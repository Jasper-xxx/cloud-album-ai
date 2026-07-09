package com.memory.xzp.model.dto.agent;

import lombok.Data;

/**
 * 智能体照片检索请求。
 */
@Data
public class AgentSearchFilesRequest {
    private Integer current;
    private Integer size;
    private String orderType;
    private String orderKeyword;
    private String imageTypeText;
    private String locationLevel;
    private String locationValue;
    private Long albumId;
    private String tagFilter;
    private String tagName;
    private String searchType;
    private String searchKeyword;
}
