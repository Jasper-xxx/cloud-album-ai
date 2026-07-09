package com.memory.xzp.config;

import cn.dev33.satoken.stp.StpUtil;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AsyncTaskAdminGuard {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskAdminGuard.class);

    private final Set<Long> adminUserIds;

    public AsyncTaskAdminGuard(
            @Value("${app.async.task.admin-user-ids:}") String configuredUserIds
    ) {
        this.adminUserIds = parseUserIds(configuredUserIds);
    }

    public Long checkAdmin() {
        StpUtil.checkLogin();
        Long userId = StpUtil.getLoginIdAsLong();
        if (!adminUserIds.contains(userId)) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "无异步任务管理权限");
        }
        return userId;
    }

    boolean isAdmin(Long userId) {
        return userId != null && adminUserIds.contains(userId);
    }

    private Set<Long> parseUserIds(String configuredUserIds) {
        if (configuredUserIds == null || configuredUserIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configuredUserIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::parseUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private Long parseUserId(String value) {
        try {
            long userId = Long.parseLong(value);
            if (userId <= 0) {
                throw new NumberFormatException("User ID must be positive");
            }
            return userId;
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid async task admin user ID: {}", value);
            return null;
        }
    }
}
