package com.memory.xzp.model.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务端校验并冻结的一条 AI 标签建议。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSuggestedTagPayload {

    private String fileId;
    private String imageType;
    private String tagName;
    private Double confidence;
}
