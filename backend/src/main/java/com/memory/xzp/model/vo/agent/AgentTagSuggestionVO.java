package com.memory.xzp.model.vo.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 经过置信度阈值筛选的 AI 标签建议。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentTagSuggestionVO {

    private String fileId;
    private String imageType;
    private String tagName;
    private Double confidence;
}
