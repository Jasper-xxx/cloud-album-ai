package com.memory.xzp.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

/**
 * RestTemplate 配置类
 *
 * <p>用于 Spring Boot 调用 FastAPI 推理服务（/extract_feature 等接口）</p>
 * <p>配置了超时时间，避免服务处理慢导致线程长时间阻塞</p>
 *
 * @author xzp
 * @date 2026/03/20
 */
@Configuration
public class RestTemplateConfig {

    /**
     * 注册 RestTemplate Bean
     *
     * <p>超时配置说明：</p>
     * <ul>
     *   <li>连接超时 5 秒：等待 AI 服务建立 TCP 连接的最长时间</li>
     *   <li>读取超时 60 秒：等待 AI 模型推理并返回特征向量的最长时间</li>
     * </ul>
     *
     * @param builder Spring 自动注入的 RestTemplateBuilder
     * @return 配置好的 RestTemplate 实例
     */
    @Bean
    public RestTemplate restTemplate() {
        return createRestTemplate();
    }

    /**
     * Dedicated HTTP/1.1 client for the local Uvicorn service.
     */
    @Bean(name = "aiHttp11RestTemplate")
    public RestTemplate aiHttp11RestTemplate() {
        return createRestTemplate();
    }

    private RestTemplate createRestTemplate() {
        RestTemplate restTemplate = new RestTemplate(requestFactory());
        restTemplate.setInterceptors(List.of(tracePropagationInterceptor()));
        return restTemplate;
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return requestFactory;
    }

    private ClientHttpRequestInterceptor tracePropagationInterceptor() {
        return (request, body, execution) -> {
            String requestId = MDC.get(ObservabilityConstants.MDC_REQUEST_ID);
            String traceId = MDC.get(ObservabilityConstants.MDC_TRACE_ID);
            if (StringUtils.hasText(requestId)) {
                request.getHeaders().set(ObservabilityConstants.REQUEST_ID_HEADER, requestId);
            }
            if (StringUtils.hasText(traceId)) {
                request.getHeaders().set(ObservabilityConstants.TRACE_ID_HEADER, traceId);
            }
            return execution.execute(request, body);
        };
    }
}
