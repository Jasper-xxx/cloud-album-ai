package com.memory.xzp.model.dto.task;

public record AsyncTaskDispatchMessage(
        Long taskId,
        String taskType,
        Long userId,
        String fileId,
        String requestId,
        String traceId
) {
}
