package com.genersoft.iot.vmp.gb28181.service.impl;

import com.alibaba.fastjson2.JSON;
import com.genersoft.iot.vmp.common.*;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.dao.DeviceChannelMapper;
import com.genersoft.iot.vmp.gb28181.dao.DeviceMapper;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;

@Slf4j
@Service
public class InviteStreamServiceImpl implements IInviteStreamService {

    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    private final Map<String, Pending> inviteErrorCallbackMap = new ConcurrentHashMap<>();

    private static final class Pending {
        final String token = UUID.randomUUID().toString();
        final long deadline;
        final Integer playChannelId;
        final List<ErrorCallback<StreamInfo>> callbacks = new CopyOnWriteArrayList<>();
        Pending(long deadline, Integer playChannelId) { this.deadline = deadline; this.playChannelId = playChannelId; }
    }

    private void notifyCallbacks(Pending pending, int code, String msg, StreamInfo data) {
        for (ErrorCallback<StreamInfo> callback : pending.callbacks) {
            try {
                if (callback != null) callback.run(code, msg, data);
            } catch (Exception e) {
                log.warn("[点播结果通知] 单个等待者回调失败，其他等待者继续处理", e);
            }
        }
    }

    private void clearFailedPlay(Pending pending) {
        if (pending.playChannelId == null) return;
        try { removeInviteInfoByDeviceAndChannel(InviteSessionType.PLAY, pending.playChannelId); }
        catch (Exception e) { log.warn("[点播在途清理] 缓存清理失败，仍释放内存等待状态，channelId={}", pending.playChannelId, e); }
    }

