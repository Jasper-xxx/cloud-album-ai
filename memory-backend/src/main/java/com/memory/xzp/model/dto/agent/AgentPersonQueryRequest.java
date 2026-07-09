package com.memory.xzp.model.dto.agent;

import lombok.Data;

/**
 * 智能体人物相册查询请求。
 */
@Data
public class AgentPersonQueryRequest {
    private Integer current;
    private Integer size;
    private Boolean display;
}
