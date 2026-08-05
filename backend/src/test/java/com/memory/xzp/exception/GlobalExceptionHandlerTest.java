package com.memory.xzp.exception;

import com.memory.xzp.common.BaseResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest
    @MethodSource("businessStatusMappings")
    void mapsBusinessCodesToRealHttpStatuses(StatusCode statusCode, HttpStatus expectedStatus) {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        BusinessException exception = new BusinessException(statusCode, "test-error");

        BaseResponse<?> response =
                handler.businessExceptionHandler(exception, servletResponse);

        assertEquals(expectedStatus.value(), servletResponse.getStatus());
        assertEquals(statusCode.getCode(), response.getCode());
        assertEquals("test-error", response.getMessage());
    }

    @Test
    void mapsUnclassifiedBusinessFailureToInternalServerError() {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        handler.businessExceptionHandler(
                new BusinessException(StatusCode.OPERATION_ERROR, "operation failed"),
                servletResponse
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), servletResponse.getStatus());
    }

    private static Stream<Arguments> businessStatusMappings() {
        return Stream.of(
                Arguments.of(StatusCode.PARAMS_ERROR, HttpStatus.BAD_REQUEST),
                Arguments.of(StatusCode.NOT_LOGIN_ERROR, HttpStatus.UNAUTHORIZED),
                Arguments.of(StatusCode.NO_AUTH_ERROR, HttpStatus.FORBIDDEN),
                Arguments.of(StatusCode.FORBIDDEN_ERROR, HttpStatus.FORBIDDEN),
                Arguments.of(StatusCode.NOT_FOUND_ERROR, HttpStatus.NOT_FOUND),
                Arguments.of(StatusCode.CONFLICT_ERROR, HttpStatus.CONFLICT),
                Arguments.of(StatusCode.GONE_ERROR, HttpStatus.GONE),
                Arguments.of(StatusCode.RATE_LIMIT_ERROR, HttpStatus.TOO_MANY_REQUESTS)
        );
    }
}
