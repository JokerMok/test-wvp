package com.genersoft.iot.vmp.gb28181.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.dao.AutoPlayMapper;
import com.genersoft.iot.vmp.gb28181.service.*;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.ZLMResult;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.concurrent.*;

@Service
@Slf4j
public class AutoPlayService {
    @Autowired private AutoPlayMapper mapper;
    @Autowired private UserSetting userSetting;
    @Autowired private IGbChannelService channelService;
    @Autowired private IGbChannelPlayService playService;
    @Autowired private IDeviceService deviceService;
    @Autowired private IDeviceChannelService deviceChannels;
    @Autowired private IInviteStreamService invites;
    @Autowired private IMediaServerService mediaServers;
    @Autowired private ZLMRESTfulUtils zlm;
    @Autowired private AutoPlayPolicy policy;
    private volatile long lastDatabaseWarning;
    private final Semaphore recoveries = new Semaphore(2);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,
        new SynchronousQueue<>(),r -> { Thread t=new Thread(r,"auto-play-check");t.setDaemon(true);return t; });
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t=new Thread(r,"auto-play-deadline");t.setDaemon(true);return t;
    });
    public enum Health { HEALTHY, MISSING, QUIET, UNKNOWN }

    public List<AutoPlayTask> list() { return mapper.list(userSetting.getServerId()); }

    @Transactional
    public void configure(List<Integer> channelIds, boolean enabled, int interval) {
        if (channelIds==null || channelIds.isEmpty() || channelIds.size()>1000) throw new IllegalArgumentException("请选择1至1000个通道");
        if (interval<15 || interval>3600) throw new IllegalArgumentException("检查间隔应为15至3600秒");
        for (Integer id : new HashSet<>(channelIds)) {
            if (id==null || id<=0) throw new IllegalArgumentException("通道编号不合法");
            CommonGBChannel channel=channelService.getOne(id);
            if (channel==null) throw new IllegalArgumentException("通道不存在："+id);
            if (enabled && (Integer.valueOf(1).equals(channel.getGbParental()) || channel.getDataType()==null || !java.util.Set.of(ChannelDataType.GB28181,ChannelDataType.STREAM_PROXY,ChannelDataType.JT_1078).contains(channel.getDataType())))
                throw new IllegalArgumentException("目录或纯推流通道不能主动续播："+channel.getGbName());
            AutoPlayTask task=new AutoPlayTask();task.setServerId(userSetting.getServerId());task.setChannelId(id);
            task.setEnabled(enabled);task.setCheckIntervalSeconds(interval);task.setStatus(enabled?"WAITING":"PAUSED");mapper.save(task);
        }
    }

    @Scheduled(fixedDelay=5000,initialDelay=10000)
    public void tick() {
        try {
            for (AutoPlayTask task : mapper.due(userSetting.getServerId(),System.currentTimeMillis())) {
                if (workers.getActiveCount()>=2) break;
                long now=System.currentTimeMillis();String owner=UUID.randomUUID().toString();
                if (mapper.acquire(task.getServerId(),task.getChannelId(),task.getRevision(),owner,now,now+Math.max(120000L,userSetting.getPlayTimeout()*2L+30000))!=1) continue;
                task.setLeaseOwner(owner);task.setLastCheckTime(now);
                try { workers.execute(() -> check(task)); }
                catch (RejectedExecutionException e) { settle(task,"UNKNOWN","等待其他通道检查完成",5000,false); }
            }
        } catch (Exception e) {
            if (System.currentTimeMillis()-lastDatabaseWarning>60000) {
                lastDatabaseWarning=System.currentTimeMillis();log.warn("[自动续播] 读取配置失败，请核对增量数据库升级；未发起自动点播");
            }
        }
    }

    boolean current(AutoPlayTask task) {
        AutoPlayTask latest=mapper.get(task.getServerId(),task.getChannelId());
        return latest!=null && Boolean.TRUE.equals(latest.getEnabled()) && Objects.equals(latest.getRevision(),task.getRevision())
            && Objects.equals(latest.getLeaseOwner(),task.getLeaseOwner()) && latest.getLeaseUntil()!=null && latest.getLeaseUntil()>System.currentTimeMillis();
    }

    void check(AutoPlayTask task) {
        try {
            if (!current(task)) return;
            CommonGBChannel channel=channelService.getOne(task.getChannelId());
            if (channel==null) { mapper.pause(task.getServerId(),task.getChannelId(),"通道已删除");return; }
            if (!"ON".equalsIgnoreCase(channel.getGbStatus())) { settle(task,"OFFLINE","设备离线，等待上线",interval(task),false);return; }
            DeviceChannel sourceChannel = Integer.valueOf(ChannelDataType.GB28181).equals(channel.getDataType()) ? deviceChannels.getOneForSourceById(channel.getGbId()) : null;
            if (invites.isPlayPending(sourceChannel == null ? channel.getGbId() : sourceChannel.getId())) { settle(task,"WAITING","点播正在建立",interval(task),false);return; }
            String stream=task.getStreamId();String app=task.getApp()==null?"rtp":task.getApp();
            if (channel.getStreamId()!=null && !channel.getStreamId().isBlank()) stream=channel.getStreamId();
            if (Integer.valueOf(ChannelDataType.GB28181).equals(channel.getDataType())) {
                Device device=deviceService.getDevice(channel.getDataDeviceId());
                if (device==null || !device.isOnLine()) { settle(task,"OFFLINE","所属设备离线，等待上线",interval(task),false);return; }
                stream=device.getDeviceId()+"_"+channel.getGbDeviceId();app="rtp";
            }
            MediaServer media=stream==null?null:mediaServers.getMediaServerByAppAndStream(app,stream);
            if (media==null && task.getMediaServerId()!=null) media=mediaServers.getOne(task.getMediaServerId());
            if (media==null) media=mediaServers.getDefaultMediaServer();
            if (media==null || !"zlm".equalsIgnoreCase(media.getType())) { settle(task,"UNKNOWN","媒体节点不可用或不支持状态检查",interval(task),false);return; }
            task.setMediaServerId(media.getId());task.setApp(app);task.setStreamId(stream);
            Health health=probe(media,app,stream);
            if (health==Health.UNKNOWN) { task.setQuietCount(0); settle(task,"UNKNOWN","媒体状态查询失败，暂不重拉",interval(task),false);return; }
            if (health==Health.HEALTHY) { task.setQuietCount(0);task.setFailureCount(0);settle(task,"PLAYING","",interval(task),false);return; }
            if (health==Health.QUIET) {
                task.setQuietCount(value(task.getQuietCount())+1);
                if (task.getQuietCount()<2) { settle(task,"QUIET","视频数据暂未增长，下一轮复核",interval(task),false);return; }
            }
            if (!recoveries.tryAcquire()) { settle(task,"WAITING","等待其他通道恢复完成",5000,false);return; }
            java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable release = () -> { if (ended.compareAndSet(false,true)) recoveries.release(); };
            try {
            if (!current(task) || mapper.preparing(task)!=1) { release.run(); return; }
            if (health==Health.QUIET) {
                // Stop only after two successful health queries reported a stalled source.
                try { playService.stopPlay(channel); }
                catch (Exception e) { release.run(); throw e; }
                if (!current(task)) { release.run(); return; }
            }
            log.info("[自动续播] 发起恢复，channelId={}, 编号={}",channel.getGbId(),channel.getGbDeviceId());
            ScheduledFuture<?> timeout=deadlines.schedule(() -> { release.run(); fail(task,"点播等待视频流超时"); },userSetting.getPlayTimeout()+5000L,TimeUnit.MILLISECONDS);
            try {
                synchronized (policy.lock(task.getChannelId())) {
                if (!current(task)) { timeout.cancel(false); release.run(); return; }
                playService.play(channel,null,userSetting.getRecordSip(),(code,msg,info) -> {
                    if (!ended.compareAndSet(false,true)) return;
                    recoveries.release();
                    timeout.cancel(false);
                    synchronized (task) {
                    if (!current(task)) return;
                    if (code==InviteErrorCode.SUCCESS.getCode() && info!=null) {
                        task.setStreamId(info.getStream());task.setApp(info.getApp());
                        if (info.getMediaServer()!=null) task.setMediaServerId(info.getMediaServer().getId());
                        task.setQuietCount(0);task.setFailureCount(0);task.setLastSuccessTime(System.currentTimeMillis());
                        settle(task,"PLAYING","",interval(task),false);
                    } else fail(task,"点播失败，错误码："+code);
                    }
                });
                }
            } catch (Exception e) { timeout.cancel(false);release.run();fail(task,"点播调用失败"); }
            } catch (Exception e) { release.run(); throw e; }
        } catch (Exception e) { settle(task,"UNKNOWN","检查失败，等待下一轮",interval(task),false);log.warn("[自动续播] 检查异常，channelId={}",task.getChannelId(),e); }
    }

    Health probe(MediaServer media,String app,String stream) {
        ZLMResult<JSONArray> result=zlm.getMediaList(media,app,stream==null || stream.isBlank() ? "__wvp_auto_health_probe__" : stream);
        if (result==null || result.getCode()!=0 || result.getData()==null) return Health.UNKNOWN;
        if (stream==null || stream.isBlank()) return Health.MISSING;
        JSONObject found=null;
        for (Object item : result.getData()) {
            JSONObject info=(JSONObject)item;
            if (stream.equals(info.getString("stream")) && app.equals(info.getString("app"))) {
                found=info;if ("rtsp".equals(info.getString("schema"))) break;
            }
        }
        if (found==null) return Health.MISSING;
        Long speed=found.getLong("bytesSpeed");
        if (speed==null) return Health.UNKNOWN;
        JSONArray tracks=found.getJSONArray("tracks");boolean video=false;
        if (tracks!=null) for (Object item:tracks) {
            JSONObject track=(JSONObject)item;
            if (Integer.valueOf(0).equals(track.getInteger("codec_type")) && !Boolean.FALSE.equals(track.getBoolean("ready"))) video=true;
        }
        return speed>0 && video ? Health.HEALTHY : Health.QUIET;
    }

    static int value(Integer n) { return n==null?0:n; }
    static long interval(AutoPlayTask t) { return Math.max(15,t.getCheckIntervalSeconds()==null?60:t.getCheckIntervalSeconds())*1000L; }
    static long retryDelay(int failureCount) { return new long[]{30000,60000,120000,300000}[Math.min(3,Math.max(0,failureCount-1))]; }
    private void fail(AutoPlayTask task,String reason) {
        synchronized (task) {
            if (!current(task)) return;
            task.setFailureCount(value(task.getFailureCount())+1);
            settle(task,"RETRYING",reason,retryDelay(task.getFailureCount()),false);
        }
    }
    private synchronized void settle(AutoPlayTask task,String status,String reason,long delay,boolean unused) {
        task.setStatus(status);task.setLastError(reason);task.setNextCheckTime(System.currentTimeMillis()+delay);
        if (mapper.finish(task)==1 && !"PLAYING".equals(status)) log.info("[自动续播] channelId={}, 状态={}, 原因={}",task.getChannelId(),status,reason);
    }
    @PreDestroy public void close() { workers.shutdownNow();deadlines.shutdownNow(); }
}
