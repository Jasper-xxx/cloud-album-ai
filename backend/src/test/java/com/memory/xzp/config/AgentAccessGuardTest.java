package com.memory.xzp.config;

import com.memory.xzp.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

class AgentAccessGuardTest {
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    private AgentAccessGuard guard(String supplied) {
        AgentAccessGuard guard = new AgentAccessGuard();
        ReflectionTestUtils.setField(guard, "serviceKey", "a".repeat(48));
        ReflectionTestUtils.setField(guard, "ownerUserId", 12L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Agent-Service-Key", supplied);
        request.addHeader("X-User-Id", "999");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return guard;
    }

    @Test void validServiceCredentialBindsConfiguredOwner() {
        guard("a".repeat(48)).checkAccess();
        assertEquals(12L, AgentAccessGuard.currentUserId());
    }

    @Test void forgedCredentialIsRejected() {
        assertThrows(BusinessException.class, () -> guard("b".repeat(48)).checkAccess());
    }

    @Test void unconfiguredOwnerIsRejected() {
        AgentAccessGuard guard = guard("a".repeat(48));
        ReflectionTestUtils.setField(guard, "ownerUserId", 0L);
        assertThrows(BusinessException.class, guard::checkAccess);
    }
}
