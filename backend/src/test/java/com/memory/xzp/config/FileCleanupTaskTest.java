package com.memory.xzp.config;
import com.memory.xzp.service.RecycleService;
import com.memory.xzp.service.ScheduledTaskLockService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FileCleanupTaskTest {
    @Test void bothSchedulesDelegateToTheSameCleanupContract() {
        FileCleanupTask task = new FileCleanupTask();
        RecycleService recycle = mock(RecycleService.class);
        ScheduledTaskLockService lock = mock(ScheduledTaskLockService.class);
        ReflectionTestUtils.setField(task, "recycleService", recycle);
        ReflectionTestUtils.setField(task, "scheduledTaskLockService", lock);
        ReflectionTestUtils.setField(task, "enabled", true);
        when(lock.runWithLock(eq("cron:recycle-drop-picture"), any(), any())).thenAnswer(call -> {
            call.getArgument(2, Runnable.class).run(); return true;
        });
        task.cleanupSoftDeletedFiles();
        verify(recycle).cronDropPicture();
    }
}
