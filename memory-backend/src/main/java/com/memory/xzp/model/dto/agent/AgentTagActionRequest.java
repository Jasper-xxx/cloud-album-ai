package com.memory.xzp.model.dto.agent;

import lombok.Data;

import java.util.List;

/**
 * 智能体标签写操作请求。
 */
@Data
public class AgentTagActionRequest {
    /**
     * add_tags / remove_tags
     */
    private String action;
    private List<String> fileIds;
    private String imageType;
    private String tagName;
    private String searchType;
    private String searchKeyword;
    private String mediaType;
    private String sourceTagName;
    private String imageTypeText;
    private String locationLevel;
    private String locationValue;
    private Long sourceAlbumId;
    /**
     * 执行接口必须为 true，预览接口忽略该字段。
     */
    private Boolean confirmed;
}
