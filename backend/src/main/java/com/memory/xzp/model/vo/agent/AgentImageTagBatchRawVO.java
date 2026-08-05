package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * P2 标签任务批次基础记录。
 */
@Data
public class AgentImageTagBatchRawVO {

    private String batchId;
    private Long userId;
    private String pendingActionId;
    private Double minConfidence;
    private Integer totalCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
