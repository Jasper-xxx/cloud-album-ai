package com.memory.xzp.model.dto.agent;

import lombok.Data;

import java.util.List;

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
    private String mediaType;
    private String tagName;
    private String imageTypeText;
    private String locationLevel;
    private String locationValue;
    private Long sourceAlbumId;
    /**
     * 执行接口必须为 true，预览接口忽略该字段。
     */
    private Boolean confirmed;
}
