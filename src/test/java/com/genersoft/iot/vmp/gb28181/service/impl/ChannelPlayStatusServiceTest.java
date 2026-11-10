package com.genersoft.iot.vmp.gb28181.service.impl;

import com.alibaba.fastjson2.*;
import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.dao.AutoPlayMapper;
import com.genersoft.iot.vmp.gb28181.service.*;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.ZLMRESTfulUtils;
import com.genersoft.iot.vmp.media.zlm.dto.ZLMResult;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelPlayStatusServiceTest {
    ChannelPlayStatusService service;
    IGbChannelService channels;
    IDeviceChannelService sourceChannels;
    IDeviceService devices;
    IInviteStreamService invites;
    ZLMRESTfulUtils zlm;
    AutoPlayMapper tasks;
    MediaServer media;

    @BeforeEach void setup() {
        service = new ChannelPlayStatusService();channels = mock(IGbChannelService.class);
        sourceChannels = mock(IDeviceChannelService.class);devices = mock(IDeviceService.class);
        invites = mock(IInviteStreamService.class);zlm = mock(ZLMRESTfulUtils.class);tasks = mock(AutoPlayMapper.class);
        IMediaServerService servers = mock(IMediaServerService.class);
        Object[][] fields = {{"channels",channels},{"deviceChannels",sourceChannels},{"devices",devices},{"invites",invites},
            {"mediaServers",servers},{"zlm",zlm},{"tasks",tasks},{"settings",new UserSetting()}};
        for (Object[] field : fields) ReflectionTestUtils.setField(service, (String)field[0], field[1]);
        media = new MediaServer();media.setId("node");media.setType("zlm");when(servers.getDefaultMediaServer()).thenReturn(media);
        Device device = new Device();device.setDeviceId("parent");device.setOnLine(false);when(devices.getDevice(1)).thenReturn(device);
        channel(18, "a");channel(19, "b");
    }

    void channel(int id, String rawCode) {
        CommonGBChannel channel = new CommonGBChannel();channel.setGbId(id);channel.setDataType(ChannelDataType.GB28181);
        channel.setDataDeviceId(1);channel.setGbDeviceId("published-"+rawCode);channel.setGbStatus("OFF");when(channels.getOne(id)).thenReturn(channel);
        DeviceChannel source = new DeviceChannel();source.setId(id);source.setDeviceId(rawCode);when(sourceChannels.getOneForSourceById(id)).thenReturn(source);
    }

    void media(String json) { when(zlm.getMediaList(media, null, null)).thenReturn(JSON.parseObject(json, ZLMResult.class)); }
    AutoPlayTask task(int id, boolean enabled, String state) {
        AutoPlayTask task = new AutoPlayTask();task.setChannelId(id);task.setEnabled(enabled);task.setStatus(state);return task;
    }

    @Test void liveMediaWinsOverOfflineCatalogAndStaleScheduler() {
        when(tasks.list(any())).thenReturn(List.of(task(18,true,"RECONNECTING")));
        media("{\"code\":0,\"data\":[{\"app\":\"rtp\",\"stream\":\"parent_a\",\"bytesSpeed\":200,\"tracks\":[{\"codec_type\":0,\"ready\":true}]}]}");
        var status=service.snapshot(List.of(18)).getFirst();
        assertEquals("PLAYING",status.getState());assertTrue(status.isCanStop());assertTrue(status.isAutoPlayEnabled());
    }
    @Test void successfulEmptyResponseDoesNotInheritPlayingTaskState() {
        when(tasks.list(any())).thenReturn(List.of(task(18,true,"PLAYING")));media("{\"code\":0}");
        var status=service.snapshot(List.of(18)).getFirst();assertEquals("STOPPED",status.getState());assertTrue(status.isCanStop());
    }
    @Test void oneNodeQueryResolvesTwoRawSourcesIndependently() {
        media("{\"code\":0,\"data\":[{\"app\":\"rtp\",\"stream\":\"parent_a\",\"bytesSpeed\":20,\"tracks\":[{\"codec_type\":0}]}]}");
        var states=service.snapshot(List.of(18,19));assertEquals("PLAYING",states.get(0).getState());assertEquals("STOPPED",states.get(1).getState());
        verify(zlm,times(1)).getMediaList(media,null,null);
    }
    @Test void nodeFailureIsUnknownAndQueriedOnlyOnce() {
        when(zlm.getMediaList(media,null,null)).thenThrow(new IllegalStateException("synthetic outage"));
        var states=service.snapshot(List.of(18,19));assertTrue(states.stream().allMatch(s -> "UNKNOWN".equals(s.getState())));
        verify(zlm,times(1)).getMediaList(media,null,null);
    }
    @Test void pendingInviteAllowsStopWithoutRegisteredMedia() {
        media("{\"code\":0}");when(invites.isPlayPending(18)).thenReturn(true);
        var status=service.snapshot(List.of(18)).getFirst();assertEquals("STARTING",status.getState());assertTrue(status.isCanStop());
    }
    @Test void zeroSpeedIsStalledNotPlaying() {
        media("{\"code\":0,\"data\":[{\"app\":\"rtp\",\"stream\":\"parent_a\",\"bytesSpeed\":0,\"tracks\":[{\"codec_type\":0}]}]}");
        assertEquals("STALLED",service.snapshot(List.of(18)).getFirst().getState());
    }
    @Test void pausedTaskCanStillHaveLiveMedia() {
        when(tasks.list(any())).thenReturn(List.of(task(18,false,"PAUSED")));
        media("{\"code\":0,\"data\":[{\"app\":\"rtp\",\"stream\":\"parent_a\",\"bytesSpeed\":20,\"tracks\":[{\"codec_type\":0}]}]}");
        var status=service.snapshot(List.of(18)).getFirst();assertEquals("PLAYING",status.getState());assertTrue(status.isAutoPlayConfigured());assertFalse(status.isAutoPlayEnabled());
    }
    @Test void storedStreamDoesNotProveMediaButStillAllowsCleanup() {
        channels.getOne(18).setStreamId("stale");media("{\"code\":0}");
        var status=service.snapshot(List.of(18)).getFirst();assertEquals("STOPPED",status.getState());assertTrue(status.isCanStop());
    }
    @Test void invalidIdsRejectedWithoutMediaOrTaskCalls() {
        assertThrows(IllegalArgumentException.class,()->service.snapshot(List.of()));
        assertThrows(IllegalArgumentException.class,()->service.snapshot(List.of(-1)));
        verifyNoInteractions(zlm,tasks);
    }
}
