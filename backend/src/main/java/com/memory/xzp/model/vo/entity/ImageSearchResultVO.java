package com.memory.xzp.model.vo.entity;

import lombok.Data;

/**
 * 以图搜图结果 VO
 *
 * <p>封装相似图片的文件信息和相似度，用于返回给前端展示</p>
 * <p>前端根据 similarity（相似度）展示瀑布流，相似度由高到低排列</p>
 *
 * @author xzp
 * @date 2026/03/20
 */
@Data
public class ImageSearchResultVO {

    /**
     * 文件ID（UUID，对应 file.file_id）
     */
    private String fileId;

    /**
     * 原始文件名（如 "holiday.jpg"）
     */
    private String originFileName;

    /**
     * 原图访问 URL（MinIO 预签名 URL，用于点击预览原图）
     */
    private String fileUrl;

    /**
     * 缩略图 URL（MinIO 预签名 URL，用于瀑布流展示缩略图）
     */
    private String thumbnailUrl;

    /**
     * 图片宽度（像素）
     */
    private Integer width;

    /**
     * 图片高度（像素）
     */
    private Integer height;

    /**
     * 余弦相似度，范围 [0, 1]，值越大越相似（已过滤 < 0.7 的结果）
     */
    private Double similarity;

    /**
     * 相似度百分比（0~100 整数），前端展示用，如 85 表示 85% 相似
     */
    private Integer similarityPercent;
}
