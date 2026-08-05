package com.memory.xzp.model.vo.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体写操作预览。
 */
@Data
public class AgentActionPreviewVO {
    private String action;
    private String title;
    private String summary;
    private Boolean requiresConfirmation = true;
    private Integer affectedFileCount = 0;
    private Integer createdAlbumCount = 0;
    private Long albumId;
    private String albumName;
    private String tagName;
    private String agentTaskId;
    private Integer suggestedTagCount = 0;
    private Integer affectedAlbumCount = 0;
    private Integer affectedPersonCount = 0;
    private Integer shareDays;
    private String publicScope;
    private String riskLevel = "normal";
    private Boolean irreversible = false;

    @JsonIgnore
    private List<String> fileIds = new ArrayList<>();
    private List<AgentFileReferenceVO> affectedFiles = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private String confirmationPrompt;
    private String pendingActionId;
    private String confirmationToken;
    private String idempotencyKey;
    private Instant expiresAt;
}
