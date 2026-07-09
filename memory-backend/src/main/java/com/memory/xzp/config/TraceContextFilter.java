package com.memory.xzp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = firstNonBlank(
                request.getHeader(ObservabilityConstants.REQUEST_ID_HEADER),
                request.getHeader("X-Request-Id"),
                request.getHeader("X-Correlation-ID"),
                generateId()
        );
        String traceId = firstNonBlank(
                request.getHeader(ObservabilityConstants.TRACE_ID_HEADER),
                traceIdFromTraceparent(request.getHeader(ObservabilityConstants.TRACEPARENT_HEADER)),
                requestId
        );

        MDC.put(ObservabilityConstants.MDC_REQUEST_ID, requestId);
        MDC.put(ObservabilityConstants.MDC_TRACE_ID, traceId);
        response.setHeader(ObservabilityConstants.REQUEST_ID_HEADER, requestId);
        response.setHeader(ObservabilityConstants.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(ObservabilityConstants.MDC_REQUEST_ID);
            MDC.remove(ObservabilityConstants.MDC_TRACE_ID);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return generateId();
    }

    private String traceIdFromTraceparent(String traceparent) {
        if (!StringUtils.hasText(traceparent)) {
            return null;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length >= 2 && parts[1].matches("[a-fA-F0-9]{32}")) {
            return parts[1].toLowerCase();
        }
        return null;
    }

    private String generateId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
