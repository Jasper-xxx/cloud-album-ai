package com.memory.xzp.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * P3/P4 预览阶段所需的最小权限查询。
 */
public interface AgentExtendedActionMapper {

    @Select({
            "<script>",
            "SELECT uf.file_id FROM user_file uf",
            "WHERE uf.user_id = #{userId} AND uf.is_deleted = 1",
            "AND uf.file_id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "ORDER BY uf.file_id",
            "</script>"
    })
    List<String> selectOwnedDeletedFileIds(
            @Param("userId") Long userId,
            @Param("fileIds") List<String> fileIds
    );

    @Select("""
            SELECT uf.file_id
            FROM user_file uf
            WHERE uf.user_id = #{userId}
              AND uf.is_deleted = 1
            ORDER BY uf.deleted_time ASC, uf.file_id ASC
            LIMIT #{limit}
            """)
    List<String> selectRecycleFileIds(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );

    @Select({
            "<script>",
            "SELECT a.album_id FROM album a",
            "WHERE a.user_id = #{userId} AND a.type = 'normal'",
            "AND a.album_id IN",
            "<foreach collection='albumIds' item='albumId' open='(' separator=',' close=')'>",
            "#{albumId}",
            "</foreach>",
            "ORDER BY a.album_id",
            "</script>"
    })
    List<Long> selectOwnedAlbumIds(
            @Param("userId") Long userId,
            @Param("albumIds") List<Long> albumIds
    );

    @Select({
            "<script>",
            "SELECT COUNT(DISTINCT ap.file_id)",
            "FROM album_picture ap",
            "INNER JOIN album a ON a.album_id = ap.album_id",
            "INNER JOIN user_file uf ON uf.file_id = ap.file_id",
            "WHERE a.user_id = #{userId} AND uf.user_id = #{userId} AND uf.is_deleted = 0",
            "AND a.album_id IN",
            "<foreach collection='albumIds' item='albumId' open='(' separator=',' close=')'>",
            "#{albumId}",
            "</foreach>",
            "</script>"
    })
    int countOwnedAlbumFiles(
            @Param("userId") Long userId,
            @Param("albumIds") List<Long> albumIds
    );

    @Select({
            "<script>",
            "SELECT DISTINCT f.file_id",
            "FROM face f",
            "INNER JOIN person_face pf ON pf.face_id = f.face_id",
            "INNER JOIN user_file uf ON uf.file_id = f.file_id",
            "WHERE pf.user_id = #{userId} AND pf.person_id = #{personId}",
            "AND uf.user_id = #{userId} AND uf.is_deleted = 0",
            "AND f.file_id IN",
            "<foreach collection='fileIds' item='fileId' open='(' separator=',' close=')'>",
            "#{fileId}",
            "</foreach>",
            "ORDER BY f.file_id",
            "</script>"
    })
    List<String> selectPersonFileIds(
            @Param("userId") Long userId,
            @Param("personId") Long personId,
            @Param("fileIds") List<String> fileIds
    );
}
