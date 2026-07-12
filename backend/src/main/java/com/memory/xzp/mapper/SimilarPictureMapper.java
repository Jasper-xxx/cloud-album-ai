package com.memory.xzp.mapper;

import com.memory.xzp.model.entity.SimilarPicture;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-04-07
 */
public interface SimilarPictureMapper extends BaseMapper<SimilarPicture> {

    /**
     * 根据文件ID删除相似图片记录
     * @param fileId 文件ID
     */
    @Delete("DELETE FROM similar_picture WHERE file_id = #{fileId}")
    void deleteByFileId(@Param("fileId") String fileId);

}
