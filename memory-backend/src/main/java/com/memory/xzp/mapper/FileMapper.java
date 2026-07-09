package com.memory.xzp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.dto.task.PendingFileTask;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.model.vo.entity.FileInfo;
import com.memory.xzp.model.vo.entity.FileMetaDataVO;
import com.memory.xzp.model.vo.visual.FileContentType;
import com.memory.xzp.model.vo.visual.FileSize;
import com.memory.xzp.model.vo.visual.VisualFileVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 文件存储表 Mapper 接口
 * </p>
 *
 * @author xzp
 * @since 2025-02-20
 */
public interface FileMapper extends BaseMapper<FileEntity> {


    /**
     * 用户是否拥有该文件
     */
    @Select("select file_id from user_file where file_id = #{fileId} and user_id = #{userId}")
    String isUserHasFile(String fileId, Long userId);

    @Select("select file_id from file where md5 = #{md5}")
    String isFileExist(String md5);

    @Select("""
            SELECT f.file_id
            FROM file f
            INNER JOIN user_file uf ON uf.file_id = f.file_id
            WHERE uf.user_id = #{userId}
              AND f.md5 = #{md5}
            ORDER BY uf.is_deleted ASC, uf.upload_time DESC
            LIMIT 1
            """)
    String selectOwnedFileIdByMd5(@Param("userId") Long userId, @Param("md5") String md5);


    /**
     * 查询文件信息
     */
    List<FileInfoListVO> getFileInfoList(
            Page<FileInfoListVO> page,
            @Param("orderType") String orderType,
            @Param("orderKeyword") String orderKeyword,
            @Param("imageTypeText") String imageTypeText,
            @Param("locationLevel") String locationLevel,
            @Param("locationValue") String locationValue,
            @Param("tagFilter") String tagFilter,
            @Param("userId") Long userId,
            @Param("albumId") Long albumId,
            @Param("isDeleted") Boolean isDeleted
    );

    List<FileInfoListVO> getTagFileInfo(
            Page<FileInfoListVO> page,
            @Param("userId") Long userId,
            @Param("orderType") String orderType,
            @Param("orderKeyword") String orderKeyword,
            @Param("imageTypeText") String imageTypeText,
            @Param("tagName") String tagName,
            @Param("isDeleted") Boolean isDeleted
    );



    @Select("SELECT DISTINCT f.model FROM file f JOIN user_file uf ON f.file_id = uf.file_id WHERE uf.user_id = #{userId}  AND f.model != ' ' ")
    List<String> selectAllModels(  @Param("userId") Long userId);



    List<FileInfoListVO> selectFileInfoListByIds(
            @Param("fileIds") List<String> fileIds,
            @Param("userId") Long userId
    );

    @Select({
            "<script>",
            "SELECT f.file_id",
            "FROM file f",
            "INNER JOIN user_file uf ON f.file_id = uf.file_id",
            "WHERE uf.user_id = #{userId}",
            "  AND uf.is_deleted = 0",
            "  AND f.file_id IN",
            "  <foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "    #{fileId}",
            "  </foreach>",
            "</script>"
    })
    List<String> selectOwnedActiveFileIds(
            @Param("fileIds") List<String> fileIds,
            @Param("userId") Long userId
    );

    @Select("select count(*) from user_file where user_id=#{userId} ")
    int selectFileToTalById(@Param("userId") Long userId);


    /**
     * 逻辑删除，删除相册内图片,删除关联表
     *
     * @param albumIds
     */
    void setIsDeletedByAlbumIds(List<Long> albumIds, Long userId);

    /**
     * 逻辑删除，删除相册内图片,删除关联表
     *
     * @param fileIds
     * @param isDeleted(1删除,0恢复)
     */
    int setIsDeletedByFileIds(List<String> fileIds, boolean isDeleted, Long userId);

    @Select("select file.file_id from file join album_picture ap on file.file_id = ap.file_id join user_file uf on file.file_id = uf.file_id where album_id=#{albumId} and uf.user_id = #{userId} and uf.is_deleted = 0")
    List<String> selectFileIdByAlbumId(Long albumId, Long userId);

    List<FileEntity> selectFileByIds(@Param("fileIds") List<String> fileIds, @Param("userId") Long userId);

