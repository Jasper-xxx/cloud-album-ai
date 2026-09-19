package com.memory.xzp.config;

import com.memory.xzp.service.RecycleService;
import com.memory.xzp.service.ScheduledTaskLockService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** Both legacy schedules use the same transaction and distributed lock. */
@Component
public class FileCleanupTask {
    @Resource private RecycleService recycleService;
    @Resource private ScheduledTaskLockService scheduledTaskLockService;
    @Value("${app.scheduler-lock.file-cleanup-ttl-seconds:14400}")
    private long fileCleanupLockTtlSeconds;
    @Value("${app.file.cleanup-enabled:true}") private boolean enabled;

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupSoftDeletedFiles() {
        if (!enabled) return;
        scheduledTaskLockService.runWithLock("cron:recycle-drop-picture",
                Duration.ofSeconds(fileCleanupLockTtlSeconds), recycleService::cronDropPicture);
    }
}
