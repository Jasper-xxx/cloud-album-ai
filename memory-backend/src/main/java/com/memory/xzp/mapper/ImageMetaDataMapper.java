package com.memory.xzp.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.entity.ImageMetaData;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 图像元数据表 Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-02-20
 */
public interface ImageMetaDataMapper extends BaseMapper<ImageMetaData> {

    /**
     * 根据文件ID删除图片元数据记录
     * @param fileId 文件ID
     */
    @Delete("DELETE FROM image_meta_data WHERE file_id = #{fileId}")
    void deleteByFileId(@Param("fileId") String fileId);

}
