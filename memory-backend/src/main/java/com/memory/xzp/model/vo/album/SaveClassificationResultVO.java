package com.memory.xzp.model.vo.album;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SaveClassificationResultVO {
    private List<AlbumSummary> createdAlbums = new ArrayList<>();
    private List<AlbumSummary> updatedAlbums = new ArrayList<>();
    private List<AlbumFileRelation> addedFiles = new ArrayList<>();
    private List<AlbumFileRelation> removedFiles = new ArrayList<>();
    private Statistics statistics = new Statistics();

    @Data
    public static class AlbumSummary {
        private Long id;
        private String name;
    }

    @Data
    public static class AlbumFileRelation {
        private Long albumId;
        private String fileId;
    }

    @Data
    public static class Statistics {
        private Integer totalFiles = 0;
        private Integer classifiedFiles = 0;
        private Integer unclassifiedFiles = 0;
    }
}
