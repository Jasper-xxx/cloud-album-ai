package com.memory.xzp.controller;

import com.memory.xzp.common.*;
import com.memory.xzp.config.AgentAccessGuard;
import com.memory.xzp.service.AgentDiscoveryJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent/discoveryJobs")
@RequiredArgsConstructor
public class AgentDiscoveryController {
    private final AgentDiscoveryJobService service;
    @GetMapping public BaseResponse<?> recent(){return ResultUtil.success(service.recent(AgentAccessGuard.currentUserId()),"任务查询成功");}
    @GetMapping("/{id}") public BaseResponse<?> status(@PathVariable String id){return ResultUtil.success(service.status(id,AgentAccessGuard.currentUserId()),"任务状态查询成功");}
    @PostMapping("/{id}/cancel") public BaseResponse<?> cancel(@PathVariable String id){return ResultUtil.success(service.cancel(id,AgentAccessGuard.currentUserId()),"已查询取消结果");}
}
