package com.memory.xzp.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticated Dify tools are bound to one configured owner, never to a supplied user ID. */
@Component
public class AgentAccessGuard {
    private static final String OWNER_ATTRIBUTE = AgentAccessGuard.class.getName() + ".owner";

    @Value("${agent.service-key:}")
    private String serviceKey = "";
    @Value("${agent.owner-user-id:0}")
    private long ownerUserId;

    public void checkAccess() {
        requireUserId(currentRequest());
    }

    /**
     * Validate and resolve the owner from the concrete request received by a
     * controller. Multipart handling wraps the servlet request after the
     * request context is installed, so its attributes are not guaranteed to be
     * visible through RequestContextHolder.
     */
    public Long requireUserId(HttpServletRequest request) {
        String supplied = request == null ? null : request.getHeader("X-Agent-Service-Key");
        if (supplied != null) {
            if (ownerUserId > 0 && serviceKey.length() >= 32 && MessageDigest.isEqual(
                    serviceKey.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                request.setAttribute(OWNER_ATTRIBUTE, ownerUserId);
                return ownerUserId;
            }
            throw new com.memory.xzp.exception.BusinessException(
                    com.memory.xzp.exception.StatusCode.NO_AUTH_ERROR, "智能体服务凭据无效");
        }
        StpUtil.checkLogin();
        return StpUtil.getLoginIdAsLong();
    }

    public static Long currentUserId() {
        HttpServletRequest request = currentRequest();
        Object owner = request == null ? null : request.getAttribute(OWNER_ATTRIBUTE);
        return owner instanceof Long ? (Long) owner : StpUtil.getLoginIdAsLong();
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
