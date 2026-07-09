package com.memory.xzp.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.entity.VideoMetaData;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 视频元数据表 Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-02-20
 */
public interface VideoMetaDataMapper extends BaseMapper<VideoMetaData> {

    /**
     * 根据文件ID删除视频元数据记录
     * @param fileId 文件ID
     */
    @Delete("DELETE FROM video_meta_data WHERE file_id = #{fileId}")
    void deleteByFileId(@Param("fileId") String fileId);

}
