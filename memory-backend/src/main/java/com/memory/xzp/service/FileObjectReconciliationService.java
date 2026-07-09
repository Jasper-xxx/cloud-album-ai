package com.memory.xzp.service;

import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.enums.FileStatus;
import com.memory.xzp.utils.file.MinioOSSUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class FileObjectReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(FileObjectReconciliationService.class);

    private final FileMapper fileMapper;
    private final MinioOSSUtil minioOSSUtil;
    private final ScheduledTaskLockService scheduledTaskLockService;

    @Value("${app.file.reconcile.enabled:true}")
    private boolean enabled;

    @Value("${app.file.reconcile.batch-size:200}")
    private int batchSize;

    @Value("${app.file.reconcile.object-scan-limit:500}")
    private int objectScanLimit;

    @Value("${app.file.reconcile.abnormal-minutes:120}")
    private long abnormalMinutes;

    @Value("${app.scheduler-lock.file-reconcile-ttl-seconds:900}")
    private long fileReconcileLockTtlSeconds;

    public FileObjectReconciliationService(
            FileMapper fileMapper,
            MinioOSSUtil minioOSSUtil,
            ScheduledTaskLockService scheduledTaskLockService
    ) {
        this.fileMapper = fileMapper;
        this.minioOSSUtil = minioOSSUtil;
        this.scheduledTaskLockService = scheduledTaskLockService;
    }

    @Scheduled(
            initialDelayString = "${app.file.reconcile.initial-delay-ms:60000}",
            fixedDelayString = "${app.file.reconcile.delay-ms:300000}"
    )
    public void reconcile() {
        if (!enabled) {
            return;
        }
        scheduledTaskLockService.runWithLock(
                "file:object-reconciliation",
                Duration.ofSeconds(fileReconcileLockTtlSeconds),
                this::reconcileLocked
        );
    }

    private void reconcileLocked() {
        try {
            reconcileReferencedObjects();
            scanOrphanObjects("file/");
            scanOrphanObjects("thumbnail/");
            scanLongAbnormalRecords();
        } catch (RuntimeException e) {
            log.warn("File object reconciliation failed: {}", e.getMessage());
        }
    }

    private void reconcileReferencedObjects() {
        List<FileEntity> files = fileMapper.selectFilesForObjectReconcile(safeLimit(batchSize));
        for (FileEntity file : files) {
            reconcileFile(file);
        }
    }

    private void reconcileFile(FileEntity file) {
        if (file == null || file.getFileId() == null) {
            return;
        }
        try {
            if (hasText(file.getFileObjectName()) && !minioOSSUtil.objectExists(file.getFileObjectName())) {
                fileMapper.updateStatusIfNotDeleting(
                        file.getFileId(),
                        FileStatus.FAILED.name(),
                        statusMessage("Main object missing: " + file.getFileObjectName())
                );
                log.warn("File main object missing: fileId={}, objectName={}",
                        file.getFileId(), file.getFileObjectName());
                return;
            }
            if (shouldCheckThumbnail(file) && !minioOSSUtil.objectExists(file.getThumbnailObjectName())) {
                log.warn("File thumbnail object missing: fileId={}, objectName={}",
                        file.getFileId(), file.getThumbnailObjectName());
            }
        } catch (RuntimeException e) {
            log.warn("File object check failed: fileId={}, error={}", file.getFileId(), e.getMessage());
        }
    }

    private void scanOrphanObjects(String prefix) {
        List<String> objectNames = minioOSSUtil.listObjectNames(prefix, safeLimit(objectScanLimit));
        if (objectNames.isEmpty()) {
            return;
        }
        Set<String> knownObjectNames = new HashSet<>(fileMapper.selectKnownObjectNames(objectNames));
        long orphanCount = 0;
        for (String objectName : objectNames) {
            if (!knownObjectNames.contains(objectName)) {
                orphanCount++;
                if (orphanCount <= 20) {
                    log.warn("Orphan MinIO object found: objectName={}", objectName);
                }
            }
        }
        if (orphanCount > 0) {
            log.warn("Orphan MinIO object scan completed: prefix={}, scanned={}, orphanCount={}",
                    prefix, objectNames.size(), orphanCount);
        }
    }

    private void scanLongAbnormalRecords() {
        if (abnormalMinutes <= 0) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(abnormalMinutes);
        List<FileEntity> files = fileMapper.selectLongAbnormalFiles(cutoff, safeLimit(batchSize));
        for (FileEntity file : files) {
            String status = file.getStatus();
            if (FileStatus.UPLOADING.name().equals(status) || FileStatus.PROCESSING.name().equals(status)) {
                fileMapper.updateStatusIfCurrent(
                        file.getFileId(),
                        status,
                        FileStatus.FAILED.name(),
                        statusMessage("State timed out during reconciliation: " + status)
                );
            }
            log.warn("Long abnormal file state: fileId={}, status={}, statusUpdateTime={}, message={}",
                    file.getFileId(), status, file.getStatusUpdateTime(), file.getStatusMessage());
        }
    }

    private boolean shouldCheckThumbnail(FileEntity file) {
        return FileStatus.READY.name().equals(file.getStatus())
                && hasText(file.getThumbnailObjectName())
                && !file.getThumbnailObjectName().equals(file.getFileObjectName());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private int safeLimit(int value) {
        return Math.max(1, value);
    }

    private String statusMessage(String message) {
        if (message == null || message.length() <= 500) {
            return message;
        }
        return message.substring(0, 500);
    }
}
