package com.genersoft.iot.vmp.service.impl;
import com.genersoft.iot.vmp.conf.*;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.hook.*;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.bean.RTPServerParam;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaybackRtpTimeoutTest {
    @Test void timeoutNotifiesBeforeCleanupAndCleanupFailureDoesNotLoseResult(){
        RtpServerServiceImpl service=new RtpServerServiceImpl();var mediaService=mock(IMediaServerService.class);var tasks=mock(DynamicTask.class);var subscribe=new HookSubscribe();
        ReflectionTestUtils.setField(service,"mediaServerService",mediaService);ReflectionTestUtils.setField(service,"dynamicTask",tasks);ReflectionTestUtils.setField(service,"subscribe",subscribe);ReflectionTestUtils.setField(service,"userSetting",new UserSetting());
        MediaServer media=new MediaServer();media.setId("media");media.setRtpEnable(true);
        when(mediaService.createRTPServer(any(),anyString(),anyString(),anyLong(),isNull(),anyBoolean(),anyBoolean(),anyBoolean(),anyInt())).thenReturn(30106);
        AtomicInteger notified=new AtomicInteger();
        doAnswer(call->{assertEquals(1,notified.get());throw new IllegalStateException("synthetic cleanup failure");}).when(mediaService).closeRTPServer(any(),anyString(),anyString());
        service.openCommonRTPServer(new RTPServerParam(media,"rtp","device_camera",1234L,null,false,false,false,0),(c,m,d)->notified.incrementAndGet());
        ArgumentCaptor<Runnable> timeout=ArgumentCaptor.forClass(Runnable.class);verify(tasks).startDelay(anyString(),timeout.capture(),anyInt());
        assertDoesNotThrow(()->timeout.getValue().run());assertEquals(1,notified.get());assertTrue(subscribe.getAll().isEmpty());
        timeout.getValue().run();assertEquals(1,notified.get());
    }
    @Test void oldSubscriptionCleanupCannotRemoveTheReplacement(){
        HookSubscribe subscriptions=new HookSubscribe();Hook old=Hook.getInstance(HookType.on_media_arrival,"rtp","device_camera");Hook fresh=Hook.getInstance(HookType.on_media_arrival,"rtp","device_camera");
        HookSubscribe.Event first=data->{},next=data->{};subscriptions.addSubscribe(old,first);subscriptions.addSubscribe(fresh,next);
        assertFalse(subscriptions.removeSubscribe(old,first));assertEquals(1,subscriptions.getAll().size());assertTrue(subscriptions.removeSubscribe(fresh,next));assertTrue(subscriptions.getAll().isEmpty());
    }
}
