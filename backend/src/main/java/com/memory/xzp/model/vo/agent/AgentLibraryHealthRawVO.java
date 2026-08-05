package com.memory.xzp.model.vo.agent;

import lombok.Data;

/**
 * 图库健康统计的数据库聚合结果。
 */
@Data
public class AgentLibraryHealthRawVO {

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
}
