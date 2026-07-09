package com.memory.xzp.model.dto.upload;

import lombok.Data;

@Data
public class MultipartUploadInitRequest {

    private String fileName;
    private Long fileSize;
    private String contentType;
    private Long lastModified;
    private Long albumId;
    private String md5;
    private String sha256;
}
