package com.memory.xzp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.memory.xzp.model.dto.PersonDTO;
import com.memory.xzp.model.entity.Person;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.model.vo.album.PersonAlbumVO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PersonService extends IService<Person> {

    PersonAlbumVO selectPersonById(Long userId, Long personId);

    Page<PersonAlbumVO> selectAllPersonAlbum(Long userId, int current, int size, Boolean display);

    Page<FileInfoListVO> getPersonFileInfo(
            Integer current,
            Integer size,
            Long userId,
            String orderType,
            String orderKeyword,
            String imageTypeText,
            Long personId
    );

    void updatePerson(PersonDTO person, Long userId);

    @Transactional(rollbackFor = Exception.class)
    void mergePersons(Long userId, Long representativeId, List<Long> mergeIds);

    @Transactional(rollbackFor = Exception.class)
    void removePersonPicture(Long userId, Long personId, List<String> fileIds);

    @Transactional(rollbackFor = Exception.class)
    void movePersonPicture(Long userId, Long sourcePersonId, Long targetPersonId, List<String> fileIds);
}
