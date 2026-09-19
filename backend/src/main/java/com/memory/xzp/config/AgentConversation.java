package com.memory.xzp.config;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Transport metadata, never a model-generated business parameter. */
public final class AgentConversation {
    private AgentConversation() {}

    public static String current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "缺少智能体会话上下文");
        }
        String value = attributes.getRequest().getParameter("conversationId");
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "conversationId格式无效");
        }
        return value;
    }
}
