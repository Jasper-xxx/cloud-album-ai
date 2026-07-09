package com.memory.xzp.model.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @description:
 * @author: xzp
 * @date: 2025/3/13,22:17
 */
@Data
@NoArgsConstructor
public class DownLoadInfoDTO {
    private Long userId;
    private List<String> fileIds;
    private Long albumId;
}
