package com.memory.xzp.model.vo.agent;

import lombok.Data;

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
    private Long albumId;
    private String albumName;
    private String tagName;
    private List<String> fileIds = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private String confirmationPrompt;
}
