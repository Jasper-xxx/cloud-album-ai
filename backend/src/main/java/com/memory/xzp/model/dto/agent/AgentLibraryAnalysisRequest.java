package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1 图库健康检查参数。
 */
@Data
public class AgentLibraryAnalysisRequest {

    /**
     * PENDING/RUNNING 超过该分钟数视为长期未完成，默认 60。
     */
    private Integer staleMinutes;

    @JsonIgnore
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
