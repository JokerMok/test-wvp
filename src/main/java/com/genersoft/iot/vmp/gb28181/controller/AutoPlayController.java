package com.genersoft.iot.vmp.gb28181.controller;

import com.genersoft.iot.vmp.conf.security.SecurityUtils;
import com.genersoft.iot.vmp.conf.security.dto.LoginUser;
import com.genersoft.iot.vmp.gb28181.bean.AutoPlayTask;
import com.genersoft.iot.vmp.gb28181.service.impl.AutoPlayService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/common/channel/auto-play")
public class AutoPlayController {
    @Autowired private AutoPlayService service;
    @Data public static class Settings {
        private List<Integer> channelIds;
        private Boolean enabled;
        private Integer checkIntervalSeconds=60;
    }
    private LoginUser authenticated() {
        LoginUser user=SecurityUtils.getUserInfo();
        if (user==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        return user;
    }
    @GetMapping public List<AutoPlayTask> list() { authenticated();return service.list(); }
    @PostMapping public Map<String,Boolean> configure(@RequestBody Settings settings) {
        LoginUser user=authenticated();
        if (user.getRole()==null || !"0".equals(user.getRole().getAuthority())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可配置自动续播");
        if (settings.getEnabled()==null || settings.getCheckIntervalSeconds()==null) throw new IllegalArgumentException("启用状态和检查间隔不能为空");
        service.configure(settings.getChannelIds(),settings.getEnabled(),settings.getCheckIntervalSeconds());
        return Map.of("success",true);
    }
}
