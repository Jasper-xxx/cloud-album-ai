package com.memory.xzp.model.vo.task;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AsyncTaskVO {

    private Long id;

    private Long userId;

    private String taskType;

    private String fileId;

    private String status;

    private JsonNode result;

    private Integer retryCount;

    private Integer maxRetries;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
