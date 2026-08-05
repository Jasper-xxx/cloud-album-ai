package com.memory.xzp.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public BaseResponse<?> notLoginException(NotLoginException e) {
        log.error("NotLoginException", e);

        return ResultUtil.error(StatusCode.NOT_LOGIN_ERROR, "用户未登录!");
    }

    @ExceptionHandler(NotPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public BaseResponse<?> notPermissionExceptionHandler(NotPermissionException e) {
        log.error("NotPermissionException", e);
        return ResultUtil.error(StatusCode.NO_AUTH_ERROR, "用户没权限访问!");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public BaseResponse<?> httpMessageNotReadableExceptionHandler(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadableException: {}", e.getMessage());
        return ResultUtil.error(StatusCode.PARAMS_ERROR, "请求体格式或字段类型错误");
    }

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e, HttpServletResponse response) {
        log.error("BusinessException", e);
        response.setStatus(httpStatusFor(e.getCode()).value());
        return ResultUtil.error(e.getCode(),e.getMessage());
    }

    private HttpStatus httpStatusFor(int code) {
        if (code == StatusCode.PARAMS_ERROR.getCode()) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code == StatusCode.NOT_LOGIN_ERROR.getCode()) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code == StatusCode.NO_AUTH_ERROR.getCode()
                || code == StatusCode.FORBIDDEN_ERROR.getCode()) {
            return HttpStatus.FORBIDDEN;
        }
        if (code == StatusCode.NOT_FOUND_ERROR.getCode()) {
            return HttpStatus.NOT_FOUND;
        }
        if (code == StatusCode.CONFLICT_ERROR.getCode()) {
            return HttpStatus.CONFLICT;
        }
        if (code == StatusCode.GONE_ERROR.getCode()) {
            return HttpStatus.GONE;
        }
        if (code == StatusCode.RATE_LIMIT_ERROR.getCode()) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public BaseResponse<?> businessExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtil.error(StatusCode.SYSTEM_ERROR,"系统错误");
    }
}
