package com.memory.xzp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.model.entity.Face;
import com.memory.xzp.model.entity.Person;
import com.memory.xzp.model.vo.album.PersonAlbumVO;
import com.memory.xzp.model.vo.album.PersonCoverVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PersonMapper extends BaseMapper<Person> {

    @Select("select face.face_id from face join person_face pf on face.face_id = pf.face_id where pf.person_id=#{personId} order by pf.representative desc,pf.update_time desc limit 1")
    Long selectRepresentativeId(@Param("personId") Long personId);

    List<Face> selectRecentFacesForCluster(@Param("userId") Long userId,
                                           @Param("personId") Long personId,
                                           @Param("limit") Integer limit);

    void updatePersonFaces(@Param("userId") Long userId,
                           @Param("representativeId") Long representativeId,
                           @Param("mergePersonIds") List<Long> mergePersonIds);

    void movePersonFacesByFaceId(@Param("userId") Long userId,
                                 @Param("sourcePersonId") Long sourcePersonId,
                                 @Param("targetPersonId") Long targetPersonId,
                                 @Param("faceIds") List<Long> faceIds);

    void deletePersonById(@Param("userId") Long userId, @Param("mergePersonIds") List<Long> mergePersonIds);

    void deletePersonFacesByFaceId(@Param("userId") Long userId,
                                   @Param("personId") Long personId,
                                   @Param("faceIds") List<Long> faceIds);

    void hiddenPerson(@Param("userId") Long userId, @Param("personIds") List<Long> personIds);

    void restorePerson(@Param("userId") Long userId, @Param("personIds") List<Long> personIds);

    PersonAlbumVO selectPersonById(@Param("userId") Long userId, @Param("personId") Long personId);

    List<PersonAlbumVO> selectAllPersonAlbum(@Param("page") Page<PersonAlbumVO> page,
                                             @Param("userId") Long userId,
                                             @Param("display") Boolean display);

    @Select("select face.person_cover_url as coverUrl,face.face_id as faceId from face join person_face pf on pf.face_id = face.face_id where person_id=#{personId} and pf.user_id=#{userId}")
    List<PersonCoverVO> selectAllPersonCover(@Param("userId") Long userId, @Param("personId") Long personId);

    @Delete("DELETE p FROM person p " +
            "LEFT JOIN person_face pf ON p.person_id = pf.person_id " +
            "WHERE pf.person_id IS NULL AND p.user_id = #{userId}")
    int deleteEmptyPersonsByUserId(@Param("userId") Long userId);
}
