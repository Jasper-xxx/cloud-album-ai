package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.config.UploadPolicy;
import com.memory.xzp.config.ratelimit.RateLimit;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.UserStorageMapper;
import com.memory.xzp.model.dto.UserAccountDTO;
import com.memory.xzp.model.dto.UserInfoDTO;
import com.memory.xzp.model.entity.User;
import com.memory.xzp.model.entity.UserStorage;
import com.memory.xzp.model.vo.entity.UserInfoVO;
import com.memory.xzp.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    UserService userService;

    @Resource
    UserStorageMapper userStorageMapper;
    @Resource
    UploadPolicy uploadPolicy;

    @PostMapping("/updateUserInfo")
    @RateLimit(permitsPerSecond = 1.0)
    public BaseResponse<?> updateUserInfo(@RequestBody UserInfoDTO userInfoDTO) throws InterruptedException {
        Long userId = StpUtil.getLoginIdAsLong();
        if (userInfoDTO == null || userInfoDTO.getUserName() == null
                || userInfoDTO.getUserName().isBlank()
                || userInfoDTO.getUserName().length() > 64
                || (userInfoDTO.getProfile() != null && userInfoDTO.getProfile().length() > 500)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "用户资料格式错误");
        }
        UpdateWrapper<User> updateWrapper= new UpdateWrapper<>();
        updateWrapper.eq("id", userId);
        updateWrapper.set("user_name",userInfoDTO.getUserName());
        updateWrapper.set("profile",userInfoDTO.getProfile());
        userService.update(updateWrapper);
        return ResultUtil.success("用户信息修改成功!");
    }

    @RequestMapping("/getUserInfo")
    public BaseResponse<UserInfoVO> getUserInfo()  {
        Long userId = Long.parseLong((String) StpUtil.getLoginId());
        QueryWrapper<User> queryWrapper = new QueryWrapper<User>();
        queryWrapper.eq("id",userId);
        User one = userService.getOne(queryWrapper);
        UserStorage userStorage = userStorageMapper.selectById(userId);
        UserInfoVO userInfoVO = new UserInfoVO();
        BeanUtils.copyProperties(userStorage, userInfoVO);
        BeanUtils.copyProperties(one, userInfoVO);
        return ResultUtil.success(userInfoVO,"用户信息获取成功!");
    }

    @PostMapping("/uploadAvatar")
    @RateLimit(permitsPerSecond = 0.2)
    public BaseResponse<?> updateUserAvatar(MultipartFile file) {
        Long userId = Long.parseLong((String) StpUtil.getLoginId());
        if (file == null || file.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "头像不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        uploadPolicy.validateAvatar(originalFilename, file.getContentType(), file.getSize());
        int i = originalFilename.lastIndexOf('.');
        String suffix = originalFilename.substring(i);
        userService.updateUserAvatar(file,userId,suffix);
        return ResultUtil.success("用户头像修改成功!");
    }

    @PostMapping("/updateUserStatus")
    public BaseResponse<?> handleUpdateUserStatus(String status,Integer day) {
        rejectDirectMembershipChange();
        return ResultUtil.error(StatusCode.NO_AUTH_ERROR, "不允许直接修改会员状态");
    }

    @PostMapping("/addUserStorage")
    public BaseResponse<?> addUserStorage(Long size){
        rejectDirectMembershipChange();
        return ResultUtil.error(StatusCode.NO_AUTH_ERROR, "不允许直接扩容");
    }

    private void rejectDirectMembershipChange() {
        throw new BusinessException(
                StatusCode.NO_AUTH_ERROR,
                "会员状态和容量只能由受信任的支付回调或管理端修改"
        );
    }

}
