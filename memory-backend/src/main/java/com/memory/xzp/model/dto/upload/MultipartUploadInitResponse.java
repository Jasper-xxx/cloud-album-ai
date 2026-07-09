package com.memory.xzp.model.dto.upload;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
public class MultipartUploadInitResponse {

    private String sessionId;
    private String objectName;
    private String fileId;
    private boolean instantUpload;
    private long partSize;
    private int partCount;
    private List<PartUploadUrl> parts;
    private List<Integer> uploadedParts;
    private Long urlsExpireAt;
    private boolean completed;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartUploadUrl {

        private int partNumber;
        private String url;
        private Long expiresAt;

        public PartUploadUrl(int partNumber, String url) {
            this.partNumber = partNumber;
            this.url = url;
        }
    }
}
