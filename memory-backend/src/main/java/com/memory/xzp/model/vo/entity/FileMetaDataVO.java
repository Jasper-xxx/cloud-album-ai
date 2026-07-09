package com.memory.xzp.model.vo.entity;

import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.ImageMetaData;
import com.memory.xzp.model.entity.VideoMetaData;
import lombok.Data;

/**
 * @description:
 * @author: xzp
 * @date: 2025/2/28,20:33
 */
@Data
public class FileMetaDataVO {
    //文件基本信息
    FileEntity fileInfo;
    //图片媒体信息
    ImageMetaData imageMetaData;
    //视频媒体信息
    VideoMetaData videoMetaData;
}
