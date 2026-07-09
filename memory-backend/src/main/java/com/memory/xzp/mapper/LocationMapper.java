package com.memory.xzp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.entity.Location;
import com.memory.xzp.model.vo.visual.VisualLocationVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface LocationMapper extends BaseMapper<Location> {


    List<VisualLocationVO> selectAllLocation(@Param("userId") Long userId);

    /**
     * 根据文件ID删除地理位置记录
     * @param fileId 文件ID
     */
    @Delete("DELETE FROM location WHERE file_id = #{fileId}")
    void deleteByFileId(@Param("fileId") String fileId);
}
