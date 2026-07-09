package com.memory.xzp.model.dto.agent;

import lombok.Data;

/**
 * 智能体相册查询请求。
 */
@Data
public class AgentAlbumQueryRequest {
    private Integer current;
    private Integer size;
    private String orderKeyword;
    private String orderType;
    private String locationLevel;
}
