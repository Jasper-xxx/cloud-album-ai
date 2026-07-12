package com.memory.xzp.cron;

import com.memory.xzp.model.enums.AsyncTaskType;
import com.memory.xzp.service.AsyncTaskRecoveryService;
import com.memory.xzp.service.FileService;
import com.memory.xzp.service.RecycleService;
import com.memory.xzp.service.ScheduledTaskLockService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * @description:
 * @author: xzp
 * @date: 2025/3/2,16:10
 */
@Component
@Slf4j
public class CronSyncTask {

    @Resource
    private RecycleService recycleService;

    @Resource
    private AsyncTaskRecoveryService asyncTaskRecoveryService;

    @Resource
    private FileService fileService;

    @Resource
    private ScheduledTaskLockService scheduledTaskLockService;

    @Value("${app.async.task.face-recovery-batch-size:100}")
    private int faceRecoveryBatchSize;

    @Value("${app.async.task.video-recovery-batch-size:50}")
    private int videoRecoveryBatchSize;

    @Value("${app.async.task.geo-recovery-batch-size:50}")
    private int geoRecoveryBatchSize;

    @Value("${app.async.task.tag-recovery-batch-size:25}")
    private int tagRecoveryBatchSize;

    @Value("${app.scheduler-lock.recycle-cleanup-ttl-seconds:14400}")
    private long recycleCleanupLockTtlSeconds;

    @Value("${app.scheduler-lock.file-url-refresh-ttl-seconds:7200}")
    private long fileUrlRefreshLockTtlSeconds;


    @EventListener(ApplicationReadyEvent.class)
    public void DropPictureOnStartUp(){
        // log.info("应用启动完成，触发删除回收站照片");
       // recycleService.cronDropPicture();
    }
    //每天凌晨执行
    @Scheduled(cron = "0 0 0 * * ?")
    @Transactional
    public void CronDropPictureSync() {
        log.info("定时任务触发删除回收站照片");
        scheduledTaskLockService.runWithLock(
                "cron:recycle-drop-picture",
                Duration.ofSeconds(recycleCleanupLockTtlSeconds),
                recycleService::cronDropPicture
        );
    }


    @EventListener(ApplicationReadyEvent.class)
    public void CronUpdateFileUrlOnStartUp(){
        //log.info("应用启动完成，触发修改文件Url");
        runStartupTask("file-url-refresh", this::refreshFileUrlsWithLock);
    }
    @Scheduled(cron = "0 0 0 */6 * ?")
    public void  CronUpdateFileUrl(){
        refreshFileUrlsWithLock();
    }

    private void refreshFileUrlsWithLock() {
        scheduledTaskLockService.runWithLock(
                "cron:file-url-refresh",
                Duration.ofSeconds(fileUrlRefreshLockTtlSeconds),
                fileService::cronUpdateFileUrl
        );
    }


    @EventListener(ApplicationReadyEvent.class)
    public void FaceOnStartUp(){
        runStartupTask("face-recovery", () -> enqueuePendingFaceTasks("应用启动"));
    }

    @Scheduled(cron = "${app.async.task.face-recovery-cron:0 0/50 * * * ?}")
    public void CronFaceSync() {
        enqueuePendingFaceTasks("定时扫描");
    }

    private void enqueuePendingFaceTasks(String trigger) {
        enqueuePendingTasks(
                trigger,
                AsyncTaskType.FACE_ANALYSIS,
                faceRecoveryBatchSize,
                "人脸"
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void VideoOnStartUp() {
        runStartupTask("video-recovery", () -> enqueuePendingVideoTasks("应用启动"));
    }

    @Scheduled(cron = "${app.async.task.video-recovery-cron:0 10/50 * * * ?}")
    public void CronVideoSync() {
        enqueuePendingVideoTasks("定时扫描");
    }

    private void enqueuePendingVideoTasks(String trigger) {
        enqueuePendingTasks(
                trigger,
                AsyncTaskType.VIDEO_PROCESSING,
                videoRecoveryBatchSize,
                "视频"
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void GeocodingOnStartup() {
        runStartupTask("geo-recovery", () -> enqueuePendingGeocodingTasks("应用启动"));
    }

    @Scheduled(cron = "${app.async.task.geo-recovery-cron:0 20/50 * * * ?}")
    public void CronGeocodingSync() {
        enqueuePendingGeocodingTasks("定时扫描");
    }

    private void enqueuePendingGeocodingTasks(String trigger) {
        enqueuePendingTasks(
                trigger,
                AsyncTaskType.GEO_CODING,
                geoRecoveryBatchSize,
                "地理编码"
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ImageTagOnStartup() {
        runStartupTask("image-tag-recovery", () -> enqueuePendingImageTagTasks("应用启动"));
    }

    @Scheduled(cron = "${app.async.task.tag-recovery-cron:0 0/30 * * * ?}")
    public void CronImageTagSync() {
        enqueuePendingImageTagTasks("定时扫描");
    }

    private void enqueuePendingImageTagTasks(String trigger) {
        enqueuePendingTasks(
                trigger,
                AsyncTaskType.IMAGE_TAG,
                tagRecoveryBatchSize,
                "图片标签"
        );
    }

    private void enqueuePendingTasks(
            String trigger,
            AsyncTaskType taskType,
            int batchSize,
            String label
    ) {
        if (!asyncTaskRecoveryService.isEnabled(taskType)) {
            return;
        }
        int enqueued = asyncTaskRecoveryService.runNow(taskType, batchSize);
        log.info("{}补建待处理{}任务完成: count={}", trigger, label, enqueued);
    }

    private void runStartupTask(String taskName, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Startup task failed: taskName={}, error={}", taskName, e.getMessage(), e);
        }
    }
}
