package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读图库整理建议。建议本身不会触发写操作。
 */
@Data
public class AgentLibrarySuggestionVO {

    private String code;
    private String priority;
    private Long affectedCount;
    private String title;
    private String description;
    private String recommendedAction;
    private Map<String, Object> suggestedFilters = new LinkedHashMap<>();
}
