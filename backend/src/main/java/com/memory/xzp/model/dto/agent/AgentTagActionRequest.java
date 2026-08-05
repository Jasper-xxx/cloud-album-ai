package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体标签写操作请求。
 */
@Data
public class AgentTagActionRequest {
    /**
     * add_tags / remove_tags
     */
    private String action;
    private List<String> fileIds;
    private String imageType;
    private String tagName;
    private String searchType;
    private String searchKeyword;
    private Integer selectionLimit;
    private String sourceTagName;
    private String imageTypeText;
    private String locationLevel;
    private String locationValue;
    private Long sourceAlbumId;

    @JsonIgnore
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
