package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体相册写操作请求。
 */
@Data
public class AgentAlbumActionRequest {
    /**
     * create_album / add_files_to_album / remove_files_from_album / create_album_and_add_files
     */
    private String action;
    private Long albumId;
    private String albumName;
    private List<String> fileIds;
    private String searchType;
    private String searchKeyword;
    private Integer selectionLimit;
    private String tagName;
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
