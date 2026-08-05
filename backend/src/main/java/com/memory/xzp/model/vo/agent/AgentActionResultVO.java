package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体写操作执行结果。
 */
@Data
public class AgentActionResultVO {
    private String action;
    private Boolean success;
    private String message;
    private Integer affectedFileCount = 0;
    private List<AgentFileReferenceVO> affectedFiles = new ArrayList<>();
    private Integer skippedFileCount = 0;
    private List<String> skippedReasons = new ArrayList<>();
    private Integer createdAlbumCount = 0;
    private Long albumId;
    private String albumName;
    private String tagName;
    private String agentTaskId;
    private Integer affectedTagCount = 0;
    private Integer skippedTagCount = 0;
    private Integer affectedAlbumCount = 0;
    private Integer affectedPersonCount = 0;
    private List<Long> taskIds = new ArrayList<>();
    private String resourceToken;
    private String tokenType;
    private Integer expiresInDays;
    private String pendingActionId;
    private Boolean idempotentReplay = false;
}
