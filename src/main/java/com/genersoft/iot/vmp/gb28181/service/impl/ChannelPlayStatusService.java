package com.genersoft.iot.vmp.gb28181.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.dao.AutoPlayMapper;
import com.genersoft.iot.vmp.gb28181.service.*;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.ZLMResult;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/** Read-only media snapshot shared by the common and device channel pages. */
@Service
public class ChannelPlayStatusService {
    @Autowired private IGbChannelService channels;
    @Autowired private IDeviceChannelService deviceChannels;
    @Autowired private IDeviceService devices;
    @Autowired private IInviteStreamService invites;
    @Autowired private IMediaServerService mediaServers;
    @Autowired private ZLMRESTfulUtils zlm;
    @Autowired private AutoPlayMapper tasks;
    @Autowired private UserSetting settings;

    @Data
    public static class Status {
        private int channelId;
        private String state = "UNKNOWN";
        private boolean canStop;
        private boolean autoPlayConfigured;
        private boolean autoPlayEnabled;
        private long checkedAt;
    }

    public List<Status> snapshot(List<Integer> channelIds) {
        if (channelIds == null || channelIds.isEmpty() || channelIds.size() > 100
                || channelIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("请选择1至100个有效通道");
        }
        Map<Integer, AutoPlayTask> configured = new HashMap<>();
        // A missing migration must not prevent the existing channel pages from reading media state.
        try { for (AutoPlayTask task : tasks.list(settings.getServerId())) configured.put(task.getChannelId(), task); }
        catch (org.springframework.dao.DataAccessException ignored) { }
        Map<String, ZLMResult<JSONArray>> mediaSnapshots = new HashMap<>();
        List<Status> result = new ArrayList<>();
        for (int id : new LinkedHashSet<>(channelIds)) {
            Status status = new Status();status.setChannelId(id);
            AutoPlayTask task = configured.get(id);
            status.setAutoPlayConfigured(task != null);
            status.setAutoPlayEnabled(task != null && Boolean.TRUE.equals(task.getEnabled()));
            status.setCanStop(status.isAutoPlayEnabled());
            try {
                CommonGBChannel channel = channels.getOne(id);
                if (channel == null) { status.setState("REMOVED");result.add(status);continue; }
                // Stored stream IDs and scheduler status are not evidence of current incoming video.
                status.setCanStop(status.isCanStop() || channel.getStreamId() != null && !channel.getStreamId().isBlank());
                if (!Integer.valueOf(ChannelDataType.GB28181).equals(channel.getDataType())) {
                    result.add(status);continue;
                }
                DeviceChannel source = deviceChannels.getOneForSourceById(id);
                Device device = devices.getDevice(channel.getDataDeviceId());
                if (source == null || device == null || source.getDeviceId() == null) { result.add(status);continue; }
                String stream = device.getDeviceId() + "_" + source.getDeviceId();
                MediaServer media = mediaServers.getMediaServerByAppAndStream("rtp", stream);
                if (media == null) media = mediaServers.getDefaultMediaServer();
                if (media == null || !"zlm".equalsIgnoreCase(media.getType())) { result.add(status);continue; }
                // Query once per node for this page, rather than once per visible channel.
                String mediaId = media.getId();
                if (!mediaSnapshots.containsKey(mediaId)) {
                    ZLMResult<JSONArray> snapshot;
                    try { snapshot = zlm.getMediaList(media, null, null); }
                    catch (Exception unavailable) { snapshot = null; }
                    mediaSnapshots.put(mediaId, snapshot);
                }
                AutoPlayService.Health health = AutoPlayService.mediaHealth(mediaSnapshots.get(mediaId), "rtp", stream);
                boolean pending = invites.isPlayPending(source.getId());
                status.setState(switch (health) {
                    case HEALTHY -> "PLAYING";
                    case QUIET -> "STALLED";
                    case MISSING -> pending ? "STARTING" : "STOPPED";
                    case UNKNOWN -> "UNKNOWN";
                });
                status.setCanStop(status.isCanStop() || pending || health == AutoPlayService.Health.HEALTHY || health == AutoPlayService.Health.QUIET);
            } catch (Exception ignored) {
                // An unavailable node is unknown, never a confirmed stopped flow.
                status.setState("UNKNOWN");
            } finally { status.setCheckedAt(System.currentTimeMillis()); }
            result.add(status);
        }
        return result;
    }
}
