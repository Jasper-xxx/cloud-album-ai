package com.memory.xzp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 智能体写操作的服务端确认状态。
 */
@Data
@TableName("agent_pending_action")
public class AgentPendingActionEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String pendingActionId;
    private Long userId;
    private String family;
    private String action;
    private String payloadJson;
    private String payloadHash;
    private String confirmationTokenHash;
    private String idempotencyKeyHash;
    private String status;
    private String workflowVersion;
    private Integer previewAffectedFileCount;
    private Integer previewCreatedAlbumCount;
    private Integer actualAffectedFileCount;
    private Integer actualCreatedAlbumCount;
    private Integer skippedFileCount;
    private String summary;
    private String resultJson;
    private String lastError;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