    private void expire(String key, Pending pending) {
        AtomicReference<Pending> expired = new AtomicReference<>();
        inviteErrorCallbackMap.computeIfPresent(key, (k, current) -> {
            if (current != pending || pending.deadline > clock.getAsLong()) return current;
            clearFailedPlay(current);
            expired.set(current);
            return null;
        });
        if (expired.get() != null) {
            log.warn("[点播在途超时释放] key={}, attempt={}", key, pending.token);
            notifyCallbacks(pending, InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(), "点播等待视频流超时，请重试", null);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void expirePendingInvites() {
        inviteErrorCallbackMap.forEach(this::expire);
    }

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private DeviceChannelMapper deviceChannelMapper;

    /**
     * 流离开的处理
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaDepartureEvent event) {
        if ("rtsp".equals(event.getSchema()) && MediaStreamUtil.isGB28181(event.getApp(), event.getStream())) {
            InviteInfo inviteInfo = getInviteInfoByStream(null, event.getStream());
            if (inviteInfo != null && (inviteInfo.getType() == InviteSessionType.PLAY || inviteInfo.getType() == InviteSessionType.PLAYBACK)) {
                try {
                    removeInviteInfo(inviteInfo);
                    Device device = deviceMapper.getDeviceByDeviceId(inviteInfo.getDeviceId());
                    if (device != null) {
                        deviceChannelMapper.stopPlayById(inviteInfo.getChannelId());
                    }
                } catch (Exception e) {
                    log.error("[流离开] 清理Invite异常: deviceId={}, channelId={}, stream={}",
                            inviteInfo.getDeviceId(), inviteInfo.getChannelId(), inviteInfo.getStream(), e);
                }
            }
        }
    }

    @Override
    public void updateInviteInfo(InviteInfo inviteInfo) {
        if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
            updateInviteInfo(inviteInfo, Long.valueOf(userSetting.getPlayTimeout()) * 2);
        } else {
            updateInviteInfo(inviteInfo, null);
        }
    }

    @Override
    public void updateInviteInfo(InviteInfo inviteInfo, Long time) {
        if (inviteInfo == null || (inviteInfo.getDeviceId() == null || inviteInfo.getChannelId() == null)) {
            log.warn("[更新Invite信息]，参数不全： {}", JSON.toJSON(inviteInfo));
            return;
        }
        InviteInfo inviteInfoForUpdate;

        if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
            if (inviteInfo.getDeviceId() == null || inviteInfo.getChannelId() == null
                    || inviteInfo.getType() == null || inviteInfo.getStream() == null
            ) {
                return;
            }
            inviteInfoForUpdate = inviteInfo;
        } else {
            InviteInfo inviteInfoInRedis = getInviteInfo(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream());
            if (inviteInfoInRedis == null) {
                log.warn("[更新Invite信息]，未从缓存中读取到Invite信息： deviceId: {}, channel: {}, stream: {}",
                        inviteInfo.getDeviceId(), inviteInfo.getChannelId(), inviteInfo.getStream());
                return;
            }
            if (inviteInfo.getStreamInfo() != null) {
                inviteInfoInRedis.setStreamInfo(inviteInfo.getStreamInfo());
            }
            if (inviteInfo.getSsrcInfo() != null) {
                inviteInfoInRedis.setSsrcInfo(inviteInfo.getSsrcInfo());
            }
            if (inviteInfo.getStreamMode() != null) {
                inviteInfoInRedis.setStreamMode(inviteInfo.getStreamMode());
            }
            if (inviteInfo.getReceiveIp() != null) {
                inviteInfoInRedis.setReceiveIp(inviteInfo.getReceiveIp());
            }
            if (inviteInfo.getReceivePort() != null) {
                inviteInfoInRedis.setReceivePort(inviteInfo.getReceivePort());
            }
            if (inviteInfo.getStatus() != null) {
                inviteInfoInRedis.setStatus(inviteInfo.getStatus());
            }

            inviteInfoForUpdate = inviteInfoInRedis;

        }
        if (inviteInfoForUpdate.getCreateTime() == null) {
            inviteInfoForUpdate.setCreateTime(System.currentTimeMillis());
        }
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = inviteInfoForUpdate.getType() +
                ":" + inviteInfoForUpdate.getChannelId() +
                ":" + inviteInfoForUpdate.getStream();
        if (time != null && time > 0) {
            inviteInfoForUpdate.setExpirationTime(time);
        }
        redisTemplate.opsForHash().put(key, objectKey, inviteInfoForUpdate);
    }

    @Override
    public InviteInfo updateInviteInfoForStream(InviteInfo inviteInfo, String stream) {

        InviteInfo inviteInfoInDb = getInviteInfo(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream());
        if (inviteInfoInDb == null) {
            return null;
        }
        removeInviteInfo(inviteInfoInDb);
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = inviteInfo.getType() +
                ":" + inviteInfo.getChannelId() +
                ":" + stream;
        inviteInfoInDb.setStream(stream);
        if (inviteInfoInDb.getSsrcInfo() != null) {
            inviteInfoInDb.getSsrcInfo().setStream(stream);
        }
        if (InviteSessionStatus.ready == inviteInfo.getStatus()) {
            inviteInfoInDb.setExpirationTime((long) (userSetting.getPlayTimeout() * 2));
        }
        if (inviteInfoInDb.getCreateTime() == null) {
            inviteInfoInDb.setCreateTime(System.currentTimeMillis());
        }
        redisTemplate.opsForHash().put(key, objectKey, inviteInfoInDb);
        return inviteInfoInDb;
    }

    @Override
    public InviteInfo getInviteInfo(InviteSessionType type, Integer channelId, String stream) {
        String key = VideoManagerConstants.INVITE_PREFIX;
        String keyPattern = (type != null ? type : "*") +
                ":" + (channelId != null ? channelId : "*") +
                ":" + (stream != null ? stream : "*");
        ScanOptions options = ScanOptions.scanOptions().match(keyPattern).count(20).build();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(key, options)) {
            if (cursor.hasNext()) {
                Map.Entry<Object, Object> entry = cursor.next();
                cursor.close();
                if (entry.getValue() instanceof InviteInfo) {
                    return (InviteInfo) entry.getValue();
                } else {
                    log.warn("[Redis-InviteInfo] 发现脏数据并清理: key={}, value={}", entry.getKey(), entry.getValue());
                    redisTemplate.opsForHash().delete(key, entry.getKey());
                }
            }
        } catch (Exception e) {
            log.error("[Redis-InviteInfo] 查询异常: ", e);
        }
        return null;
    }

    @Override
    public List<InviteInfo> getAllInviteInfo() {
        List<InviteInfo> result = new ArrayList<>();
        String key = VideoManagerConstants.INVITE_PREFIX;
        List<Object> values = redisTemplate.opsForHash().values(key);
        if(values.isEmpty()) {
            return result;
        }
        for (Object value : values) {
            result.add((InviteInfo)value);
        }
        return result;
    }

    @Override
    public InviteInfo getInviteInfoByDeviceAndChannel(InviteSessionType type, Integer channelId) {
        return getInviteInfo(type, channelId, null);
    }

    @Override
    public InviteInfo getInviteInfoByStream(InviteSessionType type, String stream) {
        return getInviteInfo(type, null, stream);
    }

    @Override
    public void removeInviteInfo(InviteSessionType type, Integer channelId, String stream) {
        String key = VideoManagerConstants.INVITE_PREFIX;
        if (type == null && channelId == null && stream == null) {
            redisTemplate.delete(key);
            return;
        }
        String keyPattern = (type != null ? type : "*") + ":" + (channelId != null ? channelId : "*") + ":" + (stream != null ? stream : "*");
        ScanOptions options = ScanOptions.scanOptions().match(keyPattern).count(20).build();
        List<Object> objectKeys = new ArrayList<>();
        try (Cursor<Map.Entry<Object, Object>> cursor = redisTemplate.opsForHash().scan(key, options)) {
            while (cursor.hasNext()) {
                objectKeys.add(cursor.next().getKey());
            }
        } catch (Exception e) {
            log.error("[Redis-InviteInfo] 删除异常: ", e);
        }
        if (!objectKeys.isEmpty()) {
            redisTemplate.opsForHash().delete(key, objectKeys.toArray());
        }
    }

    @Override
    public void removeInviteInfoByDeviceAndChannel(InviteSessionType inviteSessionType, Integer channelId) {
        removeInviteInfo(inviteSessionType, channelId, null);
    }

    @Override
    public void removeInviteInfo(InviteInfo inviteInfo) {
        removeInviteInfo(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream());
    }

    @Override
    public void once(InviteSessionType type, Integer channelId, String stream, ErrorCallback<StreamInfo> callback) {
        String key = buildKey(type, channelId, stream);
        inviteErrorCallbackMap.compute(key, (k, current) -> {
            Pending pending = current != null ? current : new Pending(Long.MAX_VALUE, null);
            if (callback != null) pending.callbacks.add(callback);
            return pending;
        });
    }

    @Override
    public boolean onceAndFirst(InviteSessionType type, Integer channelId, String stream, ErrorCallback<StreamInfo> callback) {
        return claim(type, channelId, stream, callback).owner();
    }

    @Override
    public Attempt claim(InviteSessionType type, Integer channelId, String stream, ErrorCallback<StreamInfo> callback) {
        String key = buildKey(type, channelId, stream);
        Pending previous = inviteErrorCallbackMap.get(key);
        if (previous != null) expire(key, previous);
        AtomicReference<Attempt> result = new AtomicReference<>();
        inviteErrorCallbackMap.compute(key, (k, current) -> {
            boolean owner = current == null;
            Pending pending = owner ? new Pending(clock.getAsLong() + Math.max(1000, userSetting.getPlayTimeout()) + 1000L, type == InviteSessionType.PLAY ? channelId : null) : current;
            if (callback != null) pending.callbacks.add(callback);
            result.set(new Attempt(key, pending.token, owner));
            return pending;
        });
        return result.get();
    }

    @Override
    public boolean isCurrent(Attempt attempt) {
        Pending pending = inviteErrorCallbackMap.get(attempt.key());
        return pending != null && pending.token.equals(attempt.token());
    }

    @Override
    public boolean runIfCurrent(Attempt attempt, Runnable action) {
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        inviteErrorCallbackMap.computeIfPresent(attempt.key(), (key, pending) -> {
            if (!pending.token.equals(attempt.token())) return pending;
            action.run();
            ran.set(true);
            return pending;
        });
        return ran.get();
    }

    @Override
    public boolean complete(Attempt attempt, int code, String msg, StreamInfo data) {
        AtomicReference<Pending> completed = new AtomicReference<>();
        inviteErrorCallbackMap.computeIfPresent(attempt.key(), (key, pending) -> {
            if (!pending.token.equals(attempt.token())) return pending;
            if (code != InviteErrorCode.SUCCESS.getCode()) clearFailedPlay(pending);
            completed.set(pending);
            return null;
        });
        if (completed.get() == null) return false;
        notifyCallbacks(completed.get(), code, msg, data);
        return true;
    }

    @Override
    public boolean completeReady(Attempt attempt, java.util.function.Supplier<StreamInfo> ready) {
        AtomicReference<Pending> completed = new AtomicReference<>();
        AtomicReference<StreamInfo> data = new AtomicReference<>();
        inviteErrorCallbackMap.computeIfPresent(attempt.key(), (key, pending) -> {
            if (!pending.token.equals(attempt.token())) return pending;
            try { data.set(ready.get()); }
            catch (Exception e) { log.warn("[点播就绪处理] 处理失败，key={}", key, e); }
            if (data.get() == null) clearFailedPlay(pending);
            completed.set(pending);
            return null;
        });
        if (completed.get() == null) return false;
        int code = data.get() == null ? InviteErrorCode.ERROR_FOR_STREAM_PARSING_EXCEPTIONS.getCode() : InviteErrorCode.SUCCESS.getCode();
        notifyCallbacks(completed.get(), code, data.get() == null ? "视频流就绪处理失败" : "success", data.get());
        return true;
    }

    @Override
    public boolean isPlayPending(Integer channelId) {
        String key = buildKey(InviteSessionType.PLAY, channelId, null);
        Pending pending = inviteErrorCallbackMap.get(key);
        if (pending != null) expire(key, pending);
        return inviteErrorCallbackMap.containsKey(key);
    }

    @Override
    public void cancelPlay(Integer channelId, String reason) {
        call(InviteSessionType.PLAY, channelId, null, InviteErrorCode.ERROR_FOR_STREAM_TIMEOUT.getCode(), reason, null);
    }

    private String buildKey(InviteSessionType type, Integer channelId, String stream) {
        String key = type + ":" + channelId;
        // 如果ssrc未null那么可以实现一个通道只能一次操作，ssrc不为null则可以支持一个通道多次invite
        if (stream != null) {
            key += (":" + stream);
        }
        return key;
    }


    @Override
    public void clearInviteInfo(String deviceId) {
        List<InviteInfo> inviteInfoList = getAllInviteInfo();
        for (InviteInfo inviteInfo : inviteInfoList) {
            if (inviteInfo.getDeviceId().equals(deviceId)) {
                removeInviteInfo(inviteInfo);
            }
        }
    }

    @Override
    public int getStreamInfoCount(String mediaServerId) {
        int count = 0;
        String key = VideoManagerConstants.INVITE_PREFIX;
        List<Object> values = redisTemplate.opsForHash().values(key);
        if (values.isEmpty()) {
            return count;
        }
        for (Object value : values) {
            InviteInfo inviteInfo = (InviteInfo)value;
            if (inviteInfo != null
                    && inviteInfo.getStreamInfo() != null
                    && inviteInfo.getStreamInfo().getMediaServer() != null
                    && inviteInfo.getStreamInfo().getMediaServer().getId().equals(mediaServerId)) {
                if (inviteInfo.getType().equals(InviteSessionType.DOWNLOAD) && inviteInfo.getStreamInfo().getProgress() == 1) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    @Override
    public void call(InviteSessionType type, Integer channelId, String stream, int code, String msg, StreamInfo data) {
        String key = buildSubStreamKey(type, channelId, stream);
        Pending pending = inviteErrorCallbackMap.remove(key);
        if (pending != null) notifyCallbacks(pending, code, msg, data);
    }


    private String buildSubStreamKey(InviteSessionType type, Integer channelId, String stream) {
        String key = type + ":" + channelId;
        if (stream != null) {
            key += (":" + stream);
        }
        return key;
    }

    @Override
    public InviteInfo getInviteInfoBySSRC(String ssrc) {
        List<InviteInfo> inviteInfoList = getAllInviteInfo();
        if (inviteInfoList.isEmpty()) {
            return null;
        }
        for (InviteInfo inviteInfo : inviteInfoList) {
            if (inviteInfo.getSsrcInfo() != null && ssrc.equals(inviteInfo.getSsrcInfo().getSsrc())) {
                return inviteInfo;
            }
        }
        return null;
    }

    @Override
    public InviteInfo updateInviteInfoForSSRC(InviteInfo inviteInfo, String ssrc) {
        InviteInfo inviteInfoInDb = getInviteInfo(inviteInfo.getType(), inviteInfo.getChannelId(), inviteInfo.getStream());
        if (inviteInfoInDb == null) {
            return null;
        }
        removeInviteInfo(inviteInfoInDb);
        String key = VideoManagerConstants.INVITE_PREFIX;
        String objectKey = inviteInfo.getType() +
                ":" + inviteInfo.getChannelId() +
                ":" + inviteInfo.getStream();
        if (inviteInfoInDb.getSsrcInfo() != null) {
            inviteInfoInDb.getSsrcInfo().setSsrc(ssrc);
        }
        redisTemplate.opsForHash().put(key, objectKey, inviteInfoInDb);
        return inviteInfoInDb;
    }

    @Scheduled(fixedRate = 10000)   //定时检测,清理错误的redis数据,防止因为错误数据导致的点播不可用
    public void execute(){
        String key = VideoManagerConstants.INVITE_PREFIX;
        if(redisTemplate.opsForHash().size(key) == 0) {
            return;
        }
        List<Object> values = redisTemplate.opsForHash().values(key);
        for (Object value : values) {
            try {
                InviteInfo inviteInfo = (InviteInfo)value;
                if (inviteInfo.getStreamInfo() != null) {
                    continue;
                }
                if (inviteInfo.getCreateTime() == null || inviteInfo.getExpirationTime() == null) {
                    removeInviteInfo(inviteInfo);
                    continue;
                }
                long time = inviteInfo.getCreateTime() + inviteInfo.getExpirationTime();
                if (System.currentTimeMillis() > time) {
                    removeInviteInfo(inviteInfo);
                }
            } catch (Exception e) {
                log.error("[定时清理Invite] 处理异常，尝试删除该数据: {}", value, e);
                removeInviteInfoByValue(value);
            }
        }
    }

    private void removeInviteInfoByValue(Object value) {
        if (value instanceof InviteInfo) {
            removeInviteInfo((InviteInfo) value);
        } else {
            String key = VideoManagerConstants.INVITE_PREFIX;
            redisTemplate.opsForHash().entries(key).forEach((k, v) -> {
                if (v.equals(value)) {
                    redisTemplate.opsForHash().delete(key, k);
                }
            });
        }
    }
}
