package com.memory.xzp.config;

/**
 * @description:
 * @author: xzp
 * @date: 2025/2/7,19:53
 */

import cn.dev33.satoken.filter.SaServletFilter;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${agent.auth-enabled:true}")
    private boolean agentAuthEnabled;

    /**
     * 配置saToken的身份权限拦截器
     *
     * @param registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 建议改用过滤器方式配置
        List<String> excludePatterns = new ArrayList<>(List.of(
                "/auth/accountLogin",
                "/auth/codeLogin",
                "/auth/getEmailCode",
                "/auth/accountRegister",
                "/agent/capabilities",
                "/file/getShareFileInfo",
                "/file/selectSharedMetaDataByFileId",
                "/file/downloadFileByToken",
                "/album/downloadAlbumByToken",
                "/swagger-ui/**",
                "/mockApi/auth/accountLogin",
                "/mockApi/auth/codeLogin",
                "/mockApi/auth/getEmailCode",
                "/mockApi/auth/accountRegister",
                "/mockApi/file/getShareFileInfo",
                "/mockApi/file/selectSharedMetaDataByFileId",
                "/mockApi/file/downloadFileByToken",
                "/mockApi/album/downloadAlbumByToken"
        ));

        if (!agentAuthEnabled) {
            excludePatterns.add("/agent/**");
        }

        registry.addInterceptor(new SaInterceptor().setAuth(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .order(Ordered.HIGHEST_PRECEDENCE)
                .excludePathPatterns(excludePatterns);
    }
}
