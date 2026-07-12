package com.memory.xzp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;


/**
 * 人脸处理记录。
 *
 * <p>人脸检测与特征提取由阿里云视觉模型完成，向量维度按模型实际返回值写入。</p>
 *
 * @author xzp
 * @since 2025-03-07
 */
@Data
@TableName("face")
public class Face implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // 人脸记录主键
    @TableId(value = "face_id", type = IdType.AUTO)
    private Long faceId;


    // 文件 ID
    @TableField("file_id")
    private String fileId;
    // 用户 ID
    @TableField("user_id")
    private Long userId;

    // 是否已处理
    @TableField("is_processed")
    private Boolean isProcessed;

    // 阿里云模型返回的人脸特征向量，按 float32 小端序存储为字节数组
    @TableField("feature_vector")
    private byte[] featureVector;

    // 人脸特征向量实际维度
    @TableField("feature_dim")
    private Integer featureDim;

    // 人脸特征向量模型名称
    @TableField("feature_model")
    private String featureModel;

    // 人脸特征向量服务提供方
    @TableField("feature_provider")
    private String featureProvider;

    // 人脸检测服务提供方或检测方案标识
    @TableField("detect_provider")
    private String detectProvider;

    // 人脸框 JSON，使用像素坐标
    @TableField("bbox_json")
    private String bboxJson;

    // 人脸质量评分
    @TableField("quality_score")
    private Double qualityScore;

    // 人脸封面 URL
    @TableField("person_cover_url")
    private String personCoverUrl;

    // 人脸封面对象名
    @TableField("person_object_name")
    private String personObjectName;

    // 创建时间
    @TableField("create_time")
    private LocalDateTime createTime;

    // 是否检测到有效人脸
    @TableField("is_face")
    private Boolean isFace;
}
