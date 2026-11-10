package com.genersoft.iot.vmp.gb28181.service.impl;

import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.dao.AutoPlayMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class AutoPlayPolicy {
    @Autowired private AutoPlayMapper mapper;
    @Autowired private UserSetting userSetting;
    private final com.github.benmanes.caffeine.cache.Cache<Integer, Object> locks =
        com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(10000).expireAfterAccess(java.time.Duration.ofHours(1)).build();

    public Object lock(int channelId) { return locks.get(channelId, key -> new Object()); }

    public void manualStop(int channelId, Runnable stop) {
        synchronized (lock(channelId)) { pauseManual(channelId); stop.run(); }
    }


    public void pauseManual(int channelId) {
        try { mapper.pause(userSetting.getServerId(), channelId, "人工停止，自动续播已暂停"); }
        catch (DataAccessException e) { throw new IllegalStateException("无法确认自动续播已暂停，请先完成数据库升级", e); }
    }

    public boolean keepAlive(String mediaId, String app, String stream) {
        try { return mapper.keeping(userSetting.getServerId(), mediaId, app, stream) > 0; }
        catch (DataAccessException e) { return false; }
    }
}
