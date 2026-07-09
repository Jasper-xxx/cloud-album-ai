package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.PersonMapper;
import com.memory.xzp.model.dto.PersonDTO;
import com.memory.xzp.model.enums.PersonRelation;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.model.vo.album.PersonAlbumVO;
import com.memory.xzp.model.vo.album.PersonCoverVO;
import com.memory.xzp.service.PersonService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/person")
public class PersonController {

    @Resource
    private PersonService personService;

    @Resource
    private PersonMapper personMapper;

    @PostMapping("/selectPersonById")
    @Operation(summary = "查询人物", description = "查询指定人物详情")
    public BaseResponse<?> selectPersonById(Long personId) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        PersonAlbumVO personAlbumVO = personService.selectPersonById(userId, personId);
        return ResultUtil.success(personAlbumVO, "获取数据成功！");
    }

    @PostMapping("/selectAllPersonAlbum")
    @Operation(summary = "查询所有人物", description = "分页查询人物列表")
    public BaseResponse<?> selectAllPersonAlbum(String current, String size, Boolean display) {
        int c;
        int s;
        try {
            c = Integer.parseInt(current);
            s = Integer.parseInt(size);
        } catch (NumberFormatException e) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "分页错误!");
        }
        if (display == null) {
            display = true;
        }

        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        Page<PersonAlbumVO> personAlbumVOPage = personService.selectAllPersonAlbum(userId, c, s, display);
        return ResultUtil.success(personAlbumVOPage, "获取数据成功！");
    }

    @PostMapping("/selectAllPersonCover")
    @Operation(summary = "查询人物封面", description = "查询人物所有可选封面")
    public BaseResponse<?> selectAllPersonCover(Long personId) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        List<PersonCoverVO> personCover = personMapper.selectAllPersonCover(userId, personId);
        return ResultUtil.success(personCover, "获取数据成功！");
    }

    @PostMapping("/mergePerson")
    @Operation(summary = "合并人物", description = "将多个人物合并为一个")
    public BaseResponse<?> mergePerson(@RequestParam("personIds") List<Long> personIds) {
        if (personIds.isEmpty() || personIds.size() < 2) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        Long representativeId = personIds.get(0);
        personIds.remove(0);
        personService.mergePersons(userId, representativeId, personIds);

        return ResultUtil.success("合并成功！");
    }

    @PostMapping("/hiddenPerson")
    @Operation(summary = "隐藏人物", description = "隐藏选中的人物")
    public BaseResponse<?> hiddenPerson(@RequestParam("personIds") List<Long> personIds) {
        if (personIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        personMapper.hiddenPerson(userId, personIds);
        return ResultUtil.success("隐藏成功！");
    }

    @PostMapping("/restorePerson")
    @Operation(summary = "取消隐藏人物", description = "恢复显示选中的隐藏人物")
    public BaseResponse<?> restorePerson(@RequestParam("personIds") List<Long> personIds) {
        if (personIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        personMapper.restorePerson(userId, personIds);
        return ResultUtil.success("取消隐藏成功！");
    }

    @PostMapping("/selectPersonAlbumFileInfo")
    @Operation(summary = "查询人物照片", description = "查询人物对应的照片列表")
    public BaseResponse<?> selectPersonAlbumFileInfo(
            Integer current,
            Integer size,
            String orderType,
            String orderKeyword,
            String imageTypeText,
            Long personId
    ) {
        if ("upload_time".equals(orderKeyword)) {
            orderKeyword = "upload_time";
        } else {
            orderKeyword = "date_time_original";
        }
        if ("desc".equals(orderType)) {
            orderType = "desc";
        } else {
            orderType = "asc";
        }
        if (personId == null || current == null || size == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }

        Set<String> imageTypeTextSet = new HashSet<>(Arrays.asList("picture", "gif", "video"));
        imageTypeText = imageTypeTextSet.contains(imageTypeText) ? imageTypeText : "all";

        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        Page<FileInfoListVO> personFileInfo = personService.getPersonFileInfo(
                current, size, userId, orderType, orderKeyword, imageTypeText, personId
        );
        return ResultUtil.success(personFileInfo, "获取数据成功！");
    }

    @PostMapping("/updatePersonName")
    @Operation(summary = "修改人物名字", description = "修改人物名字")
    public BaseResponse<?> updatePersonName(@RequestBody PersonDTO person) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        person.setPersonRelation(null);
        person.setOldFaceId(person.getFaceId());
        personService.updatePerson(person, userId);
        return ResultUtil.success("名字已修改！");
    }

    @PostMapping("/updatePersonInfo")
    @Operation(summary = "修改人物信息", description = "修改人物资料和封面")
    public BaseResponse<?> updatePersonInfo(@RequestBody PersonDTO person) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        String personRelation = person.getPersonRelation();
        PersonRelation relation = PersonRelation.fromDisplayName(personRelation);
        Long faceId = person.getFaceId();
        if (relation == null) {
            personRelation = null;
        }
        if (faceId != null && faceId == -1) {
            faceId = null;
        }

        personService.updatePerson(person, userId);
        return ResultUtil.success("人物信息已修改！");
    }

    @PostMapping("/removePersonPicture")
    @Operation(summary = "移除人物里的图片", description = "解除图片与当前人物的关联")
    public BaseResponse<?> removePersonPicture(Long personId, @RequestParam("fileIds") List<String> fileIds) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        if (personId == null || personId == -1 || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        personService.removePersonPicture(userId, personId, fileIds);
        return ResultUtil.success("人物信息已修改！");
    }

    @PostMapping("/movePersonPicture")
    @Operation(summary = "移动人物里的图片", description = "将当前人物中的图片移动到指定人物")
    public BaseResponse<?> movePersonPicture(
            Long sourcePersonId,
            Long targetPersonId,
            @RequestParam("fileIds") List<String> fileIds
    ) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        if (sourcePersonId == null || targetPersonId == null || sourcePersonId == -1 || targetPersonId == -1
                || sourcePersonId.equals(targetPersonId) || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        personService.movePersonPicture(userId, sourcePersonId, targetPersonId, fileIds);
        return ResultUtil.success("人物信息已修改！");
    }
}
