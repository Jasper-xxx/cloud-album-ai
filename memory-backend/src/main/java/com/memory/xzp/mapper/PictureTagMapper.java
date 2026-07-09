package com.memory.xzp.mapper;

import com.memory.xzp.model.entity.PictureTag;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.dto.picture.PictureTagMappingDTO;
import com.memory.xzp.model.vo.visual.FileTagVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-03-04
 */
public interface PictureTagMapper extends BaseMapper<PictureTag> {

    @Select("""
            SELECT pt.tag_name
            FROM picture_tag pt
            INNER JOIN user_file uf ON pt.file_id = uf.file_id
            WHERE uf.user_id = #{userId}
              AND uf.is_deleted = 0
              AND pt.file_id = #{fileId}
            """)
    List<String> selectTagsByFileId(@Param("fileId") String fileId, @Param("userId") Long userId);

    List<FileTagVO> selectAllTags(Long userId);

    @Select("""
            SELECT DISTINCT pt.tag_name
            FROM picture_tag pt
            INNER JOIN user_file uf ON pt.file_id = uf.file_id
            WHERE uf.user_id = #{userId}
              AND uf.is_deleted = 0
              AND pt.image_type = #{imageType}
            ORDER BY pt.tag_name
            """)
    List<String> selectTagNamesByImageType(@Param("userId") Long userId,
                                           @Param("imageType") String imageType);

    List<PictureTagMappingDTO> selectTagsByFileIds(@Param("fileIds") List<String> fileIds,
                                                   @Param("userId") Long userId);

    /**
     * 根据文件ID删除标签记录
     * @param fileId 文件ID
     */
    @Delete("DELETE FROM picture_tag WHERE file_id = #{fileId}")
    void deleteByFileId(@Param("fileId") String fileId);

    @Insert("""
            INSERT INTO picture_tag (file_id, image_type, tag_name)
            SELECT #{fileId}, #{imageType}, #{tagName}
            WHERE NOT EXISTS (
                SELECT 1
                FROM picture_tag
                WHERE file_id = #{fileId}
                  AND tag_name = #{tagName}
            )
            """)
    int insertIfAbsent(
            @Param("fileId") String fileId,
            @Param("imageType") String imageType,
            @Param("tagName") String tagName
    );

    @Delete({
            "<script>",
            "DELETE pt",
            "FROM picture_tag pt",
            "INNER JOIN user_file uf ON pt.file_id = uf.file_id",
            "WHERE uf.user_id = #{userId}",
            "  AND uf.is_deleted = 0",
            "  AND pt.tag_name = #{tagName}",
            "  AND pt.file_id IN",
            "  <foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "    #{fileId}",
            "  </foreach>",
            "</script>"
    })
    int deleteByFileIdsAndTag(
            @Param("fileIds") List<String> fileIds,
            @Param("tagName") String tagName,
            @Param("userId") Long userId
    );

}
