package com.memory.xzp.model.dto;

import lombok.Data;

/**
 * 相似图片检测三表联查数据传输对象。
 *
 * <p>保留给旧相似检测查询使用。featureVector 为模型实际维度的 float32 字节流，仅用于后端余弦相似度计算，不返回给前端。</p>
 *
 * @author xzp
 * @date 2026/03/20
 */
@Data
public class FaceFileDTO {

    /** 文件唯一ID（UUID，36位） */
    private String fileId;

    /** 原始文件名（带扩展名） */
    private String originFileName;

    /** 文件大小（单位：字节） */
    private Long size;

    /** MIME 类型（如 image/jpeg） */
    private String contentType;

    /** 文件分类（image / video / gif） */
    private String category;

    /** 图片原始宽度（像素） */
    private Integer width;

    /** 图片原始高度（像素） */
    private Integer height;

    /** 文件访问 URL（MinIO 对象存储地址） */
    private String fileUrl;

    /** 缩略图 URL */
    private String thumbnailUrl;

    /** 缩略图对象名（用于下载鉴权） */
    private String thumbnailObjectName;

    /**
     * face 表中的特征向量，按 float32 小端序存储为字节数组。
     */
    private byte[] featureVector;
}
