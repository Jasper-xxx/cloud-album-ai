package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * P1 图库健康检查结果。
 */
@Data
public class AgentLibraryAnalysisVO {

    private Instant generatedAt;
    private Integer staleMinutes;
    private Integer healthScore;
    private String healthLevel;
    private Long totalFileCount;
    private Long untaggedFileCount;
    private Long missingLocationFileCount;
    private Long missingFeatureFileCount;
    private Long missingAiAnalysisFileCount;
    private Long similarGroupCount;
    private Long similarFileCount;
    private Long failedAiTaskCount;
    private Long staleAiTaskCount;
    private Long pendingAiTaskCount;
    private Long unalbumedFileCount;
    private List<AgentLibrarySuggestionVO> suggestions = new ArrayList<>();
}
