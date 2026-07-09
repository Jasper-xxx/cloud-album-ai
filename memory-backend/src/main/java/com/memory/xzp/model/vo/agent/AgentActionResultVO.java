package com.memory.xzp.model.vo.agent;

import lombok.Data;

/**
 * 智能体写操作执行结果。
 */
@Data
public class AgentActionResultVO {
    private String action;
    private Boolean success;
    private String message;
    private Integer affectedFileCount = 0;
    private Long albumId;
    private String albumName;
    private String tagName;
}
