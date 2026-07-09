package com.memory.xzp.model.vo;

import com.memory.xzp.model.vo.entity.FileInfo;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * @description:
 * @author: xzp
 * @date: 2025-04-07,01:51
 */
@Data
public class SimilarFileInfoListVO {
    private String similarId;
    private List<FileInfo> fileList;
}
