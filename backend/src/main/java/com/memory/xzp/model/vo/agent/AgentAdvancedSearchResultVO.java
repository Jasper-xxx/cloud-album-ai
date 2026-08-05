package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * P1 组合检索结果。
 */
@Data
public class AgentAdvancedSearchResultVO {

    private Long current;
    private Long size;
    private Long total;
    private Long pages;
    private Boolean hasNext;
    private String conditionSummary;
    private List<AgentFileSearchItemVO> records = new ArrayList<>();
    private List<AgentSearchRelaxSuggestionVO> relaxSuggestions = new ArrayList<>();
}
