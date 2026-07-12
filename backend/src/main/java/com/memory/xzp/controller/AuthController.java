package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.config.ratelimit.RateLimit;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.model.dto.UserAccountDTO;
import com.memory.xzp.model.vo.UserLoginVO;
import com.memory.xzp.service.AuthService;
import com.memory.xzp.utils.auth.RedisUtil;
import com.memory.xzp.utils.auth.SendEmailUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @description:
 * @author: xzp
 * @date: 2025/2/18,16:21
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Autowired
    private SendEmailUtil sendEmailUtil;

    @Autowired
    private AuthService authService;

    @Autowired
    private RedisUtil redisUtil;


    @RequestMapping("/accountRegister")
    @RateLimit(permitsPerSecond = 0.2, scope = RateLimit.Scope.IP)
    public BaseResponse<?> accountRegister(@RequestBody UserAccountDTO userAccountDTO) {
        Boolean res = authService.accountRegister(userAccountDTO);
        if(res){
            return ResultUtil.success("账户注册成功,请登录!");
        }
        else {
            return ResultUtil.error(StatusCode.PARAMS_ERROR.getCode(),"账户注册失败！");
        }

    }


    @RequestMapping("/accountLogin")
    @RateLimit(permitsPerSecond = 1.0, scope = RateLimit.Scope.IP)
    public BaseResponse<UserLoginVO> accountLogin(@RequestBody UserAccountDTO userAccountDTO)  {
        UserLoginVO userLoginVO = authService.accountLogin(userAccountDTO);

        return ResultUtil.success(userLoginVO,"账户登录成功");
    }

    @RequestMapping("/getEmailCode")
    @RateLimit(permitsPerSecond = 0.1, scope = RateLimit.Scope.IP)
    public BaseResponse<?> sendEmailCode(@RequestBody UserAccountDTO userAccountDTO)  {
        String emailCode = authService.getEmailCode(userAccountDTO);

        return ResultUtil.success("验证码已发送至："+ userAccountDTO.getEmail());
    }

    @RequestMapping("/codeLogin")
    @RateLimit(permitsPerSecond = 1.0, scope = RateLimit.Scope.IP)
    public BaseResponse<UserLoginVO> codeLogin(@RequestBody UserAccountDTO userAccountDTO)  {
        UserLoginVO userLoginVO = authService.codeLogin(userAccountDTO);
        return ResultUtil.success(userLoginVO,"账户登录成功!");
    }

    @RequestMapping("/accountLogout")
    public BaseResponse<UserLoginVO> accountLogout() {
        StpUtil.logout();
        return ResultUtil.success(null,"账户退出登录!");
    }

    @RequestMapping("/updatePassWord")
    public BaseResponse<?> updatePassWord(@RequestBody UserAccountDTO userAccountDTO)  {
        authService.updatePassword(userAccountDTO);
        return ResultUtil.success("账户密码修改成功!");
    }

    @RequestMapping("/updateEmail")
    public BaseResponse<?> updateEmail(@RequestBody UserAccountDTO userAccountDTO)  {
        authService.updateEmail(userAccountDTO);
        return ResultUtil.success("邮箱修改成功!");
    }
}
