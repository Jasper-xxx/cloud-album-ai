package com.memory.xzp.model.dto;

import lombok.Data;

/**
 * 图片特征查询 DTO。
 *
 * <p>用于 file_feature / user_file / file 联表查询。featureVector 仅用于后端相似度计算，不序列化到前端响应。</p>
 *
 * @author xzp
 * @date 2026/03/20
 */
@Data
public class FileFeatureQueryDTO {

    /** 特征记录主键 ID。 */
    private Long id;

    /** 文件 ID。 */
    private String fileId;

    /**
     * 阿里云 embedding 模型返回的图片特征向量，按 float32 小端序存储为字节数组。
     */
    private byte[] featureVector;

    /** 特征向量实际维度。 */
    private Integer featureDim;

    /** 特征向量模型名称。 */
    private String featureModel;

    /** 特征向量服务提供方。 */
    private String featureProvider;

    /** 特征向量版本标识。 */
    private String featureVersion;

    /** 原始文件名。 */
    private String originFileName;

    /** 文件大小，单位字节。 */
    private Long size;

    /** MIME 类型，如 image/jpeg。 */
    private String contentType;

    /** 文件分类，如 image / video。 */
    private String category;

    /** 原图访问 URL。 */
    private String fileUrl;

    /** 缩略图访问 URL。 */
    private String thumbnailUrl;

    /** 缩略图对象名。 */
    private String thumbnailObjectName;

    /** 图片宽度，单位像素。 */
    private Integer width;

    /** 图片高度，单位像素。 */
    private Integer height;
}


