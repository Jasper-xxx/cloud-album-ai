package com.memory.xzp.model.dto.album;

import lombok.Data;

import java.util.List;

@Data
public class AlbumBatchUpdateRequest {
    private List<AlbumUpdateItem> albums;

    @Data
    public static class AlbumUpdateItem {
        private Long id;
        private String name;
    }
}
