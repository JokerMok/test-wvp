package com.genersoft.iot.vmp.gb28181.service.impl;
import com.alibaba.fastjson2.*;
import com.genersoft.iot.vmp.common.*;
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
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AutoPlayServiceTest {
    AutoPlayService service; AutoPlayMapper mapper; IGbChannelPlayService play; ZLMRESTfulUtils zlm; MediaServer media; AutoPlayTask task; CommonGBChannel channel; IInviteStreamService invites;
    @BeforeEach void setup() {
        service=new AutoPlayService();mapper=mock(AutoPlayMapper.class);play=mock(IGbChannelPlayService.class);zlm=mock(ZLMRESTfulUtils.class);invites=mock(IInviteStreamService.class);
        var channels=mock(IGbChannelService.class);var devices=mock(IDeviceService.class);var deviceChannels=mock(IDeviceChannelService.class);var mediaServers=mock(IMediaServerService.class);var policy=mock(AutoPlayPolicy.class);
        UserSetting setting=new UserSetting();setting.setServerId("test");setting.setPlayTimeout(15000);
        ReflectionTestUtils.setField(service,"mapper",mapper);ReflectionTestUtils.setField(service,"playService",play);ReflectionTestUtils.setField(service,"channelService",channels);
        ReflectionTestUtils.setField(service,"deviceService",devices);ReflectionTestUtils.setField(service,"deviceChannels",deviceChannels);ReflectionTestUtils.setField(service,"mediaServers",mediaServers);
        ReflectionTestUtils.setField(service,"zlm",zlm);ReflectionTestUtils.setField(service,"invites",invites);ReflectionTestUtils.setField(service,"userSetting",setting);ReflectionTestUtils.setField(service,"policy",policy);
        when(policy.lock(anyInt())).thenReturn(new Object());
        task=new AutoPlayTask();task.setChannelId(200);task.setServerId("test");task.setEnabled(true);task.setRevision(1L);task.setLeaseOwner("lease");task.setLeaseUntil(System.currentTimeMillis()+120000);task.setQuietCount(0);task.setFailureCount(0);task.setLastSuccessTime(0L);
        when(mapper.get("test",200)).thenReturn(task);when(mapper.preparing(any())).thenReturn(1);when(mapper.finish(any())).thenReturn(1);
        channel=new CommonGBChannel();channel.setGbId(200);channel.setGbDeviceId("camera");channel.setGbStatus("ON");channel.setDataType(ChannelDataType.GB28181);channel.setDataDeviceId(1);
        when(channels.getOne(200)).thenReturn(channel);Device device=new Device();device.setOnLine(true);device.setDeviceId("device");when(devices.getDevice(1)).thenReturn(device);
        DeviceChannel source=new DeviceChannel();source.setId(145);when(deviceChannels.getOneForSourceById(200)).thenReturn(source);
        media=new MediaServer();media.setId("zlm");media.setType("zlm");when(mediaServers.getDefaultMediaServer()).thenReturn(media);when(mediaServers.getOne("zlm")).thenReturn(media);
    }
    @AfterEach void close(){service.close();}
    ZLMResult<JSONArray> result(long speed,boolean videoReady){
        JSONObject track=new JSONObject();track.put("codec_type",0);track.put("ready",videoReady);
        JSONObject item=new JSONObject();item.put("app","rtp");item.put("stream","device_camera");item.put("schema","rtsp");item.put("bytesSpeed",speed);item.put("tracks",JSONArray.of(track));
        ZLMResult<JSONArray> r=new ZLMResult<>();r.setCode(0);r.setData(JSONArray.of(item));return r;
    }
    @Test void mediaQueryFailureIsUnknownAndNeverStartsOrStopsStream(){when(zlm.getMediaList(any(),any(),any())).thenReturn(null);service.check(task);assertEquals("UNKNOWN",task.getStatus());verifyNoInteractions(play);}
    @Test void healthySourceDoesNotRestartOrResetRecoveryVersion(){task.setLastSuccessTime(123L);when(zlm.getMediaList(any(),any(),any())).thenReturn(result(100,true));service.check(task);assertEquals("PLAYING",task.getStatus());assertEquals(123L,task.getLastSuccessTime());verifyNoInteractions(play);}
    @Test void firstQuietSampleWaitsAndSecondSampleRecovers(){
        when(zlm.getMediaList(any(),any(),any())).thenReturn(result(0,true));service.check(task);assertEquals("QUIET",task.getStatus());verifyNoInteractions(play);
        service.check(task);verify(play).stopPlay(channel);verify(play).play(eq(channel),isNull(),anyBoolean(),any());
    }
    @Test void pendingSourceChannelIsNotStartedTwice(){when(invites.isPlayPending(145)).thenReturn(true);service.check(task);verifyNoInteractions(zlm,play);assertEquals("WAITING",task.getStatus());}
    @Test void offlineDeviceWaits(){channel.setGbStatus("OFF");service.check(task);verifyNoInteractions(zlm,play);assertEquals("OFFLINE",task.getStatus());}
    @Test void stoppedOrReconfiguredTaskCannotDoWork(){task.setEnabled(false);service.check(task);verifyNoInteractions(zlm,play);}
    @Test void missingSourceStartsOnceAndLateSuccessCannotOverrideManualPause(){
        ZLMResult<JSONArray> empty=new ZLMResult<>();empty.setCode(0);empty.setData(new JSONArray());when(zlm.getMediaList(any(),any(),any())).thenReturn(empty);
        service.check(task);
        assertEquals(1,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(service,"recoveries")).availablePermits());
        org.mockito.ArgumentCaptor<com.genersoft.iot.vmp.service.bean.ErrorCallback<StreamInfo>> cb=org.mockito.ArgumentCaptor.forClass(com.genersoft.iot.vmp.service.bean.ErrorCallback.class);
        verify(play).play(eq(channel),isNull(),anyBoolean(),cb.capture());task.setEnabled(false);cb.getValue().run(0,"ok",new StreamInfo());verify(mapper,never()).finish(any());assertEquals(2,((java.util.concurrent.Semaphore)ReflectionTestUtils.getField(service,"recoveries")).availablePermits());
    }
    @Test void missingMetricsAreUnknownAndUnreadyVideoIsNotHealthy(){
        ZLMResult<JSONArray> r=result(100,false);when(zlm.getMediaList(any(),any(),any())).thenReturn(r);assertEquals(AutoPlayService.Health.QUIET,service.probe(media,"rtp","device_camera"));r.getData().getJSONObject(0).remove("bytesSpeed");assertEquals(AutoPlayService.Health.UNKNOWN,service.probe(media,"rtp","device_camera"));
    }
    @Test void thirdAsynchronousRecoveryWaitsUntilAnotherCompletes(){
        ZLMResult<JSONArray> empty=new ZLMResult<>();empty.setCode(0);empty.setData(new JSONArray());when(zlm.getMediaList(any(),any(),any())).thenReturn(empty);
        java.util.concurrent.Semaphore permits=(java.util.concurrent.Semaphore)ReflectionTestUtils.getField(service,"recoveries");
        assertTrue(permits.tryAcquire(2));service.check(task);verifyNoInteractions(play);assertEquals("WAITING",task.getStatus());permits.release(2);
        service.check(task);verify(play).play(eq(channel),isNull(),anyBoolean(),any());assertEquals(1,permits.availablePermits());
    }
    @Test void unknownSampleBreaksConsecutiveQuietDetection(){
        task.setQuietCount(1);when(zlm.getMediaList(any(),any(),any())).thenReturn(null);service.check(task);assertEquals(0,task.getQuietCount());verifyNoInteractions(play);
    }
    @Test void approvedDefaultsAndBackoffAreBounded(){assertEquals(60000,AutoPlayService.interval(new AutoPlayTask()));assertArrayEquals(new long[]{30000,60000,120000,300000,300000},java.util.stream.IntStream.rangeClosed(1,5).mapToLong(AutoPlayService::retryDelay).toArray());}
}
