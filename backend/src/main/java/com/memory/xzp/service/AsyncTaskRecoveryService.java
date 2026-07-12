package com.memory.xzp.service;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.model.enums.AsyncTaskType;
import com.memory.xzp.model.vo.task.RecoveryScanStatusVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AsyncTaskRecoveryService {

    private static final List<AsyncTaskType> SUPPORTED_TYPES = List.of(
            AsyncTaskType.FACE_ANALYSIS,
            AsyncTaskType.VIDEO_PROCESSING,
            AsyncTaskType.GEO_CODING,
            AsyncTaskType.IMAGE_TAG
    );

    private final AsyncTaskService asyncTaskService;
    private final ScheduledTaskLockService scheduledTaskLockService;
    private final Map<AsyncTaskType, AtomicBoolean> enabledStates =
            new EnumMap<>(AsyncTaskType.class);

    @Value("${app.scheduler-lock.recovery-ttl-seconds:300}")
    private long recoveryLockTtlSeconds;

    public AsyncTaskRecoveryService(
            AsyncTaskService asyncTaskService,
            ScheduledTaskLockService scheduledTaskLockService,
            @Value("${app.async.task.face-recovery-enabled:true}") boolean faceEnabled,
            @Value("${app.async.task.video-recovery-enabled:true}") boolean videoEnabled,
            @Value("${app.async.task.geo-recovery-enabled:true}") boolean geoEnabled,
            @Value("${app.async.task.tag-recovery-enabled:false}") boolean tagEnabled
    ) {
        this.asyncTaskService = asyncTaskService;
        this.scheduledTaskLockService = scheduledTaskLockService;
        enabledStates.put(AsyncTaskType.FACE_ANALYSIS, new AtomicBoolean(faceEnabled));
        enabledStates.put(AsyncTaskType.VIDEO_PROCESSING, new AtomicBoolean(videoEnabled));
        enabledStates.put(AsyncTaskType.GEO_CODING, new AtomicBoolean(geoEnabled));
        enabledStates.put(AsyncTaskType.IMAGE_TAG, new AtomicBoolean(tagEnabled));
    }

    public List<RecoveryScanStatusVO> listStatuses() {
        return SUPPORTED_TYPES.stream()
                .map(type -> new RecoveryScanStatusVO(type.name(), isEnabled(type)))
                .toList();
    }

    public boolean isEnabled(AsyncTaskType taskType) {
        return requireState(taskType).get();
    }

    public void setEnabled(AsyncTaskType taskType, boolean enabled) {
        requireState(taskType).set(enabled);
    }

    public int runNow(AsyncTaskType taskType, int batchSize) {
        int safeBatchSize = Math.min(Math.max(batchSize, 1), 500);
        requireState(taskType);
        return scheduledTaskLockService.callWithLock(
                "async-recovery:" + taskType.name(),
                Duration.ofSeconds(recoveryLockTtlSeconds),
                () -> runNowLocked(taskType, safeBatchSize),
                0
        );
    }

    private int runNowLocked(AsyncTaskType taskType, int safeBatchSize) {
        return switch (taskType) {
            case FACE_ANALYSIS -> asyncTaskService.enqueuePendingFaceAnalyses(safeBatchSize);
            case VIDEO_PROCESSING -> asyncTaskService.enqueuePendingVideoProcessing(safeBatchSize);
            case GEO_CODING -> asyncTaskService.enqueuePendingGeocoding(safeBatchSize);
            case IMAGE_TAG -> asyncTaskService.enqueuePendingImageTags(safeBatchSize);
            default -> throw unsupportedType();
        };
    }

    public AsyncTaskType parseSupportedType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            throw unsupportedType();
        }
        try {
            AsyncTaskType parsed = AsyncTaskType.valueOf(taskType.trim().toUpperCase());
            requireState(parsed);
            return parsed;
        } catch (IllegalArgumentException e) {
            throw unsupportedType();
        }
    }

    private AtomicBoolean requireState(AsyncTaskType taskType) {
        AtomicBoolean state = enabledStates.get(taskType);
        if (state == null) {
            throw unsupportedType();
        }
        return state;
    }

    private BusinessException unsupportedType() {
        return new BusinessException(StatusCode.PARAMS_ERROR, "该任务类型不支持补偿扫描");
    }
}
