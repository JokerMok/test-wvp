package com.genersoft.iot.vmp.media.zlm.dto.hook;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * zlm hook事件中的on_rtp_server_timeout事件的参数
 * @author lin
 */
public class OnRtpServerTimeoutHookParam extends HookParam{
    private int local_port;
    private String stream_id;
    private String app;
    @JSONField(alternateNames = "tcp_mode")
    @JsonAlias("tcp_mode")
    private int tcpMode = -1;
    private boolean re_use_port;
    private String ssrc;

    public int getLocal_port() {
        return local_port;
    }

    public void setLocal_port(int local_port) {
        this.local_port = local_port;
    }

    public String getStream_id() {
        return stream_id;
    }

    public void setStream_id(String stream_id) {
        this.stream_id = stream_id;
    }

    public String getApp() {
        return app;
    }

    public void setApp(String app) {
        this.app = app;
    }

    public int getTcpMode() {
        return tcpMode;
    }

    public void setTcpMode(int tcpMode) {
        this.tcpMode = tcpMode;
    }

    public boolean isRe_use_port() {
        return re_use_port;
    }

    public void setRe_use_port(boolean re_use_port) {
        this.re_use_port = re_use_port;
    }

    public String getSsrc() {
        return ssrc;
    }

    public void setSsrc(String ssrc) {
        this.ssrc = ssrc;
    }

    @Override
    public String toString() {
        return "OnRtpServerTimeoutHookParam{" +
                "local_port=" + local_port +
                ", stream_id='" + stream_id + '\'' +
                ", tcpMode=" + tcpMode +
                ", re_use_port=" + re_use_port +
                ", ssrc='" + ssrc + '\'' +
                '}';
    }
}