    @Select("""
            SELECT f.*
            FROM file f
            WHERE f.file_id = #{fileId}
              AND EXISTS (
                  SELECT 1
                  FROM user_file uf
                  WHERE uf.file_id = f.file_id
                    AND uf.is_deleted = 0
              )
            LIMIT 1
            """)
    FileEntity selectGeocodingFile(@Param("fileId") String fileId);

    List<FileInfo> selectAllFileByUserId(@Param("userId") Long userId,@Param("size")Integer size);

    List<FileInfoListVO> selectAllSimilarPicture(@Param("userId") Long userId, @Param("imageTypeText") String imageTypeText);

    FileMetaDataVO selectFileMetaDataById(@Param("fileId") String fileId, @Param("userId") Long userId);

    List<PendingFileTask> selectPendingVideosWithoutTask(
            @Param("taskType") String taskType,
            @Param("taskVersion") String taskVersion,
            @Param("limit") int limit
    );

    List<PendingFileTask> selectPendingGeocodingWithoutTask(
            @Param("taskType") String taskType,
            @Param("taskVersion") String taskVersion,
            @Param("limit") int limit
    );

    List<PendingFileTask> selectPendingImageTagsWithoutTask(
            @Param("taskType") String taskType,
            @Param("taskVersion") String taskVersion,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE file
            SET location = #{location}
            WHERE file_id = #{fileId}
              AND latitude = #{latitude}
              AND longitude = #{longitude}
            """)
    int updateLocationIfCoordinatesMatch(
            @Param("fileId") String fileId,
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("location") String location
    );

    @Update("""
            UPDATE file
            SET status = #{status},
                status_update_time = NOW(),
                status_message = #{message}
            WHERE file_id = #{fileId}
            """)
    int updateStatus(
            @Param("fileId") String fileId,
            @Param("status") String status,
            @Param("message") String message
    );

    @Update("""
            UPDATE file
            SET status = #{targetStatus},
                status_update_time = NOW(),
                status_message = #{message}
            WHERE file_id = #{fileId}
              AND status = #{currentStatus}
            """)
    int updateStatusIfCurrent(
            @Param("fileId") String fileId,
            @Param("currentStatus") String currentStatus,
            @Param("targetStatus") String targetStatus,
            @Param("message") String message
    );

    @Update("""
            UPDATE file
            SET status = #{status},
                status_update_time = NOW(),
                status_message = #{message}
            WHERE file_id = #{fileId}
              AND COALESCE(status, 'READY') != 'DELETING'
            """)
    int updateStatusIfNotDeleting(
            @Param("fileId") String fileId,
            @Param("status") String status,
            @Param("message") String message
    );

    @Select("""
            SELECT *
            FROM file
            WHERE COALESCE(status, 'READY') != 'DELETING'
              AND (
                  file_object_name IS NOT NULL
                  OR thumbnail_object_name IS NOT NULL
              )
            ORDER BY status_update_time ASC, file_id ASC
            LIMIT #{limit}
            """)
    List<FileEntity> selectFilesForObjectReconcile(@Param("limit") int limit);

    @Select("""
            SELECT *
            FROM file
            WHERE status IN ('UPLOADING', 'PROCESSING', 'FAILED', 'DELETING')
              AND status_update_time < #{cutoff}
            ORDER BY status_update_time ASC, file_id ASC
            LIMIT #{limit}
            """)
    List<FileEntity> selectLongAbnormalFiles(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );

    @Select({
            "<script>",
            "SELECT DISTINCT object_name",
            "FROM (",
            "  SELECT file_object_name AS object_name",
            "  FROM file",
            "  WHERE file_object_name IN",
            "  <foreach collection='objectNames' item='objectName' open='(' separator=',' close=')'>",
            "    #{objectName}",
            "  </foreach>",
            "  UNION",
            "  SELECT thumbnail_object_name AS object_name",
            "  FROM file",
            "  WHERE thumbnail_object_name IN",
            "  <foreach collection='objectNames' item='objectName' open='(' separator=',' close=')'>",
            "    #{objectName}",
            "  </foreach>",
            ") known",
            "WHERE object_name IS NOT NULL",
            "  AND object_name != ''",
            "</script>"
    })
    List<String> selectKnownObjectNames(@Param("objectNames") List<String> objectNames);


    // 文件大小分布统计
    List<FileSize> selectVisualFileSize(@Param("userId") Long userId);

    // 文件类型分布统计
    List<FileContentType> selectVisualContentType(@Param("userId") Long userId);


    VisualFileVO selectFileCount(@Param("userId") Long userId);


}
