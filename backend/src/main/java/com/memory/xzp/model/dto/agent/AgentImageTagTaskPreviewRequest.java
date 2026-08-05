package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2 AI 标签建议任务预览请求。
 */
@Data
public class AgentImageTagTaskPreviewRequest {

    private List<String> fileIds = new ArrayList<>();
    private AgentAdvancedSearchRequest filters;
    private Long albumId;
    private String tagName;
    private String locationLevel;
    private String locationValue;
    private String model;
    private String keyword;
    private String searchType;
    private String searchKeyword;
    private Integer selectionLimit;
    private Double minConfidence;

    @JsonIgnore
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
