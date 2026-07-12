package com.memory.xzp.service;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.model.enums.AsyncTaskType;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AsyncTaskRecoveryServiceTest {

    @Test
    void exposesRuntimeStatesAndAllowsToggling() {
        AsyncTaskService taskService = mock(AsyncTaskService.class);
        AsyncTaskRecoveryService recoveryService = newRecoveryService(
                taskService,
                true,
                true,
                false,
                false
        );

        assertTrue(recoveryService.isEnabled(AsyncTaskType.FACE_ANALYSIS));
        assertFalse(recoveryService.isEnabled(AsyncTaskType.GEO_CODING));

        recoveryService.setEnabled(AsyncTaskType.GEO_CODING, true);

        assertTrue(recoveryService.isEnabled(AsyncTaskType.GEO_CODING));
        assertEquals(4, recoveryService.listStatuses().size());
    }

    @Test
    void manualRunClampsBatchSizeAndDelegatesByTaskType() {
        AsyncTaskService taskService = mock(AsyncTaskService.class);
        AsyncTaskRecoveryService recoveryService = newRecoveryService(
                taskService,
                true,
                true,
                true,
                false
        );
        when(taskService.enqueuePendingImageTags(500)).thenReturn(12);

        int enqueued = recoveryService.runNow(AsyncTaskType.IMAGE_TAG, 1000);

        assertEquals(12, enqueued);
        verify(taskService).enqueuePendingImageTags(500);
    }

    @Test
    void rejectsUnsupportedTaskTypes() {
        AsyncTaskRecoveryService recoveryService = newRecoveryService(
                mock(AsyncTaskService.class),
                true,
                true,
                true,
                false
        );

        assertThrows(
                BusinessException.class,
                () -> recoveryService.parseSupportedType("IMAGE_FEATURE")
        );
    }

    private AsyncTaskRecoveryService newRecoveryService(
            AsyncTaskService taskService,
            boolean faceEnabled,
            boolean videoEnabled,
            boolean geoEnabled,
            boolean tagEnabled
    ) {
        ScheduledTaskLockService lockService = mock(ScheduledTaskLockService.class);
        when(lockService.callWithLock(anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<Integer> action = invocation.getArgument(2, Supplier.class);
                    return action.get();
                });
        return new AsyncTaskRecoveryService(
                taskService,
                lockService,
                faceEnabled,
                videoEnabled,
                geoEnabled,
                tagEnabled
        );
    }
}
