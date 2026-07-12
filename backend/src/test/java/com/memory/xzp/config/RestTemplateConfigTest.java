package com.memory.xzp.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RestTemplateConfigTest {

    @Test
    void aiClientUsesExplicitHttp11RequestFactory() {
        RestTemplate restTemplate = new RestTemplateConfig().aiHttp11RestTemplate();

        InterceptingClientHttpRequestFactory requestFactory = assertInstanceOf(
                InterceptingClientHttpRequestFactory.class,
                restTemplate.getRequestFactory()
        );
        assertInstanceOf(
                SimpleClientHttpRequestFactory.class,
                ReflectionTestUtils.getField(requestFactory, "requestFactory")
        );
    }
}
