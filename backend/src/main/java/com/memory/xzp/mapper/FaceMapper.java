package com.memory.xzp.mapper;

import com.memory.xzp.model.dto.FaceFileDTO;
import com.memory.xzp.model.entity.Face;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 人像特征表 Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-03-07
 */
public interface FaceMapper extends BaseMapper<Face> {

    /**
     * 根据文件ID列表查询人像ID（原有方法，保留不动）
     */
    List<Long> selectFaceIdByFileIds(
            @Param("userId") Long userId,
            @Param("personId") Long personId,
            @Param("fileIds") List<String> fileIds
    );

    /**
     * 查询用户所有未删除图片的特征向量及文件展示信息
     *
     * <p>三表联查：face（特征向量）+ user_file（过滤已删除）+ file（展示字段）</p>
     * <p>仅返回 feature_vector 不为空的记录，用于相似图片实时检测</p>
     *
     * @param userId 登录用户 ID（来自 Sa-Token）
     * @return FaceFileDTO 列表（含特征向量 + 文件展示信息）
     */
    List<FaceFileDTO> selectFaceFileByUserId(@Param("userId") Long userId);

    /**
     * 查询尚未创建当前版本可靠任务的待处理人脸记录。
     */
    List<Face> selectPendingWithoutTask(
            @Param("taskType") String taskType,
            @Param("taskVersion") String taskVersion,
            @Param("limit") int limit
    );

    /**
     * 根据文件ID删除人脸记录
     * @param fileId 文件ID
     */
    @Delete("DELETE FROM face WHERE file_id = #{fileId}")
    void deleteByFileId(@Param("fileId") String fileId);
}
