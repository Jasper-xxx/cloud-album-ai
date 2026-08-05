package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 应用 AI 标签建议前的预览请求。
 */
@Data
public class AgentSuggestedTagsPreviewRequest {

    private String agentTaskId;
    private Boolean applyAll;
    private List<AgentSuggestedTagSelection> selections = new ArrayList<>();
    private List<String> selectedTagNames = new ArrayList<>();

    @JsonIgnore
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
