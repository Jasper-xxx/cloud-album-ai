package com.memory.xzp.model.vo.agent;

import lombok.Data;

/**
 * 聊天附件进入 Cloud-Album 后的可信文件标识。
 */
@Data
public class AgentAttachmentUploadVO {
    private String fileId;
    private String originalFilename;
    private String category;
    private Long size;
    private Long albumId;
    private Boolean uploaded;
}
