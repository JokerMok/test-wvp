package com.genersoft.iot.vmp.gb28181.controller;

import com.genersoft.iot.vmp.conf.security.SecurityUtils;
import com.genersoft.iot.vmp.gb28181.service.impl.ChannelPlayStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api/common/channel/play-status")
public class ChannelPlayStatusController {
    @Autowired private ChannelPlayStatusService service;

    @GetMapping
    public List<ChannelPlayStatusService.Status> snapshot(@RequestParam List<Integer> channelIds) {
        if (SecurityUtils.getUserInfo() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return service.snapshot(channelIds);
    }
}
