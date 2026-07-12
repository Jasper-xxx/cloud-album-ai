package com.memory.xzp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.config.ObservabilityConstants;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.AsyncTaskMapper;
import com.memory.xzp.mapper.FaceMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PictureTagMapper;
import com.memory.xzp.metrics.AsyncTaskMetrics;
import com.memory.xzp.model.entity.AsyncTaskEntity;
import com.memory.xzp.model.entity.Face;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.dto.task.PendingFileTask;
import com.memory.xzp.model.enums.AsyncTaskStatus;
import com.memory.xzp.model.enums.AsyncTaskType;
import com.memory.xzp.model.enums.FileStatus;
import com.memory.xzp.model.vo.task.AsyncTaskBatchActionVO;
import com.memory.xzp.model.vo.task.AsyncTaskVO;
import com.memory.xzp.model.vo.picture.TagResult;
import com.memory.xzp.service.AsyncTaskService;
import com.memory.xzp.service.AsyncTaskOutboxService;
import com.memory.xzp.service.FaceService;
import com.memory.xzp.service.FileFeatureService;
import com.memory.xzp.service.LocationService;
import com.memory.xzp.service.ScheduledTaskLockService;
import com.memory.xzp.service.VideoPostProcessingService;
import com.memory.xzp.utils.picture.ImageTagUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class AsyncTaskServiceImpl implements AsyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskServiceImpl.class);
    private static final int MAX_ERROR_LENGTH = 1000;
    private final Map<String, Object> fileFeatureProcessLocks = new ConcurrentHashMap<>();
    private final Map<Long, Object> userFaceProcessLocks = new ConcurrentHashMap<>();
    private final Set<Long> dispatchingTaskIds = ConcurrentHashMap.newKeySet();

    @Resource
    private AsyncTaskMapper asyncTaskMapper;

    @Resource
    private FileFeatureService fileFeatureService;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private FaceMapper faceMapper;

    @Resource
    private FaceService faceService;

    @Resource
    private VideoPostProcessingService videoPostProcessingService;

    @Resource
    private LocationService locationService;

    @Resource
    private PictureTagMapper pictureTagMapper;

    @Resource
    private ImageTagUtil imageTagUtil;

    @Resource
    private ScheduledTaskLockService scheduledTaskLockService;

    @Resource
    private AsyncTaskOutboxService asyncTaskOutboxService;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AsyncTaskMetrics asyncTaskMetrics;

    @Resource(name = "fileTaskExecutor")
    private Executor fileTaskExecutor;

    @Resource(name = "aiBatchTaskExecutor")
    private Executor aiBatchTaskExecutor;

    @Value("${ai.feature.version:v1}")
    private String featureVersion;

    @Value("${ai.feature.running-timeout-seconds:120}")
    private long imageFeatureRunningTimeoutSeconds;

    @Value("${ai.face.task-version:v1}")
    private String faceTaskVersion;

    @Value("${ai.face.running-timeout-seconds:120}")
    private long faceAnalysisRunningTimeoutSeconds;

    @Value("${app.async.task.video-version:v1}")
    private String videoTaskVersion;

    @Value("${app.async.task.geo-version:v1}")
    private String geoTaskVersion;

    @Value("${ai.tag.task-version:v1}")
    private String imageTagTaskVersion;

    @Value("${ai.tag.running-timeout-seconds:180}")
    private long imageTagRunningTimeoutSeconds;

    @Value("${app.async.task.max-retries:5}")
    private int maxRetries;

    @Value("${app.async.task.scan-batch-size:50}")
    private int scanBatchSize;

    @Value("${app.async.task.initial-retry-delay-seconds:30}")
    private long initialRetryDelaySeconds;

    @Value("${app.async.task.max-retry-delay-seconds:300}")
    private long maxRetryDelaySeconds;

    @Value("${app.async.task.running-timeout-minutes:30}")
    private long runningTimeoutMinutes;

    @Value("${app.scheduler-lock.async-dispatch-ttl-seconds:120}")
    private long asyncDispatchLockTtlSeconds;

    @Value("${app.async.task.mq.enabled:false}")
    private boolean asyncTaskMqEnabled;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long enqueueImageFeature(String fileId, Long userId, String objectName) {
        if (fileId == null || fileId.isBlank() || userId == null || objectName == null || objectName.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "图片特征任务参数不完整");
        }

        String taskKey = AsyncTaskType.IMAGE_FEATURE + ":" + fileId + ":" + userId + ":" + featureVersion;
        AsyncTaskEntity newTask = new AsyncTaskEntity();
        newTask.setTaskKey(taskKey);
        newTask.setTaskType(AsyncTaskType.IMAGE_FEATURE.name());
        newTask.setUserId(userId);
        newTask.setFileId(fileId);
        newTask.setPayloadJson(writePayload(new ImageFeaturePayload(objectName, currentRequestId(), currentTraceId())));
        newTask.setStatus(AsyncTaskStatus.PENDING.name());
        newTask.setMaxRetries(maxRetries);

        asyncTaskMapper.insertIfAbsent(newTask);
        AsyncTaskEntity persistedTask = asyncTaskMapper.selectByTaskKey(taskKey);
        if (persistedTask == null) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "异步任务创建失败");
        }
        scheduleDispatch(persistedTask);
        return persistedTask.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long enqueueFaceAnalysis(Long faceId, String fileId, Long userId) {
        if (faceId == null || fileId == null || fileId.isBlank() || userId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "人脸分析任务参数不完整");
        }

        String taskKey = AsyncTaskType.FACE_ANALYSIS + ":" + faceId + ":" + faceTaskVersion;
        AsyncTaskEntity newTask = new AsyncTaskEntity();
        newTask.setTaskKey(taskKey);
        newTask.setTaskType(AsyncTaskType.FACE_ANALYSIS.name());
        newTask.setUserId(userId);
        newTask.setFileId(fileId);
        newTask.setPayloadJson(writePayload(new FaceAnalysisPayload(faceId, currentRequestId(), currentTraceId())));
        newTask.setStatus(AsyncTaskStatus.PENDING.name());
        newTask.setMaxRetries(maxRetries);

        asyncTaskMapper.insertIfAbsent(newTask);
        AsyncTaskEntity persistedTask = asyncTaskMapper.selectByTaskKey(taskKey);
        if (persistedTask == null) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "人脸分析任务创建失败");
        }
        scheduleDispatch(persistedTask);
        return persistedTask.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int enqueuePendingFaceAnalyses(int batchSize) {
        int safeBatchSize = Math.min(Math.max(batchSize, 1), 500);
        List<Face> pendingFaces = faceMapper.selectPendingWithoutTask(
                AsyncTaskType.FACE_ANALYSIS.name(),
                faceTaskVersion,
                safeBatchSize
        );

        int enqueued = 0;
        for (Face face : pendingFaces) {
            if (face.getFaceId() == null
                    || face.getFileId() == null
                    || face.getFileId().isBlank()
                    || face.getUserId() == null) {
                log.warn("Skipping invalid pending face record: faceId={}, fileId={}, userId={}",
                        face.getFaceId(), face.getFileId(), face.getUserId());
                continue;
            }
            enqueueFaceAnalysis(face.getFaceId(), face.getFileId(), face.getUserId());
            enqueued++;
        }
        return enqueued;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long enqueueVideoProcessing(String fileId, Long userId) {
        if (fileId == null || fileId.isBlank() || userId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "视频后处理任务参数不完整");
        }

        String taskKey = AsyncTaskType.VIDEO_PROCESSING + ":" + fileId + ":" + videoTaskVersion;
        AsyncTaskEntity newTask = new AsyncTaskEntity();
        newTask.setTaskKey(taskKey);
        newTask.setTaskType(AsyncTaskType.VIDEO_PROCESSING.name());
        newTask.setUserId(userId);
        newTask.setFileId(fileId);
        newTask.setStatus(AsyncTaskStatus.PENDING.name());
        newTask.setMaxRetries(maxRetries);

        asyncTaskMapper.insertIfAbsent(newTask);
        AsyncTaskEntity persistedTask = asyncTaskMapper.selectByTaskKey(taskKey);
        if (persistedTask == null) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "视频后处理任务创建失败");
        }
        scheduleDispatch(persistedTask);
        return persistedTask.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int enqueuePendingVideoProcessing(int batchSize) {
        int safeBatchSize = Math.min(Math.max(batchSize, 1), 500);
        List<PendingFileTask> pendingFiles = fileMapper.selectPendingVideosWithoutTask(
                AsyncTaskType.VIDEO_PROCESSING.name(),
                videoTaskVersion,
                safeBatchSize
        );

        int enqueued = 0;
        for (PendingFileTask pendingFile : pendingFiles) {
            if (pendingFile.getFileId() == null
                    || pendingFile.getFileId().isBlank()
                    || pendingFile.getUserId() == null) {
                log.warn("Skipping invalid pending video record: fileId={}, userId={}",
                        pendingFile.getFileId(), pendingFile.getUserId());
                continue;
            }
            enqueueVideoProcessing(pendingFile.getFileId(), pendingFile.getUserId());
            enqueued++;
        }
        return enqueued;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long enqueueGeocoding(String fileId, Long userId) {
        if (fileId == null || fileId.isBlank() || userId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "地理编码任务参数不完整");
        }

        List<FileEntity> ownedFiles = fileMapper.selectFileByIds(List.of(fileId), userId);
        if (ownedFiles.size() != 1) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "文件不存在或无权访问");
        }
        FileEntity file = ownedFiles.get(0);
        if (!hasValidCoordinates(file)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件没有有效的 GPS 坐标");
        }

        String coordinateKey = String.format(
                Locale.ROOT,
                "%.6f,%.6f",
                file.getLatitude(),
                file.getLongitude()
        );
        String taskKey = AsyncTaskType.GEO_CODING + ":" + fileId + ":"
                + geoTaskVersion + ":" + coordinateKey;
        AsyncTaskEntity newTask = new AsyncTaskEntity();
        newTask.setTaskKey(taskKey);
        newTask.setTaskType(AsyncTaskType.GEO_CODING.name());
        newTask.setUserId(userId);
        newTask.setFileId(fileId);
        newTask.setPayloadJson(writePayloadValue(
                new GeocodingPayload(file.getLatitude(), file.getLongitude(), currentRequestId(), currentTraceId())
        ));
        newTask.setStatus(AsyncTaskStatus.PENDING.name());
        newTask.setMaxRetries(maxRetries);

        asyncTaskMapper.insertIfAbsent(newTask);
        AsyncTaskEntity persistedTask = asyncTaskMapper.selectByTaskKey(taskKey);
        if (persistedTask == null) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "地理编码任务创建失败");
        }
        scheduleDispatch(persistedTask);
        return persistedTask.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int enqueuePendingGeocoding(int batchSize) {
        int safeBatchSize = Math.min(Math.max(batchSize, 1), 500);
        List<PendingFileTask> pendingFiles = fileMapper.selectPendingGeocodingWithoutTask(
                AsyncTaskType.GEO_CODING.name(),
                geoTaskVersion,
                safeBatchSize
        );

        int enqueued = 0;
        for (PendingFileTask pendingFile : pendingFiles) {
            if (pendingFile.getFileId() == null
                    || pendingFile.getFileId().isBlank()
                    || pendingFile.getUserId() == null) {
                log.warn("Skipping invalid pending geocoding record: fileId={}, userId={}",
                        pendingFile.getFileId(), pendingFile.getUserId());
                continue;
            }
            try {
                enqueueGeocoding(pendingFile.getFileId(), pendingFile.getUserId());
                enqueued++;
            } catch (BusinessException e) {
                log.warn("Skipping geocoding candidate that is no longer eligible: fileId={}, userId={}",
                        pendingFile.getFileId(), pendingFile.getUserId());
            }
        }
        return enqueued;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long enqueueImageTag(String fileId, Long userId, boolean autoAddTag) {
        if (fileId == null || fileId.isBlank() || userId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "图片标签任务参数不完整");
        }

        List<FileEntity> files = fileMapper.selectFileByIds(List.of(fileId), userId);
        if (files.size() != 1 || !"image".equals(files.get(0).getCategory())) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "图片不存在或无权访问");
        }
        FileEntity file = files.get(0);
        if (!hasImageObject(file)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "图片对象路径不存在");
        }

        String mode = autoAddTag ? "auto" : "preview";
        String taskKey = AsyncTaskType.IMAGE_TAG + ":" + fileId + ":" + userId + ":"
                + imageTagTaskVersion + ":" + mode;
        AsyncTaskEntity newTask = new AsyncTaskEntity();
        newTask.setTaskKey(taskKey);
        newTask.setTaskType(AsyncTaskType.IMAGE_TAG.name());
        newTask.setUserId(userId);
        newTask.setFileId(fileId);
        newTask.setPayloadJson(writePayloadValue(new ImageTagPayload(autoAddTag, currentRequestId(), currentTraceId())));
        newTask.setStatus(AsyncTaskStatus.PENDING.name());
        newTask.setMaxRetries(maxRetries);

        asyncTaskMapper.insertIfAbsent(newTask);
        AsyncTaskEntity persistedTask = asyncTaskMapper.selectByTaskKey(taskKey);
        if (persistedTask == null) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "图片标签任务创建失败");
        }
        recoverStaleImageTagTask(persistedTask);
        scheduleDispatch(persistedTask);
        return persistedTask.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int enqueuePendingImageTags(int batchSize) {
        int safeBatchSize = Math.min(Math.max(batchSize, 1), 500);
        List<PendingFileTask> pendingFiles = fileMapper.selectPendingImageTagsWithoutTask(
                AsyncTaskType.IMAGE_TAG.name(),
                imageTagTaskVersion,
                safeBatchSize
        );

        int enqueued = 0;
        for (PendingFileTask pendingFile : pendingFiles) {
            if (pendingFile.getFileId() == null
                    || pendingFile.getFileId().isBlank()
                    || pendingFile.getUserId() == null) {
                log.warn("Skipping invalid pending image tag record: fileId={}, userId={}",
                        pendingFile.getFileId(), pendingFile.getUserId());
                continue;
            }
            try {
                enqueueImageTag(pendingFile.getFileId(), pendingFile.getUserId(), true);
                enqueued++;
            } catch (BusinessException e) {
                log.warn("Skipping image tag candidate that is no longer eligible: fileId={}, userId={}",
                        pendingFile.getFileId(), pendingFile.getUserId());
            }
        }
        return enqueued;
    }

    @Override
    public Page<AsyncTaskVO> listUserTasks(Long userId, long current, long size, String status) {
        if (userId == null || current < 1 || size < 1 || size > 100) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "分页参数错误");
        }

        QueryWrapper<AsyncTaskEntity> query = new QueryWrapper<AsyncTaskEntity>()
                .eq("user_id", userId)
                .orderByDesc("create_time");
        if (status != null && !status.isBlank()) {
            query.eq("status", parseStatus(status).name());
        }

        Page<AsyncTaskEntity> entityPage = asyncTaskMapper.selectPage(new Page<>(current, size), query);
        Page<AsyncTaskVO> result = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        result.setRecords(entityPage.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    @Override
    public AsyncTaskVO getUserTask(Long taskId, Long userId) {
        if (taskId == null || userId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "任务参数错误");
        }
        AsyncTaskEntity task = asyncTaskMapper.selectOne(
                new QueryWrapper<AsyncTaskEntity>()
                        .eq("id", taskId)
                        .eq("user_id", userId)
        );
        if (task == null) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "任务不存在");
        }
        return toVO(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void retryTask(Long taskId, Long userId) {
        if (taskId == null || userId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "任务参数错误");
        }
        if (asyncTaskMapper.resetForManualRetry(taskId, userId) != 1) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "任务不存在或当前状态不可重试");
        }
        scheduleDispatch(taskId);
    }

    @Override
    public Page<AsyncTaskVO> listAdminTasks(
            long current,
            long size,
            String status,
            String taskType,
            Long userId,
            String fileId
    ) {
        if (current < 1 || size < 1 || size > 100 || (userId != null && userId <= 0)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "分页或筛选参数错误");
        }

        QueryWrapper<AsyncTaskEntity> query = new QueryWrapper<AsyncTaskEntity>()
                .orderByDesc("create_time");
        if (status != null && !status.isBlank()) {
            query.eq("status", parseStatus(status).name());
        }
        if (taskType != null && !taskType.isBlank()) {
            query.eq("task_type", parseTaskType(taskType).name());
        }
        if (userId != null) {
            query.eq("user_id", userId);
        }
        if (fileId != null && !fileId.isBlank()) {
            query.eq("file_id", fileId.trim());
        }

        Page<AsyncTaskEntity> entityPage = asyncTaskMapper.selectPage(new Page<>(current, size), query);
        Page<AsyncTaskVO> result = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        result.setRecords(entityPage.getRecords().stream().map(this::toVO).toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskBatchActionVO retryTasks(List<Long> taskIds) {
        List<Long> normalizedIds = normalizeTaskIds(taskIds);
        List<Long> retryableIds = asyncTaskMapper.selectRetryableTaskIds(normalizedIds);
        if (retryableIds.isEmpty()) {
            return new AsyncTaskBatchActionVO(normalizedIds.size(), 0);
        }
        int updated = asyncTaskMapper.resetForAdminRetry(retryableIds);
        scheduleDispatchBatch(retryableIds);
        return new AsyncTaskBatchActionVO(normalizedIds.size(), updated);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AsyncTaskBatchActionVO cancelDeadTasks(List<Long> taskIds) {
        List<Long> normalizedIds = normalizeTaskIds(taskIds);
        int updated = asyncTaskMapper.cancelDeadTasks(normalizedIds);
        return new AsyncTaskBatchActionVO(normalizedIds.size(), updated);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverTasksOnStartup() {
        runStartupTask("async-task-recover-and-dispatch", this::runRecoverAndDispatchWithLock);
    }

    @Scheduled(
            fixedDelayString = "${app.async.task.scan-delay-ms:30000}",
            initialDelayString = "${app.async.task.scan-initial-delay-ms:30000}"
    )
    public void scanDueTasks() {
        runRecoverAndDispatchWithLock();
    }

    private void runRecoverAndDispatchWithLock() {
        scheduledTaskLockService.runWithLock(
                "async-task:recover-and-dispatch",
                Duration.ofSeconds(asyncDispatchLockTtlSeconds),
                this::recoverAndDispatch
        );
    }

    private void runStartupTask(String taskName, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Startup task failed: taskName={}, error={}", taskName, e.getMessage(), e);
        }
    }

    void dispatch(Long taskId) {
        if (taskId == null || !dispatchingTaskIds.add(taskId)) {
            return;
        }
        AsyncTaskEntity pendingTask;
        try {
            pendingTask = asyncTaskMapper.selectById(taskId);
        } catch (RuntimeException e) {
            dispatchingTaskIds.remove(taskId);
            log.warn("Failed to resolve persistent task executor: taskId={}, error={}",
                    taskId, e.getMessage());
            return;
        }
        if (!isDispatchable(pendingTask)) {
            dispatchingTaskIds.remove(taskId);
            return;
        }
        try {
            Executor executor = selectExecutor(pendingTask);
            executor.execute(() -> executeQueuedTask(taskId));
        } catch (RejectedExecutionException e) {
            dispatchingTaskIds.remove(taskId);
            asyncTaskMetrics.recordDispatchRejected(pendingTask.getTaskType());
            log.warn("Persistent task executor is full; task remains pending: taskId={}", taskId);
        }
    }

    private void executeQueuedTask(Long taskId) {
        try {
            if (asyncTaskMapper.claim(taskId) != 1) {
                return;
            }
            executeClaimedTask(taskId);
        } finally {
            dispatchingTaskIds.remove(taskId);
        }
    }

    private boolean isDispatchable(AsyncTaskEntity task) {
        if (task == null || task.getId() == null) {
            return false;
        }
        boolean runnableStatus = AsyncTaskStatus.PENDING.name().equals(task.getStatus())
                || AsyncTaskStatus.FAILED.name().equals(task.getStatus());
        if (!runnableStatus) {
            return false;
        }
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int effectiveMaxRetries = task.getMaxRetries() == null ? maxRetries : task.getMaxRetries();
        return retryCount < effectiveMaxRetries
                && (task.getNextRetryTime() == null
                || !task.getNextRetryTime().isAfter(LocalDateTime.now()));
    }

    private Executor selectExecutor(AsyncTaskEntity task) {
        if (task != null && isAiTask(task.getTaskType())) {
            return aiBatchTaskExecutor;
        }
        return fileTaskExecutor;
    }

    private boolean isAiTask(String taskType) {
        return AsyncTaskType.IMAGE_FEATURE.name().equals(taskType)
                || AsyncTaskType.FACE_ANALYSIS.name().equals(taskType)
                || AsyncTaskType.IMAGE_TAG.name().equals(taskType);
    }

    void executeClaimedTask(Long taskId) {
        AsyncTaskEntity task = asyncTaskMapper.selectById(taskId);
        if (task == null || !AsyncTaskStatus.RUNNING.name().equals(task.getStatus())) {
            return;
        }

        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        applyTaskMdc(task);
        long startedAt = System.nanoTime();
        try {
            String resultJson = executeHandler(task);
            asyncTaskMapper.markSuccess(taskId, resultJson);
            markFileReadyAfterTask(task);
            asyncTaskMetrics.recordExecution(
                    task.getTaskType(),
                    "SUCCESS",
                    System.nanoTime() - startedAt
            );
            log.info("Persistent task completed: taskId={}, type={}, fileId={}",
                    taskId, task.getTaskType(), task.getFileId());
        } catch (NonRetryableTaskException e) {
            asyncTaskMapper.markDead(taskId, errorMessage(e));
            markFileFailedAfterTask(task, e);
            asyncTaskMetrics.recordExecution(
                    task.getTaskType(),
                    "DEAD",
                    System.nanoTime() - startedAt
            );
            log.warn("Persistent task moved to dead state: taskId={}, type={}, error={}",
                    taskId, task.getTaskType(), e.getMessage());
        } catch (Exception e) {
            int nextAttempt = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(retryDelaySeconds(nextAttempt));
            asyncTaskMapper.markFailure(taskId, nextRetryTime, errorMessage(e));
            int effectiveMaxRetries = task.getMaxRetries() == null ? maxRetries : task.getMaxRetries();
            String outcome = nextAttempt >= effectiveMaxRetries ? "DEAD" : "RETRY";
            if ("DEAD".equals(outcome)) {
                markFileFailedAfterTask(task, e);
            }
            if ("RETRY".equals(outcome)) {
                asyncTaskMetrics.recordRetry(task.getTaskType());
            }
            asyncTaskMetrics.recordExecution(
                    task.getTaskType(),
                    outcome,
                    System.nanoTime() - startedAt
            );
            log.warn("Persistent task failed: taskId={}, type={}, attempt={}, error={}",
                    taskId, task.getTaskType(), nextAttempt, e.getMessage());
        } finally {
            restoreMdc(previousMdc);
        }
    }

    private void markFileReadyAfterTask(AsyncTaskEntity task) {
        if (!isCriticalFileStateTask(task)) {
            return;
        }
        try {
            if (AsyncTaskType.IMAGE_FEATURE.name().equals(task.getTaskType())
                    && !imageHasMaterializedThumbnail(task.getFileId())) {
                return;
            }
            fileMapper.updateStatusIfNotDeleting(
                    task.getFileId(),
                    FileStatus.READY.name(),
                    task.getTaskType() + " completed"
            );
        } catch (RuntimeException e) {
            log.warn("Failed to mark file ready after task success: taskId={}, fileId={}, error={}",
                    task.getId(), task.getFileId(), e.getMessage());
        }
    }

    private void markFileFailedAfterTask(AsyncTaskEntity task, Exception cause) {
        if (!isCriticalFileStateTask(task)) {
            return;
        }
        try {
            fileMapper.updateStatusIfNotDeleting(
                    task.getFileId(),
                    FileStatus.FAILED.name(),
                    statusMessage(task.getTaskType() + " failed: " + cause.getMessage())
            );
        } catch (RuntimeException e) {
            log.warn("Failed to mark file failed after task failure: taskId={}, fileId={}, error={}",
                    task.getId(), task.getFileId(), e.getMessage());
        }
    }

    private boolean isCriticalFileStateTask(AsyncTaskEntity task) {
        return task != null
                && task.getFileId() != null
                && (AsyncTaskType.IMAGE_FEATURE.name().equals(task.getTaskType())
                || AsyncTaskType.VIDEO_PROCESSING.name().equals(task.getTaskType()));
    }

    private boolean imageHasMaterializedThumbnail(String fileId) {
        FileEntity file = fileMapper.selectById(fileId);
        if (file == null) {
            return false;
        }
        String thumbnailObjectName = file.getThumbnailObjectName();
        return thumbnailObjectName != null
                && !thumbnailObjectName.isBlank()
                && !thumbnailObjectName.equals(file.getFileObjectName());
    }

    private String executeHandler(AsyncTaskEntity task) {
        if (task.getTaskType() == null) {
            throw new NonRetryableTaskException("Async task type is missing");
        }
        AsyncTaskType type;
        try {
            type = AsyncTaskType.valueOf(task.getTaskType());
        } catch (IllegalArgumentException e) {
            throw new NonRetryableTaskException("Unsupported async task type: " + task.getTaskType(), e);
        }

        return switch (type) {
            case IMAGE_FEATURE -> {
                executeImageFeature(task);
                yield null;
            }
            case FACE_ANALYSIS -> {
                executeFaceAnalysis(task);
                yield null;
            }
            case VIDEO_PROCESSING -> {
                executeVideoProcessing(task);
                yield null;
            }
            case GEO_CODING -> {
                executeGeocoding(task);
                yield null;
            }
            case IMAGE_TAG -> executeImageTag(task);
        };
    }

    private void executeImageFeature(AsyncTaskEntity task) {
        readPayload(task, ImageFeaturePayload.class, "image feature");
        FileEntity file = requireOwnedImage(task);
        String trustedObjectName = file.getFileObjectName();
        if (trustedObjectName == null || trustedObjectName.isBlank()) {
            throw new NonRetryableTaskException("File object path is missing");
        }
        String lockKey = task.getUserId() + ":" + task.getFileId();
        Object fileLock = fileFeatureProcessLocks.computeIfAbsent(lockKey, key -> new Object());
        try {
            synchronized (fileLock) {
                fileFeatureService.extractAndSaveFeature(task.getFileId(), task.getUserId(), trustedObjectName);
            }
        } finally {
            fileFeatureProcessLocks.remove(lockKey, fileLock);
        }
    }

    private void executeFaceAnalysis(AsyncTaskEntity task) {
        FaceAnalysisPayload payload = readPayload(task, FaceAnalysisPayload.class, "face analysis");
        if (payload.faceId() == null) {
            throw new NonRetryableTaskException("Face analysis task faceId is missing");
        }

        Face face = faceMapper.selectById(payload.faceId());
        if (face == null
                || !task.getUserId().equals(face.getUserId())
                || !task.getFileId().equals(face.getFileId())) {
            throw new NonRetryableTaskException("Face record is no longer available to the task owner");
        }
        requireOwnedImage(task);
        if (Boolean.TRUE.equals(face.getIsProcessed())) {
            return;
        }

        Object userLock = userFaceProcessLocks.computeIfAbsent(task.getUserId(), key -> new Object());
        synchronized (userLock) {
            Face latestFace = faceMapper.selectById(payload.faceId());
            if (latestFace == null || Boolean.TRUE.equals(latestFace.getIsProcessed())) {
                return;
            }
            faceService.processOneFace(latestFace);
        }
    }

    private void executeVideoProcessing(AsyncTaskEntity task) {
        FileEntity file = requireOwnedFile(task, "video");
        videoPostProcessingService.process(file);
    }

    private void executeGeocoding(AsyncTaskEntity task) {
        GeocodingPayload payload = readPayload(task, GeocodingPayload.class, "geocoding");
        if (!hasValidCoordinates(payload.latitude(), payload.longitude())) {
            throw new NonRetryableTaskException("Geocoding task coordinates are invalid");
        }

        FileEntity file = fileMapper.selectGeocodingFile(task.getFileId());
        if (file == null) {
            throw new NonRetryableTaskException("File no longer has an active owner");
        }
        if (!hasValidCoordinates(file)) {
            throw new NonRetryableTaskException("File GPS coordinates are no longer available");
        }
        if (Double.compare(file.getLatitude(), payload.latitude()) != 0
                || Double.compare(file.getLongitude(), payload.longitude()) != 0) {
            log.info("Skipping stale geocoding task: taskId={}, fileId={}",
                    task.getId(), task.getFileId());
            return;
        }
        locationService.processCoordinates(file);
    }

    private String executeImageTag(AsyncTaskEntity task) {
        ImageTagPayload payload = readPayload(task, ImageTagPayload.class, "image tag");
        FileEntity file = requireOwnedImage(task);
        String objectName = trustedImageObject(file);
        if (objectName == null) {
            throw new NonRetryableTaskException("Image object path is missing");
        }

        List<TagResult> tagResults = imageTagUtil.classifyImage("object_key", objectName);
        List<TagResult> normalizedResults = tagResults == null
                ? List.of()
                : tagResults.stream()
                        .filter(Objects::nonNull)
                        .filter(result -> result.getImageType() != null
                                && !result.getImageType().isBlank()
                                && result.getTagName() != null
                                && !result.getTagName().isBlank())
                        .limit(20)
                        .toList();

        if (payload.autoAddTag() && !normalizedResults.isEmpty()) {
            TagResult selected = normalizedResults.get(0);
            pictureTagMapper.insertIfAbsent(
                    task.getFileId(),
                    selected.getImageType().trim(),
                    selected.getTagName().trim()
            );
        }
        return writePayloadValue(normalizedResults);
    }

    private FileEntity requireOwnedImage(AsyncTaskEntity task) {
        return requireOwnedFile(task, "image");
    }

    private FileEntity requireOwnedFile(AsyncTaskEntity task) {
        return requireOwnedFile(task, null);
    }

    private FileEntity requireOwnedFile(AsyncTaskEntity task, String category) {
        FileEntity file = requireOwnedFile(task.getFileId(), task.getUserId());
        if (category != null && !category.equals(file.getCategory())) {
            throw new NonRetryableTaskException("File is no longer available to the task owner");
        }
        return file;
    }

    private FileEntity requireOwnedFile(String fileId, Long userId) {
        List<FileEntity> files = fileMapper.selectFileByIds(List.of(fileId), userId);
        if (files.size() != 1) {
            throw new NonRetryableTaskException("File is no longer available to the task owner");
        }
        return files.get(0);
    }

    private boolean hasValidCoordinates(FileEntity file) {
        return file != null && hasValidCoordinates(file.getLatitude(), file.getLongitude());
    }

    private boolean hasValidCoordinates(Double latitude, Double longitude) {
        return latitude != null
                && longitude != null
                && Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    private boolean hasImageObject(FileEntity file) {
        return trustedImageObject(file) != null;
    }

    private String trustedImageObject(FileEntity file) {
        if (file == null) {
            return null;
        }
        if (file.getThumbnailObjectName() != null && !file.getThumbnailObjectName().isBlank()) {
            return file.getThumbnailObjectName();
        }
        if (file.getFileObjectName() != null && !file.getFileObjectName().isBlank()) {
            return file.getFileObjectName();
        }
        return null;
    }

    private void applyTaskMdc(AsyncTaskEntity task) {
        if (task == null) {
            return;
        }
        String requestId = null;
        String traceId = null;
        if (task.getPayloadJson() != null && !task.getPayloadJson().isBlank()) {
            try {
                JsonNode payload = objectMapper.readTree(task.getPayloadJson());
                requestId = textValue(payload, ObservabilityConstants.MDC_REQUEST_ID);
                traceId = textValue(payload, ObservabilityConstants.MDC_TRACE_ID);
            } catch (JsonProcessingException e) {
                log.debug("Async task payload is not readable for trace context: taskId={}", task.getId());
            }
        }
        MDC.put(
                ObservabilityConstants.MDC_REQUEST_ID,
                requestId == null || requestId.isBlank() ? "async-" + task.getId() : requestId
        );
        MDC.put(
                ObservabilityConstants.MDC_TRACE_ID,
                traceId == null || traceId.isBlank() ? MDC.get(ObservabilityConstants.MDC_REQUEST_ID) : traceId
        );
        if (task.getId() != null) {
            MDC.put(ObservabilityConstants.MDC_ASYNC_TASK_ID, String.valueOf(task.getId()));
        }
        if (task.getTaskType() != null) {
            MDC.put(ObservabilityConstants.MDC_ASYNC_TASK_TYPE, task.getTaskType());
        }
    }

    private String textValue(JsonNode node, String fieldName) {
        if (node == null || !node.hasNonNull(fieldName)) {
            return null;
        }
        String value = node.get(fieldName).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private void restoreMdc(Map<String, String> previousMdc) {
        if (previousMdc == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousMdc);
        }
    }

    private <T> T readPayload(AsyncTaskEntity task, Class<T> payloadType, String taskName) {
        if (task.getPayloadJson() == null) {
            throw new NonRetryableTaskException(taskName + " task payload is missing");
        }
        try {
            return objectMapper.readValue(task.getPayloadJson(), payloadType);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new NonRetryableTaskException("Invalid " + taskName + " task payload", e);
        }
    }

    private void recoverAndDispatch() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int recovered = recoverStaleRunningByType(
                    AsyncTaskType.IMAGE_FEATURE,
                    imageFeatureRunningTimeoutSeconds,
                    now
            );
            recovered += recoverStaleRunningByType(
                    AsyncTaskType.FACE_ANALYSIS,
                    faceAnalysisRunningTimeoutSeconds,
                    now
            );
            recovered += asyncTaskMapper.recoverStaleRunning(now.minusMinutes(runningTimeoutMinutes));
            if (recovered > 0) {
                asyncTaskMetrics.recordStaleRecovered(recovered);
                log.warn("Recovered stale persistent tasks: count={}", recovered);
            }
            List<Long> taskIds = asyncTaskMapper.selectDueTaskIds(scanBatchSize);
            taskIds.forEach(this::scheduleDispatch);
        } catch (Exception e) {
            log.warn("Persistent task scan failed: {}", e.getMessage());
        }
    }

    private int recoverStaleRunningByType(
            AsyncTaskType taskType,
            long timeoutSeconds,
            LocalDateTime now
    ) {
        if (timeoutSeconds <= 0) {
            return 0;
        }
        return asyncTaskMapper.recoverStaleRunningByType(
                taskType.name(),
                now.minusSeconds(timeoutSeconds),
                taskType.name() + " timed out after " + timeoutSeconds + " seconds"
        );
    }

    private void recoverStaleImageTagTask(AsyncTaskEntity task) {
        if (imageTagRunningTimeoutSeconds <= 0
                || task == null
                || task.getId() == null
                || !AsyncTaskStatus.RUNNING.name().equals(task.getStatus())
                || task.getStartedAt() == null) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(imageTagRunningTimeoutSeconds);
        if (!task.getStartedAt().isBefore(cutoff)) {
            return;
        }

        if (asyncTaskMapper.releaseClaim(task.getId()) == 1) {
            task.setStatus(AsyncTaskStatus.PENDING.name());
            task.setStartedAt(null);
            log.warn("Recovered stale image tag task for immediate retry: taskId={}, fileId={}",
                    task.getId(), task.getFileId());
        }
    }

    private void dispatchAfterCommit(Long taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(taskId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                dispatch(taskId);
            }
        });
    }

    private void scheduleDispatch(AsyncTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        if (asyncTaskMqEnabled) {
            asyncTaskOutboxService.recordDispatch(task);
            return;
        }
        dispatchAfterCommit(task.getId());
    }

    private void scheduleDispatch(Long taskId) {
        if (taskId == null) {
            return;
        }
        if (!asyncTaskMqEnabled) {
            dispatchAfterCommit(taskId);
            return;
        }
        AsyncTaskEntity task = asyncTaskMapper.selectById(taskId);
        scheduleDispatch(task);
    }

    private void dispatchBatchAfterCommit(List<Long> taskIds) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            taskIds.forEach(this::dispatch);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskIds.forEach(AsyncTaskServiceImpl.this::dispatch);
            }
        });
    }

    private void scheduleDispatchBatch(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        if (!asyncTaskMqEnabled) {
            dispatchBatchAfterCommit(taskIds);
            return;
        }
        for (Long taskId : taskIds) {
            scheduleDispatch(taskId);
        }
    }

    private String writePayload(ImageFeaturePayload payload) {
        return writePayloadValue(payload);
    }

    private String writePayload(FaceAnalysisPayload payload) {
        return writePayloadValue(payload);
    }

    private String writePayloadValue(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "异步任务参数序列化失败");
        }
    }

    private String currentRequestId() {
        return MDC.get(ObservabilityConstants.MDC_REQUEST_ID);
    }

    private String currentTraceId() {
        return MDC.get(ObservabilityConstants.MDC_TRACE_ID);
    }

    private AsyncTaskStatus parseStatus(String status) {
        try {
            return AsyncTaskStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "任务状态不合法");
        }
    }

    private AsyncTaskType parseTaskType(String taskType) {
        try {
            return AsyncTaskType.valueOf(taskType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "任务类型不合法");
        }
    }

    private List<Long> normalizeTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty() || taskIds.size() > 100) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "任务 ID 数量必须在 1 到 100 之间");
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long taskId : taskIds) {
            if (taskId == null || taskId <= 0) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "任务 ID 不合法");
            }
            normalized.add(taskId);
        }
        return List.copyOf(normalized);
    }

    private long retryDelaySeconds(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 10);
        long delay;
        try {
            delay = Math.multiplyExact(initialRetryDelaySeconds, 1L << exponent);
        } catch (ArithmeticException e) {
            delay = maxRetryDelaySeconds;
        }
        return Math.min(delay, maxRetryDelaySeconds);
    }

    private String errorMessage(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": "
                + (exception.getMessage() == null ? "unknown error" : exception.getMessage());
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }

    private String statusMessage(String message) {
        if (message == null || message.length() <= 500) {
            return message;
        }
        return message.substring(0, 500);
    }

    private AsyncTaskVO toVO(AsyncTaskEntity entity) {
        AsyncTaskVO vo = new AsyncTaskVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setTaskType(entity.getTaskType());
        vo.setFileId(entity.getFileId());
        vo.setStatus(entity.getStatus());
        if (entity.getResultJson() != null && !entity.getResultJson().isBlank()) {
            try {
                vo.setResult(objectMapper.readTree(entity.getResultJson()));
            } catch (JsonProcessingException e) {
                log.warn("Ignoring invalid async task result JSON: taskId={}", entity.getId());
            }
        }
        vo.setRetryCount(entity.getRetryCount());
        vo.setMaxRetries(entity.getMaxRetries());
        vo.setNextRetryTime(entity.getNextRetryTime());
        vo.setLastError(entity.getLastError());
        vo.setStartedAt(entity.getStartedAt());
        vo.setCompletedAt(entity.getCompletedAt());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        return vo;
    }

    private record ImageFeaturePayload(String objectName, String requestId, String traceId) {
    }

    private record FaceAnalysisPayload(Long faceId, String requestId, String traceId) {
    }

    private record GeocodingPayload(Double latitude, Double longitude, String requestId, String traceId) {
    }

    private record ImageTagPayload(boolean autoAddTag, String requestId, String traceId) {
    }

    private static class NonRetryableTaskException extends RuntimeException {

        NonRetryableTaskException(String message) {
            super(message);
        }

        NonRetryableTaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
