package com.memory.xzp.utils.picture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.model.vo.picture.TagResult;
import com.memory.xzp.service.ExternalServiceExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageTagUtilTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ExternalServiceExecutor externalServiceExecutor;

    @Test
    void objectKeyUsesDirectMinioRecognitionEndpoint() {
        ImageTagUtil imageTagUtil = newImageTagUtil();
        ReflectionTestUtils.setField(imageTagUtil, "aiServiceBaseUrl", "http://localhost:5000");
        when(restTemplate.exchange(
                eq("http://localhost:5000/recognize_from_minio"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<Map<String, String>>>any(),
                eq(String.class)
        )).thenReturn(ResponseEntity.ok(
                "[{\"imageType\":\"人物\",\"tagName\":\"人物\",\"confidence\":90.0}]"
        ));

        List<TagResult> result = imageTagUtil.classifyImage("object_key", "thumbnail/a.jpg");

        ArgumentCaptor<HttpEntity<Map<String, String>>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://localhost:5000/recognize_from_minio"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class)
        );
        assertEquals(Map.of("object_key", "thumbnail/a.jpg"), entityCaptor.getValue().getBody());
        assertEquals(1, result.size());
        assertEquals("人物", result.get(0).getTagName());
    }

    @Test
    void nonSuccessfulResponseIsPropagatedForTaskRetry() {
        ImageTagUtil imageTagUtil = newImageTagUtil();
        ReflectionTestUtils.setField(imageTagUtil, "aiServiceBaseUrl", "http://localhost:5000");
        when(restTemplate.exchange(
                eq("http://localhost:5000/recognize_from_minio"),
                eq(HttpMethod.POST),
                org.mockito.ArgumentMatchers.<HttpEntity<Map<String, String>>>any(),
                eq(String.class)
        )).thenReturn(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("upstream unavailable"));

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> imageTagUtil.classifyImage("object_key", "thumbnail/a.jpg")
        );

        assertInstanceOf(IllegalStateException.class, exception.getCause());
    }

    private ImageTagUtil newImageTagUtil() {
        when(externalServiceExecutor.execute(eq(ExternalServiceExecutor.AI), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    ExternalServiceExecutor.CheckedOperation<ResponseEntity<String>> operation =
                            invocation.getArgument(1, ExternalServiceExecutor.CheckedOperation.class);
                    return operation.call();
                });
        return new ImageTagUtil(restTemplate, new ObjectMapper(), externalServiceExecutor);
    }
}
