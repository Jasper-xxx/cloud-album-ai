package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.config.AsyncTaskAdminGuard;
import com.memory.xzp.model.dto.task.AsyncTaskBatchActionRequest;
import com.memory.xzp.model.enums.AsyncTaskType;
import com.memory.xzp.model.vo.task.AsyncTaskBatchActionVO;
import com.memory.xzp.model.vo.task.AsyncTaskVO;
import com.memory.xzp.model.vo.task.RecoveryScanStatusVO;
import com.memory.xzp.service.AsyncTaskRecoveryService;
import com.memory.xzp.service.AsyncTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/asyncTask")
@Tag(name = "异步任务")
public class AsyncTaskController {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskController.class);

    @Resource
    private AsyncTaskService asyncTaskService;

    @Resource
    private AsyncTaskAdminGuard asyncTaskAdminGuard;

    @Resource
    private AsyncTaskRecoveryService asyncTaskRecoveryService;

    @GetMapping("/list")
    @Operation(summary = "查询当前用户的异步任务")
    public BaseResponse<Page<AsyncTaskVO>> list(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status
    ) {
        StpUtil.checkLogin();
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        return ResultUtil.success(
                asyncTaskService.listUserTasks(userId, current, size, status),
                "查询成功"
        );
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "查询当前用户的异步任务详情")
    public BaseResponse<AsyncTaskVO> detail(@PathVariable Long taskId) {
        StpUtil.checkLogin();
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        return ResultUtil.success(
                asyncTaskService.getUserTask(taskId, userId),
                "查询成功"
        );
    }

    @PostMapping("/{taskId}/retry")
    @Operation(summary = "手动重试失败或死信任务")
    public BaseResponse<?> retry(@PathVariable Long taskId) {
        StpUtil.checkLogin();
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        asyncTaskService.retryTask(taskId, userId);
        return ResultUtil.success("任务已重新提交");
    }

    @GetMapping("/admin/list")
    @Operation(summary = "管理员检索异步任务")
    public BaseResponse<Page<AsyncTaskVO>> adminList(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String fileId
    ) {
        asyncTaskAdminGuard.checkAdmin();
        return ResultUtil.success(
                asyncTaskService.listAdminTasks(
                        current,
                        size,
                        status,
                        taskType,
                        userId,
                        fileId
                ),
                "查询成功"
        );
    }

    @PostMapping("/admin/retry")
    @Operation(summary = "管理员批量重试失败或死信任务")
    public BaseResponse<AsyncTaskBatchActionVO> adminRetry(
            @RequestBody AsyncTaskBatchActionRequest request
    ) {
        Long adminUserId = asyncTaskAdminGuard.checkAdmin();
        AsyncTaskBatchActionVO result = asyncTaskService.retryTasks(
                request == null ? null : request.getTaskIds()
        );
        log.info(
                "Async task admin batch retry: adminUserId={}, requested={}, updated={}",
                adminUserId,
                result.getRequestedCount(),
                result.getUpdatedCount()
        );
        return ResultUtil.success(
                result,
                "批量重试已提交"
        );
    }

    @PostMapping("/admin/dead/cancel")
    @Operation(summary = "管理员批量取消死信任务")
    public BaseResponse<AsyncTaskBatchActionVO> cancelDeadTasks(
            @RequestBody AsyncTaskBatchActionRequest request
    ) {
        Long adminUserId = asyncTaskAdminGuard.checkAdmin();
        AsyncTaskBatchActionVO result = asyncTaskService.cancelDeadTasks(
                request == null ? null : request.getTaskIds()
        );
        log.info(
                "Async task admin cancelled dead tasks: adminUserId={}, requested={}, updated={}",
                adminUserId,
                result.getRequestedCount(),
                result.getUpdatedCount()
        );
        return ResultUtil.success(
                result,
                "死信任务已取消"
        );
    }

    @GetMapping("/admin/recovery")
    @Operation(summary = "查询补偿扫描开关")
    public BaseResponse<List<RecoveryScanStatusVO>> recoveryStatuses() {
        asyncTaskAdminGuard.checkAdmin();
        return ResultUtil.success(asyncTaskRecoveryService.listStatuses(), "查询成功");
    }

    @PostMapping("/admin/recovery/{taskType}/enabled")
    @Operation(summary = "修改补偿扫描运行时开关")
    public BaseResponse<?> setRecoveryEnabled(
            @PathVariable String taskType,
            @RequestParam boolean enabled
    ) {
        Long adminUserId = asyncTaskAdminGuard.checkAdmin();
        AsyncTaskType parsedType = asyncTaskRecoveryService.parseSupportedType(taskType);
        asyncTaskRecoveryService.setEnabled(parsedType, enabled);
        log.info(
                "Async task recovery switch changed: adminUserId={}, taskType={}, enabled={}",
                adminUserId,
                parsedType,
                enabled
        );
        return ResultUtil.success(enabled ? "补偿扫描已启用" : "补偿扫描已停用");
    }

    @PostMapping("/admin/recovery/{taskType}/run")
    @Operation(summary = "立即执行一次补偿扫描")
    public BaseResponse<Integer> runRecovery(
            @PathVariable String taskType,
            @RequestParam(defaultValue = "50") int batchSize
    ) {
        Long adminUserId = asyncTaskAdminGuard.checkAdmin();
        AsyncTaskType parsedType = asyncTaskRecoveryService.parseSupportedType(taskType);
        int enqueued = asyncTaskRecoveryService.runNow(parsedType, batchSize);
        log.info(
                "Async task recovery manually triggered: adminUserId={}, taskType={}, batchSize={}, enqueued={}",
                adminUserId,
                parsedType,
                batchSize,
                enqueued
        );
        return ResultUtil.success(enqueued, "补偿扫描执行完成");
    }
}
