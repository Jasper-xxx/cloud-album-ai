package com.memory.xzp.model.dto.upload;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DirectUploadRegistration {

    private Long userId;
    private Long albumId;
    private String fileName;
    private String objectName;
    private String contentType;
    private String md5;
    private Long fileSize;
    private Long lastModified;
}
