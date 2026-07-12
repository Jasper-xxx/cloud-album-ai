package com.memory.xzp.model.dto.imageSearch;

import lombok.Data;

import java.util.List;

@Data
public class ImageSearchRequestDTO {

    /**
     * 搜索模式：fuzzy / exact / local
     */
    private String mode;

    /**
     * 相册筛选
     */
    private List<Long> albumIds;

    /**
     * 标签筛选
     */
    private List<String> tagNames;

    /**
     * 尺寸筛选：small / medium / large
     */
    private String sizeRange;
}
