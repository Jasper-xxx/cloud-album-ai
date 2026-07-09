package com.memory.xzp.model.dto.album;

import lombok.Data;

import java.util.List;

@Data
public class AlbumBatchAddFilesRequest {
    private List<AlbumFilesItem> albumFiles;

    @Data
    public static class AlbumFilesItem {
        private Long albumId;
        private List<String> fileIds;
    }
}
