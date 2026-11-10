package com.genersoft.iot.vmp.gb28181.service.impl;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.service.IInviteStreamService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaybackAttemptTest {
    InviteStreamServiceImpl service; AtomicLong clock;
    @BeforeEach void setup() {
        service=spy(new InviteStreamServiceImpl());clock=new AtomicLong(10000);
        UserSetting setting=new UserSetting();setting.setPlayTimeout(15000);
        ReflectionTestUtils.setField(service,"userSetting",setting);
        ReflectionTestUtils.setField(service,"clock",(java.util.function.LongSupplier)clock::get);
        doNothing().when(service).removeInviteInfoByDeviceAndChannel(any(),any());
    }
    @Test void expiredAttemptReleasesAllWaitersAndOldCompletionCannotConsumeNewAttempt() {
        AtomicInteger old=new AtomicInteger(),fresh=new AtomicInteger();
        var first=service.claim(InviteSessionType.PLAY,145,null,(c,m,d)->old.incrementAndGet());
        var waiting=service.claim(InviteSessionType.PLAY,145,null,(c,m,d)->old.incrementAndGet());
        assertTrue(first.owner());assertFalse(waiting.owner());
        clock.addAndGet(16001);service.expirePendingInvites();
        assertEquals(2,old.get());
        var next=service.claim(InviteSessionType.PLAY,145,null,(c,m,d)->fresh.incrementAndGet());
        assertTrue(next.owner());assertFalse(service.complete(first,0,"old",null));assertEquals(0,fresh.get());
        assertTrue(service.complete(next,0,"ok",null));assertEquals(1,fresh.get());assertFalse(service.isPlayPending(145));
    }
    @Test void oldReadyCallbackCannotMutateNewSessionState() {
        var old=service.claim(InviteSessionType.PLAY,145,null,null);clock.addAndGet(16001);
        var next=service.claim(InviteSessionType.PLAY,145,null,null);
        AtomicInteger mutations=new AtomicInteger();
        assertFalse(service.completeReady(old,()->{mutations.incrementAndGet();return new com.genersoft.iot.vmp.common.StreamInfo();}));
        assertEquals(0,mutations.get());assertTrue(service.isCurrent(next));
    }
    @Test void staleSipProcessingIsIgnoredAndDoesNotCompleteTheReplacementAttempt() {
        var old=service.claim(InviteSessionType.PLAY,145,null,null);clock.addAndGet(16001);
        var next=service.claim(InviteSessionType.PLAY,145,null,null);AtomicInteger writes=new AtomicInteger();
        assertFalse(service.runIfCurrent(old,writes::incrementAndGet));assertEquals(0,writes.get());
        assertTrue(service.runIfCurrent(next,writes::incrementAndGet));assertEquals(1,writes.get());
        assertTrue(service.isCurrent(next));
    }
    @Test void oneBrokenWaiterDoesNotBlockOtherWaitersOrNextPlayback() {
        AtomicInteger other=new AtomicInteger();
        var first=service.claim(InviteSessionType.PLAY,145,null,(c,m,d)->{throw new IllegalStateException("test");});
        service.claim(InviteSessionType.PLAY,145,null,(c,m,d)->other.incrementAndGet());
        assertDoesNotThrow(()->service.complete(first,-1,"failed",null));assertEquals(1,other.get());
        assertTrue(service.claim(InviteSessionType.PLAY,145,null,null).owner());
    }
    @Test void manualCancelReleasesPendingRequest() {
        AtomicInteger done=new AtomicInteger();service.claim(InviteSessionType.PLAY,145,null,(c,m,d)->done.incrementAndGet());
        service.cancelPlay(145,"manual");assertEquals(1,done.get());assertFalse(service.isPlayPending(145));
    }
    @Test void concurrentClicksHaveOnlyOneInitiator() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(8);AtomicInteger owners=new AtomicInteger(),done=new AtomicInteger();
        try {
            java.util.List<Future<?>> jobs=new java.util.ArrayList<>();
            for(int i=0;i<100;i++) jobs.add(pool.submit(()->{if(service.claim(InviteSessionType.PLAY,145,null,(c,m,d)->done.incrementAndGet()).owner())owners.incrementAndGet();}));
            for(Future<?> job:jobs) job.get(5,TimeUnit.SECONDS);
            assertEquals(1,owners.get());service.call(InviteSessionType.PLAY,145,null,0,"ok",null);assertEquals(100,done.get());
        } finally { pool.shutdownNow(); }
    }
}
