package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.time.Instant;

/**
 * 智能体待执行操作状态。
 */
@Data
public class AgentPendingActionStatusVO {

    private String pendingActionId;
    private String status;
    private String family;
    private String action;
    private String summary;
    private Integer affectedFileCount;
    private Integer createdAlbumCount;
    private Integer previewAffectedFileCount;
    private Integer actualAffectedFileCount;
    private Integer skippedFileCount;
    private Integer previewCreatedAlbumCount;
    private Integer actualCreatedAlbumCount;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant confirmedAt;
    private Instant executedAt;
    private Instant completedAt;
    private String failureMessage;
}
