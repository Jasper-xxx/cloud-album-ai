package com.memory.xzp.model.dto.album;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SaveClassificationRequest {
    private String fileId;
    private List<String> fileIds = new ArrayList<>();
    private List<ClassificationGroup> groups;
    private Boolean unclassified;
    private List<String> unclassifiedFileIds = new ArrayList<>();

    @Data
    public static class ClassificationGroup {
        private Long albumId;
        private String albumName;
        private Boolean isNew;
        private String tagName;
        private Boolean checked;
        private List<String> fileIds = new ArrayList<>();
    }
}
