package com.memory.xzp.utils.picture;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.model.vo.picture.TagResult;
import com.memory.xzp.service.ExternalServiceExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图像标签识别工具类。
 */
@Component
public class ImageTagUtil {

    private static final Logger log = LoggerFactory.getLogger(ImageTagUtil.class);

    /** 服务地址，配置在 application.yml: ai.service.url */
    @Value("${ai.service.url:http://localhost:5000}")
    private String aiServiceBaseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExternalServiceExecutor externalServiceExecutor;

    public ImageTagUtil(
            @Qualifier("aiHttp11RestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ExternalServiceExecutor externalServiceExecutor
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.externalServiceExecutor = externalServiceExecutor;
    }

    /**
     * 识别图片标签。
     *
     * @param requestImageType "url" 或 "base64"
     * @param image            图片内容（Base64 字符串或 HTTP URL）
     * @return 标签列表，每项包含 imageType / tagName / confidence
     */
    public List<TagResult> classifyImage(String requestImageType, String image) {
        String endpoint;
        Map<String, String> requestBody = new HashMap<>();
        switch (requestImageType) {
            case "object_key" -> {
                endpoint = aiServiceBaseUrl + "/recognize_from_minio";
                requestBody.put("object_key", image);
            }
            case "url" -> {
                endpoint = aiServiceBaseUrl + "/recognize";
                requestBody.put("url", image);
            }
            default -> {
                endpoint = aiServiceBaseUrl + "/recognize";
                requestBody.put("image", image);
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(requestBody, headers);
        long startedAt = System.nanoTime();

        try {
            ResponseEntity<String> response = externalServiceExecutor.execute(
                    ExternalServiceExecutor.AI,
                    () -> restTemplate.exchange(
                            endpoint,
                            HttpMethod.POST,
                            entity,
                            String.class
                    )
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new IllegalStateException(
                        "ai-service 返回异常状态: " + response.getStatusCode()
                );
            }

            List<TagResult> results = objectMapper.readValue(
                    response.getBody(),
                    new TypeReference<List<TagResult>>() {}
            );

            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("AI 识别成功: sourceType={}, tagCount={}, elapsedMs={}",
                    requestImageType, results.size(), elapsedMillis);
            return results;

        } catch (Exception e) {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            log.error("调用 ai-service 失败: sourceType={}, elapsedMs={}, error={}",
                    requestImageType, elapsedMillis, e.getMessage(), e);
            throw new RuntimeException("AI 图像识别服务调用失败: " + e.getMessage(), e);
        }
    }
}
