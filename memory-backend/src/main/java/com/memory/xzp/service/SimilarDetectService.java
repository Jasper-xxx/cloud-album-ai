package com.memory.xzp.service;

import com.memory.xzp.model.vo.SimilarFileInfoListVO;

import java.util.List;

/**
 * 相似图片检测服务接口
 *
 * <p>基于阿里云多模态向量，通过余弦相似度实时检测用户相册中的相似图片。</p>
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>相似结果为实时计算，<b>不写入数据库</b>，仅临时返回前端展示</li>
 *   <li>仅使用当前用户已入库的 file_feature / user_file / file 记录，严格数据隔离</li>
 *   <li>删除操作复用现有 /file/deleteFileByIds 接口，本服务不新增删除逻辑</li>
 * </ul>
 *
 * @author xzp
 * @date 2026/03/20
 */
public interface SimilarDetectService {

    /**
     * 实时检测用户相册中的相似图片
     *
     * <p>算法流程：</p>
     * <ol>
     *   <li>通过 user_id 查询当前用户未删除图片的 file_feature 记录</li>
     *   <li>读取每张图片的 feature_vector（byte[] → float[]）</li>
     *   <li>关联 file 表，获取图片展示信息（URL、文件名等）</li>
     *   <li>批量计算两两余弦相似度，使用并查集（Union-Find）将相似度 ≥ threshold 的图片分组</li>
     *   <li>过滤出组内图片数 ≥ 2 的组，按 maxGroups 截断后返回</li>
     * </ol>
     *
     * @param threshold 相似度阈值，范围 [0, 1]，不低于 0
     *                  例：0.80 表示只返回相似度 ≥ 80% 的图片组
     * @param maxGroups 最多返回的相似组数，不小于 1
     *                  例：30 表示最多返回 30 个相似分组
     * @param userId    登录用户 ID（由 Sa-Token StpUtil.getLoginId() 获取）
     * @return 相似图片分组列表，每组包含 ≥ 2 张图片；若无相似图片则返回空列表
     */
    List<SimilarFileInfoListVO> detectSimilarImages(Double threshold, Integer maxGroups, Long userId);
}
