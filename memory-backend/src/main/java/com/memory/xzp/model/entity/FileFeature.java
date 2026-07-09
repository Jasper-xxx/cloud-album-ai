package com.memory.xzp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 图片特征向量存储表。
 *
 * <p>对应数据库表：file_feature。特征由阿里云 embedding 模型生成，向量维度按模型实际返回值写入。</p>
 * <ul>
 *   <li>特征向量以 float32 小端序字节流存入 LONGBLOB 字段</li>
 *   <li>通过 {@link com.memory.xzp.utils.CosineSimilarityUtil} 进行 byte[] / float[] 转换</li>
 *   <li>file_id + user_id + feature_provider + feature_model 标识一份可检索特征</li>
 * </ul>
 *
 * @author xzp
 * @date 2026/03/20
 * @see com.memory.xzp.utils.CosineSimilarityUtil
 */
@Data
@TableName("file_feature")
public class FileFeature implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID，自增。
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联文件 ID。
     */
    private String fileId;

    /**
     * 关联用户 ID。
     */
    private Long userId;

    /**
     * 阿里云 embedding 模型返回的图片特征向量，按 float32 小端序存储为字节数组。
     */
    private byte[] featureVector;

    /**
     * 特征向量实际维度。
     */
    private Integer featureDim;

    /**
     * 特征向量模型名称。
     */
    private String featureModel;

    /**
     * 特征向量服务提供方。
     */
    private String featureProvider;

    /**
     * 特征向量版本标识。
     */
    private String featureVersion;

    /**
     * 特征提取时间。
     */
    private LocalDateTime createTime;
}


