package com.memory.xzp.model.dto.upload;

import lombok.Data;

import java.util.List;

@Data
public class MultipartUploadRefreshRequest {

    private String sessionId;
    private List<Integer> partNumbers;
}
