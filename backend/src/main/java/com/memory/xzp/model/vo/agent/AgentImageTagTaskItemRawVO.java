package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * P2 批次中的底层 IMAGE_TAG 任务。
 */
@Data
public class AgentImageTagTaskItemRawVO {

    private String fileId;
    private Long asyncTaskId;
    private String status;
    private String resultJson;
    private String lastError;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
