package com.memory.xzp.model.dto.album;

import lombok.Data;

import java.util.List;

@Data
public class AlbumBatchCreateRequest {
    private List<AlbumCreateItem> albums;

    @Data
    public static class AlbumCreateItem {
        private String name;
        private String description;
        private String type;
        private String tagName;
    }
}
