package com.memory.xzp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskAdminGuardTest {

    @Test
    void parsesConfiguredAdminIdsAndDeniesUnknownUsers() {
        AsyncTaskAdminGuard guard = new AsyncTaskAdminGuard("7, 9, invalid, -1");

        assertTrue(guard.isAdmin(7L));
        assertTrue(guard.isAdmin(9L));
        assertFalse(guard.isAdmin(8L));
        assertFalse(guard.isAdmin(null));
    }

    @Test
    void emptyConfigurationDeniesEveryone() {
        AsyncTaskAdminGuard guard = new AsyncTaskAdminGuard("");

        assertFalse(guard.isAdmin(7L));
    }
}
