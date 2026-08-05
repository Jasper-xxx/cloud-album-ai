package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * P2 AI 标签建议批次状态。
 */
@Data
public class AgentImageTagTaskStatusVO {

    private String agentTaskId;
    private String status;
    private Integer totalCount;
    private Integer pendingCount;
    private Integer runningCount;
    private Integer succeededCount;
    private Integer failedCount;
    private Integer deadCount;
    private Integer progressPercent;
    private Double minConfidence;
    private List<AgentFileReferenceVO> files = new ArrayList<>();
    private Integer suggestionCount;
    private List<AgentTagSuggestionVO> suggestions = new ArrayList<>();
    private List<String> failureSummaries = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
