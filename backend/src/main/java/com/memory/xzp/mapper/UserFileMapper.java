package com.memory.xzp.mapper;

import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.UserFileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * 用户-文件关联表 Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-02-27
 */
public interface UserFileMapper extends BaseMapper<UserFileEntity> {
    void updateUseFile(Long userId, List<String> fileIds, boolean isDeleted);

    void dropFile(Long userId,@Param("fileIds") List<String> fileIds);
    @Select("SELECT f.* FROM file f WHERE NOT EXISTS (SELECT 1 FROM user_file uf WHERE uf.file_id=f.file_id) AND f.status IN ('READY','FAILED','DELETING') AND f.status_update_time < DATE_SUB(NOW(), INTERVAL 2 HOUR) LIMIT 200 FOR UPDATE")
    List<FileEntity> selectExpiredPicture();

    @Select("SELECT * FROM user_file WHERE is_deleted = 1 AND deleted_time <= #{thirtyDaysAgo}")
    List<UserFileEntity> selectSoftDeletedFiles(@Param("thirtyDaysAgo") java.time.LocalDateTime thirtyDaysAgo);

    @Select("SELECT COUNT(*) FROM user_file WHERE file_id = #{fileId}")
    long countByFileId(@Param("fileId") String fileId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM user_file WHERE id=#{id} AND user_id=#{userId} AND is_deleted=1 AND deleted_time=#{deletedTime} AND deleted_time <= #{cutoff}")
    int deleteDeletedRelation(@Param("id") Long id, @Param("userId") Long userId,
                              @Param("deletedTime") java.time.LocalDateTime deletedTime,
                              @Param("cutoff") java.time.LocalDateTime cutoff);

    @org.apache.ibatis.annotations.Delete("DELETE FROM album_picture WHERE user_id=#{userId} AND file_id=#{fileId}")
    int deleteRemovedAlbumMembership(@Param("userId") Long userId, @Param("fileId") String fileId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM similar_picture WHERE user_id=#{userId} AND file_id=#{fileId}")
    int deleteRemovedSimilarMembership(@Param("userId") Long userId, @Param("fileId") String fileId);

}
