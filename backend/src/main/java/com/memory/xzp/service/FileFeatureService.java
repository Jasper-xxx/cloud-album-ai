package com.memory.xzp.service;

import com.memory.xzp.model.vo.entity.ImageSearchResultVO;
import com.memory.xzp.model.dto.imageSearch.ImageSearchRequestDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 图片特征向量 Service 接口
 *
 * <p>提供以图搜图核心能力：</p>
 * <ol>
 *   <li>图片上传后异步提取并保存特征向量</li>
 *   <li>接收查询图片，提取特征并计算余弦相似度，返回相似图片列表</li>
 * </ol>
 *
 * @author xzp
 * @date 2026/03/20
 */
public interface FileFeatureService {

    /**
     * 提取指定文件的特征向量并保存到 file_feature 表
     *
     * <p>由持久化异步任务消费者调用，任务记录与上传业务数据在同一事务中创建</p>
     * <p>底层调用 FastAPI /extract_feature 接口，以 MinIO 对象路径传递图片</p>
     * <p>使用 {@code Propagation.REQUIRES_NEW} 独立事务，不影响上传主流程</p>
     *
     * @param fileId         文件ID（UUID，关联 file.file_id）
     * @param userId         用户ID（关联 user.id）
     * @param fileObjectName MinIO 对象路径（如 "file/2026-03-20/xxx.jpg"）
     */
    void extractAndSaveFeature(String fileId, Long userId, String fileObjectName);

    /**
     * 以图搜图：接收查询图片，提取特征，计算余弦相似度，返回相似图片列表
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>将查询图片发送到 FastAPI /extract_feature，获取模型默认维度的特征向量</li>
     *   <li>按当前用户、特征模型和显式筛选条件从 file_feature 表查询候选图片特征</li>
     *   <li>遍历计算余弦相似度，模糊匹配返回 ≥ 0.60 的全部结果，精确匹配返回 ≥ 0.85 的全部结果</li>
     *   <li>局部匹配会先召回全局相似候选，再基于缩略图局部哈希计算局部相似度，最多返回 20 条</li>
     * </ol>
     *
     * @param queryImage 查询图片（前端上传的 MultipartFile）
     * @param userId     当前登录用户ID（保证数据隔离，只搜该用户的图片）
     * @return 相似图片列表（按匹配模式和相似度降序返回）
     */
    List<ImageSearchResultVO> searchSimilarImages(MultipartFile queryImage, Long userId, ImageSearchRequestDTO request);
}
