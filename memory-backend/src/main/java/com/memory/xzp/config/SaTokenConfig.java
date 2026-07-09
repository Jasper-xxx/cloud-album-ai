package com.memory.xzp.config;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @description:
 * @author: xzp
 * @date: 2025/2/7,19:51
 */
@Configuration
public class SaTokenConfig {
    /**
     * 配置集成jwt
     * @return StpLogic
     */
    @Bean
    public StpLogic getStpLogicJwt() {
        return new StpLogicJwtForSimple();
    }
}

