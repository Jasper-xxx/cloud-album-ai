package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户从 AI 结果中选择的一条文件标签建议。
 */
@Data
public class AgentSuggestedTagSelection {

    private String fileId;
    private String tagName;

    @JsonIgnore
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
