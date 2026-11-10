package com.genersoft.iot.vmp.gb28181.bean;

import lombok.Data;

@Data
public class AutoPlayTask {
    private Integer channelId;
    private String serverId;
    private Boolean enabled;
    private Integer checkIntervalSeconds = 60;
    private Long revision;
    private String status;
    private Integer failureCount;
    private Integer quietCount;
    private String lastError;
    private Long lastCheckTime;
    private Long nextCheckTime;
    private Long lastSuccessTime;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String leaseOwner;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Long leaseUntil;
    private String mediaServerId;
    private String app;
    private String streamId;
    private String channelName;
    private String channelCode;
}
