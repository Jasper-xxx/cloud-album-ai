package com.memory.xzp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.dto.FileFeatureQueryDTO;
import com.memory.xzp.model.entity.FileFeature;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 图片特征向量 Mapper 接口
 *
 * <p>操作数据库表：file_feature</p>
 * <p>继承 MyBatis-Plus BaseMapper，提供基础 CRUD 能力</p>
 * <p>复杂联表查询（关联 file 表获取文件信息）通过 XML 实现</p>
 *
 * @author xzp
 * @date 2026/03/20
 */
public interface FileFeatureMapper extends BaseMapper<FileFeature> {

    /**
     * 查询指定用户的全部图片特征（联表 file 获取文件展示信息）
     *
     * <p>用于以图搜图的相似度计算：先拉取用户所有特征，再在内存中计算余弦相似度</p>
     * <p>SQL 定义在 FileFeatureMapper.xml 中</p>
     *
     * @param userId 用户ID（关联 user.id）
     * @return 包含 featureVector 和文件信息的 DTO 列表
     */
    List<FileFeatureQueryDTO> selectFeatureListByUserId(@Param("userId") Long userId,
                                                        @Param("featureProvider") String featureProvider,
                                                        @Param("featureModel") String featureModel,
                                                        @Param("featureDim") Integer featureDim,
                                                        @Param("expectedBytes") Integer expectedBytes);

    List<FileFeatureQueryDTO> selectFeatureListByCondition(@Param("userId") Long userId,
                                                           @Param("albumIds") List<Long> albumIds,
                                                           @Param("tagNames") List<String> tagNames,
                                                           @Param("sizeMinPixels") Long sizeMinPixels,
                                                           @Param("sizeMaxPixels") Long sizeMaxPixels,
                                                           @Param("featureProvider") String featureProvider,
                                                           @Param("featureModel") String featureModel,
                                                           @Param("featureDim") Integer featureDim,
                                                           @Param("expectedBytes") Integer expectedBytes);

    /**
     * 根据 fileId 和 userId 查询特征记录主键 ID（用于幂等性检查）
     *
     * <p>上传时先检查该文件是否已提取过特征，避免重复插入</p>
     *
     * @param fileId 文件ID（UUID）
     * @param userId 用户ID
     * @return 特征记录主键 ID；不存在则返回 null
     */
    @Select("SELECT id FROM file_feature WHERE file_id = #{fileId} AND user_id = #{userId} " +
            "AND feature_provider = #{featureProvider} AND feature_model = #{featureModel} LIMIT 1")
    Long selectIdByFileIdAndUserId(@Param("fileId") String fileId,
                                   @Param("userId") Long userId,
                                   @Param("featureProvider") String featureProvider,
                                   @Param("featureModel") String featureModel);

    /**
     * 根据文件ID删除特征向量记录
     * @param fileId 文件ID
     */
    @Delete("DELETE FROM file_feature WHERE file_id = #{fileId}")
    void deleteByFileId(@Param("fileId") String fileId);
}
