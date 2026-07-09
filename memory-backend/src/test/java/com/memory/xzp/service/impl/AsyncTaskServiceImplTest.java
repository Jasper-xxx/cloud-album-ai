package com.memory.xzp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.exception.BusinessException;
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
import com.memory.xzp.model.vo.picture.TagResult;
import com.memory.xzp.model.vo.task.AsyncTaskBatchActionVO;
import com.memory.xzp.model.vo.task.AsyncTaskVO;
import com.memory.xzp.service.FaceService;
import com.memory.xzp.service.FileFeatureService;
import com.memory.xzp.service.LocationService;
import com.memory.xzp.service.ScheduledTaskLockService;
import com.memory.xzp.service.VideoPostProcessingService;
import com.memory.xzp.utils.picture.ImageTagUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncTaskServiceImplTest {

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    @Mock
    private FileFeatureService fileFeatureService;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FaceMapper faceMapper;

    @Mock
    private FaceService faceService;

    @Mock
    private VideoPostProcessingService videoPostProcessingService;

    @Mock
    private LocationService locationService;

    @Mock
    private PictureTagMapper pictureTagMapper;

    @Mock
    private ImageTagUtil imageTagUtil;

    @Mock
    private ScheduledTaskLockService scheduledTaskLockService;

    @Mock
    private AsyncTaskMetrics asyncTaskMetrics;

    @Mock
    private Executor fileTaskExecutor;

    @Mock
    private Executor aiBatchTaskExecutor;

    @InjectMocks
    private AsyncTaskServiceImpl asyncTaskService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(asyncTaskService, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(asyncTaskService, "featureVersion", "v1");
        ReflectionTestUtils.setField(asyncTaskService, "faceTaskVersion", "v1");
        ReflectionTestUtils.setField(asyncTaskService, "videoTaskVersion", "v1");
        ReflectionTestUtils.setField(asyncTaskService, "geoTaskVersion", "v1");
        ReflectionTestUtils.setField(asyncTaskService, "imageTagTaskVersion", "v1");
        ReflectionTestUtils.setField(asyncTaskService, "imageTagRunningTimeoutSeconds", 180L);
        ReflectionTestUtils.setField(asyncTaskService, "maxRetries", 5);
        ReflectionTestUtils.setField(asyncTaskService, "scanBatchSize", 50);
        ReflectionTestUtils.setField(asyncTaskService, "initialRetryDelaySeconds", 30L);
        ReflectionTestUtils.setField(asyncTaskService, "maxRetryDelaySeconds", 300L);
        ReflectionTestUtils.setField(asyncTaskService, "runningTimeoutMinutes", 30L);
        ReflectionTestUtils.setField(asyncTaskService, "asyncDispatchLockTtlSeconds", 120L);
        lenient().when(scheduledTaskLockService.runWithLock(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    Runnable action = invocation.getArgument(2, Runnable.class);
                    action.run();
                    return true;
                });
    }

    @Test
    void enqueueUsesStableIdempotencyKey() {
        AsyncTaskEntity persisted = runningTask();
        persisted.setId(42L);
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(asyncTaskMapper.selectByTaskKey(anyString())).thenReturn(persisted);

        asyncTaskService.enqueueImageFeature("file-1", 7L, "file/1.jpg");
        asyncTaskService.enqueueImageFeature("file-1", 7L, "file/1.jpg");

        ArgumentCaptor<AsyncTaskEntity> captor = ArgumentCaptor.forClass(AsyncTaskEntity.class);
        verify(asyncTaskMapper, times(2)).insertIfAbsent(captor.capture());
        List<AsyncTaskEntity> submitted = captor.getAllValues();
        assertEquals("IMAGE_FEATURE:file-1:7:v1", submitted.get(0).getTaskKey());
        assertEquals(submitted.get(0).getTaskKey(), submitted.get(1).getTaskKey());
    }

    @Test
    void adminBatchRetryDeduplicatesIdsAndDispatchesEligibleTasks() {
        AsyncTaskEntity task = dispatchableVideoTask(43L);
        when(asyncTaskMapper.selectRetryableTaskIds(List.of(42L, 43L)))
                .thenReturn(List.of(43L));
        when(asyncTaskMapper.resetForAdminRetry(List.of(43L))).thenReturn(1);
        when(asyncTaskMapper.selectById(43L)).thenReturn(task);
        when(asyncTaskMapper.claim(43L)).thenReturn(1);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0, Runnable.class);
            runnable.run();
            return null;
        }).when(fileTaskExecutor).execute(any(Runnable.class));

        AsyncTaskBatchActionVO result = asyncTaskService.retryTasks(
                List.of(42L, 43L, 42L)
        );

        assertEquals(2, result.getRequestedCount());
        assertEquals(1, result.getUpdatedCount());
        verify(asyncTaskMapper).selectRetryableTaskIds(List.of(42L, 43L));
        verify(asyncTaskMapper).resetForAdminRetry(List.of(43L));
        verify(asyncTaskMapper).claim(43L);
        verify(asyncTaskMapper, never()).claim(42L);
    }

    @Test
    void adminCancelsOnlyDeadTasksThroughMapper() {
        when(asyncTaskMapper.cancelDeadTasks(List.of(42L, 43L))).thenReturn(1);

        AsyncTaskBatchActionVO result = asyncTaskService.cancelDeadTasks(
                List.of(42L, 43L)
        );

        assertEquals(2, result.getRequestedCount());
        assertEquals(1, result.getUpdatedCount());
        verify(asyncTaskMapper).cancelDeadTasks(List.of(42L, 43L));
    }

    @Test
    void adminBatchActionsRejectInvalidIds() {
        assertThrows(
                BusinessException.class,
                () -> asyncTaskService.retryTasks(List.of(0L))
        );
        assertThrows(
                BusinessException.class,
                () -> asyncTaskService.cancelDeadTasks(List.of())
        );
    }

    @Test
    void enqueueFaceAnalysisUsesFaceIdAsIdempotencyKey() {
        AsyncTaskEntity persisted = faceTask();
        persisted.setId(43L);
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(asyncTaskMapper.selectByTaskKey(anyString())).thenReturn(persisted);

        asyncTaskService.enqueueFaceAnalysis(9L, "file-1", 7L);
        asyncTaskService.enqueueFaceAnalysis(9L, "file-1", 7L);

        ArgumentCaptor<AsyncTaskEntity> captor = ArgumentCaptor.forClass(AsyncTaskEntity.class);
        verify(asyncTaskMapper, times(2)).insertIfAbsent(captor.capture());
        List<AsyncTaskEntity> submitted = captor.getAllValues();
        assertEquals("FACE_ANALYSIS:9:v1", submitted.get(0).getTaskKey());
        assertEquals(submitted.get(0).getTaskKey(), submitted.get(1).getTaskKey());
    }

    @Test
    void enqueuePendingFaceAnalysesCreatesReliableTasks() {
        Face face = pendingFace();
        AsyncTaskEntity persisted = faceTask();
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(faceMapper.selectPendingWithoutTask("FACE_ANALYSIS", "v1", 100)).thenReturn(List.of(face));
        when(asyncTaskMapper.selectByTaskKey("FACE_ANALYSIS:9:v1")).thenReturn(persisted);

        int enqueued = asyncTaskService.enqueuePendingFaceAnalyses(100);

        assertEquals(1, enqueued);
        ArgumentCaptor<AsyncTaskEntity> captor = ArgumentCaptor.forClass(AsyncTaskEntity.class);
        verify(asyncTaskMapper).insertIfAbsent(captor.capture());
        assertEquals("FACE_ANALYSIS:9:v1", captor.getValue().getTaskKey());
        assertEquals("file-1", captor.getValue().getFileId());
        assertEquals(7L, captor.getValue().getUserId());
        verify(faceMapper).selectPendingWithoutTask("FACE_ANALYSIS", "v1", 100);
    }

    @Test
    void enqueueVideoProcessingUsesStableIdempotencyKey() {
        AsyncTaskEntity persisted = videoTask();
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(asyncTaskMapper.selectByTaskKey("VIDEO_PROCESSING:video-1:v1")).thenReturn(persisted);

        asyncTaskService.enqueueVideoProcessing("video-1", 7L);
        asyncTaskService.enqueueVideoProcessing("video-1", 7L);

        ArgumentCaptor<AsyncTaskEntity> captor = ArgumentCaptor.forClass(AsyncTaskEntity.class);
        verify(asyncTaskMapper, times(2)).insertIfAbsent(captor.capture());
        assertEquals("VIDEO_PROCESSING:video-1:v1", captor.getAllValues().get(0).getTaskKey());
        assertEquals(captor.getAllValues().get(0).getTaskKey(), captor.getAllValues().get(1).getTaskKey());
    }

    @Test
    void enqueuePendingVideoProcessingCreatesReliableTasks() {
        PendingFileTask pending = new PendingFileTask();
        pending.setFileId("video-1");
        pending.setUserId(7L);
        AsyncTaskEntity persisted = videoTask();
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(fileMapper.selectPendingVideosWithoutTask("VIDEO_PROCESSING", "v1", 50))
                .thenReturn(List.of(pending));
        when(asyncTaskMapper.selectByTaskKey("VIDEO_PROCESSING:video-1:v1")).thenReturn(persisted);

        int enqueued = asyncTaskService.enqueuePendingVideoProcessing(50);

        assertEquals(1, enqueued);
        verify(fileMapper).selectPendingVideosWithoutTask("VIDEO_PROCESSING", "v1", 50);
        verify(asyncTaskMapper).insertIfAbsent(any(AsyncTaskEntity.class));
    }

    @Test
    void enqueueGeocodingUsesCoordinatesInIdempotencyKey() {
        FileEntity file = geocodingFile();
        AsyncTaskEntity persisted = geocodingTask();
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(fileMapper.selectFileByIds(List.of("geo-1"), 7L)).thenReturn(List.of(file));
        when(asyncTaskMapper.selectByTaskKey("GEO_CODING:geo-1:v1:30.100000,120.200000"))
                .thenReturn(persisted);

        asyncTaskService.enqueueGeocoding("geo-1", 7L);

        ArgumentCaptor<AsyncTaskEntity> captor = ArgumentCaptor.forClass(AsyncTaskEntity.class);
        verify(asyncTaskMapper).insertIfAbsent(captor.capture());
        assertEquals("GEO_CODING:geo-1:v1:30.100000,120.200000",
                captor.getValue().getTaskKey());
        assertEquals("{\"latitude\":30.1,\"longitude\":120.2,\"requestId\":null,\"traceId\":null}",
                captor.getValue().getPayloadJson());
    }

    @Test
    void enqueuePendingGeocodingCreatesReliableTasks() {
        PendingFileTask pending = new PendingFileTask();
        pending.setFileId("geo-1");
        pending.setUserId(7L);
        AsyncTaskEntity persisted = geocodingTask();
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(fileMapper.selectPendingGeocodingWithoutTask("GEO_CODING", "v1", 50))
                .thenReturn(List.of(pending));
        when(fileMapper.selectFileByIds(List.of("geo-1"), 7L))
                .thenReturn(List.of(geocodingFile()));
        when(asyncTaskMapper.selectByTaskKey("GEO_CODING:geo-1:v1:30.100000,120.200000"))
                .thenReturn(persisted);

        int enqueued = asyncTaskService.enqueuePendingGeocoding(50);

        assertEquals(1, enqueued);
        verify(fileMapper).selectPendingGeocodingWithoutTask("GEO_CODING", "v1", 50);
        verify(asyncTaskMapper).insertIfAbsent(any(AsyncTaskEntity.class));
    }

    @Test
    void dispatchClaimsTaskBeforeSubmittingToExecutor() {
        AsyncTaskEntity task = dispatchableVideoTask(42L);
        when(asyncTaskMapper.selectById(42L)).thenReturn(task);
        when(asyncTaskMapper.claim(42L)).thenReturn(1);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0, Runnable.class);
            runnable.run();
            return null;
        }).when(fileTaskExecutor).execute(any(Runnable.class));

        asyncTaskService.dispatch(42L);

        verify(asyncTaskMapper).claim(42L);
        verify(fileTaskExecutor).execute(any(Runnable.class));
    }

    @Test
    void dispatchReleasesClaimWhenExecutorRejectsTask() {
        AsyncTaskEntity task = dispatchableVideoTask(42L);
        when(asyncTaskMapper.selectById(42L)).thenReturn(task);
        doThrow(new RejectedExecutionException("full"))
                .when(fileTaskExecutor).execute(any(Runnable.class));

        asyncTaskService.dispatch(42L);

        verify(asyncTaskMapper, never()).claim(42L);
        verify(asyncTaskMapper, never()).releaseClaim(42L);
        verify(asyncTaskMetrics).recordDispatchRejected(AsyncTaskType.VIDEO_PROCESSING.name());
    }

    @Test
    void executeClaimedTaskMarksSuccess() {
        AsyncTaskEntity task = runningTask();
        when(asyncTaskMapper.selectById(42L)).thenReturn(task);
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L)).thenReturn(List.of(imageFile()));

        asyncTaskService.executeClaimedTask(42L);

        verify(fileFeatureService).extractAndSaveFeature("file-1", 7L, "file/1.jpg");
        verify(asyncTaskMapper).markSuccess(42L, null);
        verify(asyncTaskMetrics).recordExecution(
                org.mockito.ArgumentMatchers.eq("IMAGE_FEATURE"),
                org.mockito.ArgumentMatchers.eq("SUCCESS"),
                anyLong()
        );
        verify(asyncTaskMapper, never()).markFailure(anyLong(), any(LocalDateTime.class), anyString());
    }

    @Test
    void executeClaimedTaskPersistsFailureAndRetryTime() {
        AsyncTaskEntity task = runningTask();
        when(asyncTaskMapper.selectById(42L)).thenReturn(task);
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L)).thenReturn(List.of(imageFile()));
        doThrow(new IllegalStateException("AI unavailable"))
                .when(fileFeatureService).extractAndSaveFeature("file-1", 7L, "file/1.jpg");

        asyncTaskService.executeClaimedTask(42L);

        ArgumentCaptor<LocalDateTime> retryTime = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(asyncTaskMapper).markFailure(org.mockito.ArgumentMatchers.eq(42L), retryTime.capture(), error.capture());
        verify(asyncTaskMapper, never()).markSuccess(org.mockito.ArgumentMatchers.eq(42L), any());
        verify(asyncTaskMetrics).recordRetry("IMAGE_FEATURE");
        verify(asyncTaskMetrics).recordExecution(
                org.mockito.ArgumentMatchers.eq("IMAGE_FEATURE"),
                org.mockito.ArgumentMatchers.eq("RETRY"),
                anyLong()
        );
        assertTrue(retryTime.getValue().isAfter(LocalDateTime.now()));
        assertEquals("IllegalStateException: AI unavailable", error.getValue());
    }

    @Test
    void executeClaimedTaskRejectsFileNoLongerOwnedByUser() {
        AsyncTaskEntity task = runningTask();
        when(asyncTaskMapper.selectById(42L)).thenReturn(task);
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L)).thenReturn(List.of());

        asyncTaskService.executeClaimedTask(42L);

        verify(fileFeatureService, never()).extractAndSaveFeature(anyString(), anyLong(), anyString());
        verify(asyncTaskMapper).markDead(org.mockito.ArgumentMatchers.eq(42L), anyString());
        verify(asyncTaskMetrics).recordExecution(
                org.mockito.ArgumentMatchers.eq("IMAGE_FEATURE"),
                org.mockito.ArgumentMatchers.eq("DEAD"),
                anyLong()
        );
        verify(asyncTaskMapper, never()).markFailure(anyLong(), any(LocalDateTime.class), anyString());
    }

    @Test
    void executeFaceAnalysisProcessesLatestFaceRecord() {
        AsyncTaskEntity task = faceTask();
        Face face = pendingFace();
        when(asyncTaskMapper.selectById(43L)).thenReturn(task);
        when(faceMapper.selectById(9L)).thenReturn(face);
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L)).thenReturn(List.of(imageFile()));

        asyncTaskService.executeClaimedTask(43L);

        verify(faceMapper, times(2)).selectById(9L);
        verify(faceService).processOneFace(face);
        verify(asyncTaskMapper).markSuccess(43L, null);
    }

    @Test
    void executeFaceAnalysisSkipsAlreadyProcessedFace() {
        AsyncTaskEntity task = faceTask();
        Face face = pendingFace();
        face.setIsProcessed(true);
        when(asyncTaskMapper.selectById(43L)).thenReturn(task);
        when(faceMapper.selectById(9L)).thenReturn(face);
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L)).thenReturn(List.of(imageFile()));

        asyncTaskService.executeClaimedTask(43L);

        verify(faceService, never()).processOneFace(any(Face.class));
        verify(asyncTaskMapper).markSuccess(43L, null);
    }

    @Test
    void executeFaceAnalysisPersistsRetryableFailure() {
        AsyncTaskEntity task = faceTask();
        Face face = pendingFace();
        when(asyncTaskMapper.selectById(43L)).thenReturn(task);
        when(faceMapper.selectById(9L)).thenReturn(face);
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L)).thenReturn(List.of(imageFile()));
        doThrow(new IllegalStateException("face API unavailable"))
                .when(faceService).processOneFace(face);

        asyncTaskService.executeClaimedTask(43L);

        verify(asyncTaskMapper).markFailure(
                org.mockito.ArgumentMatchers.eq(43L),
                any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.contains("face API unavailable")
        );
        verify(asyncTaskMapper, never()).markSuccess(org.mockito.ArgumentMatchers.eq(43L), any());
    }

    @Test
    void executeFaceAnalysisRejectsMismatchedOwner() {
        AsyncTaskEntity task = faceTask();
        Face face = pendingFace();
        face.setUserId(8L);
        when(asyncTaskMapper.selectById(43L)).thenReturn(task);
        when(faceMapper.selectById(9L)).thenReturn(face);

        asyncTaskService.executeClaimedTask(43L);

        verify(faceService, never()).processOneFace(any(Face.class));
        verify(asyncTaskMapper).markDead(
                org.mockito.ArgumentMatchers.eq(43L),
                org.mockito.ArgumentMatchers.contains("Face record")
        );
    }

    @Test
    void executeVideoProcessingDelegatesToIdempotentProcessor() {
        AsyncTaskEntity task = videoTask();
        FileEntity video = videoFile();
        when(asyncTaskMapper.selectById(44L)).thenReturn(task);
        when(fileMapper.selectFileByIds(List.of("video-1"), 7L)).thenReturn(List.of(video));

        asyncTaskService.executeClaimedTask(44L);

        verify(videoPostProcessingService).process(video);
        verify(asyncTaskMapper).markSuccess(44L, null);
    }

    @Test
    void executeVideoProcessingPersistsRetryableFailure() {
        AsyncTaskEntity task = videoTask();
        FileEntity video = videoFile();
        when(asyncTaskMapper.selectById(44L)).thenReturn(task);
        when(fileMapper.selectFileByIds(List.of("video-1"), 7L)).thenReturn(List.of(video));
        doThrow(new IllegalStateException("ffmpeg unavailable"))
                .when(videoPostProcessingService).process(video);

        asyncTaskService.executeClaimedTask(44L);

        verify(asyncTaskMapper).markFailure(
                org.mockito.ArgumentMatchers.eq(44L),
                any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.contains("ffmpeg unavailable")
        );
        verify(asyncTaskMapper, never()).markSuccess(org.mockito.ArgumentMatchers.eq(44L), any());
    }

    @Test
    void executeGeocodingUsesCurrentOwnedCoordinates() {
        AsyncTaskEntity task = geocodingTask();
        FileEntity file = geocodingFile();
        when(asyncTaskMapper.selectById(45L)).thenReturn(task);
        when(fileMapper.selectGeocodingFile("geo-1")).thenReturn(file);

        asyncTaskService.executeClaimedTask(45L);

        verify(locationService).processCoordinates(file);
        verify(asyncTaskMapper).markSuccess(45L, null);
    }

    @Test
    void executeGeocodingSkipsStaleCoordinates() {
        AsyncTaskEntity task = geocodingTask();
        FileEntity file = geocodingFile();
        file.setLatitude(31.0D);
        when(asyncTaskMapper.selectById(45L)).thenReturn(task);
        when(fileMapper.selectGeocodingFile("geo-1")).thenReturn(file);

        asyncTaskService.executeClaimedTask(45L);

        verify(locationService, never()).processCoordinates(any(FileEntity.class));
        verify(asyncTaskMapper).markSuccess(45L, null);
    }

    @Test
    void executeGeocodingPersistsRetryableFailure() {
        AsyncTaskEntity task = geocodingTask();
        FileEntity file = geocodingFile();
        when(asyncTaskMapper.selectById(45L)).thenReturn(task);
        when(fileMapper.selectGeocodingFile("geo-1")).thenReturn(file);
        doThrow(new IllegalStateException("map API unavailable"))
                .when(locationService).processCoordinates(file);

        asyncTaskService.executeClaimedTask(45L);

        verify(asyncTaskMapper).markFailure(
                org.mockito.ArgumentMatchers.eq(45L),
                any(LocalDateTime.class),
                org.mockito.ArgumentMatchers.contains("map API unavailable")
        );
        verify(asyncTaskMapper, never()).markSuccess(org.mockito.ArgumentMatchers.eq(45L), any());
    }

    @Test
    void executeGeocodingRejectsFileWithoutActiveOwner() {
        AsyncTaskEntity task = geocodingTask();
        when(asyncTaskMapper.selectById(45L)).thenReturn(task);
        when(fileMapper.selectGeocodingFile("geo-1")).thenReturn(null);

        asyncTaskService.executeClaimedTask(45L);

        verify(locationService, never()).processCoordinates(any(FileEntity.class));
        verify(asyncTaskMapper).markDead(
                org.mockito.ArgumentMatchers.eq(45L),
                org.mockito.ArgumentMatchers.contains("active owner")
        );
    }

    @Test
    void enqueueImageTagUsesModeAndVersionInIdempotencyKey() {
        AsyncTaskEntity persisted = imageTagTask(true);
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L))
                .thenReturn(List.of(imageFile()));
        when(asyncTaskMapper.selectByTaskKey("IMAGE_TAG:file-1:7:v1:auto"))
                .thenReturn(persisted);

        Long taskId = asyncTaskService.enqueueImageTag("file-1", 7L, true);

        assertEquals(46L, taskId);
        ArgumentCaptor<AsyncTaskEntity> captor = ArgumentCaptor.forClass(AsyncTaskEntity.class);
        verify(asyncTaskMapper).insertIfAbsent(captor.capture());
        assertEquals("IMAGE_TAG:file-1:7:v1:auto", captor.getValue().getTaskKey());
        assertEquals("{\"autoAddTag\":true,\"requestId\":null,\"traceId\":null}", captor.getValue().getPayloadJson());
    }

    @Test
    void enqueuePendingImageTagsCreatesAutoAddTasks() {
        PendingFileTask pending = new PendingFileTask();
        pending.setFileId("file-1");
        pending.setUserId(7L);
        AsyncTaskEntity persisted = imageTagTask(true);
        persisted.setStatus(AsyncTaskStatus.PENDING.name());
        when(fileMapper.selectPendingImageTagsWithoutTask("IMAGE_TAG", "v1", 25))
                .thenReturn(List.of(pending));
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L))
                .thenReturn(List.of(imageFile()));
        when(asyncTaskMapper.selectByTaskKey("IMAGE_TAG:file-1:7:v1:auto"))
                .thenReturn(persisted);

        int enqueued = asyncTaskService.enqueuePendingImageTags(25);

        assertEquals(1, enqueued);
        verify(fileMapper).selectPendingImageTagsWithoutTask("IMAGE_TAG", "v1", 25);
        verify(asyncTaskMapper).insertIfAbsent(any(AsyncTaskEntity.class));
    }

    @Test
    void dispatchImageTagUsesAiExecutor() {
        AsyncTaskEntity task = imageTagTask(false);
        task.setStatus(AsyncTaskStatus.PENDING.name());
        when(asyncTaskMapper.selectById(46L)).thenReturn(task);
        doAnswer(invocation -> null).when(aiBatchTaskExecutor).execute(any(Runnable.class));

        asyncTaskService.dispatch(46L);

        verify(aiBatchTaskExecutor).execute(any(Runnable.class));
        verify(fileTaskExecutor, never()).execute(any(Runnable.class));
    }

    @Test
    void enqueueImageTagRequeuesStaleRunningTask() {
        AsyncTaskEntity task = imageTagTask(false);
        task.setStartedAt(LocalDateTime.now().minusSeconds(181));
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L))
                .thenReturn(List.of(imageFile()));
        when(asyncTaskMapper.selectByTaskKey("IMAGE_TAG:file-1:7:v1:preview"))
                .thenReturn(task);
        when(asyncTaskMapper.releaseClaim(46L)).thenReturn(1);
        when(asyncTaskMapper.selectById(46L)).thenReturn(task);
        doAnswer(invocation -> null).when(aiBatchTaskExecutor).execute(any(Runnable.class));

        Long taskId = asyncTaskService.enqueueImageTag("file-1", 7L, false);

        assertEquals(46L, taskId);
        verify(asyncTaskMapper).releaseClaim(46L);
        verify(aiBatchTaskExecutor).execute(any(Runnable.class));
    }

    @Test
    void executeImageTagPersistsResultAndAutoAddsFirstTag() throws Exception {
        AsyncTaskEntity task = imageTagTask(true);
        FileEntity file = imageFile();
        file.setThumbnailObjectName("thumbnail/file-1.jpg");
        when(asyncTaskMapper.selectById(46L)).thenReturn(task);
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L)).thenReturn(List.of(file));
        when(imageTagUtil.classifyImage("object_key", "thumbnail/file-1.jpg"))
                .thenReturn(List.of(
                        new TagResult("人物", "人像", 92.0),
                        new TagResult("场景", "室内", 80.0)
                ));

        asyncTaskService.executeClaimedTask(46L);

        verify(pictureTagMapper).insertIfAbsent("file-1", "人物", "人像");
        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(asyncTaskMapper).markSuccess(
                org.mockito.ArgumentMatchers.eq(46L),
                resultJson.capture()
        );
        assertEquals("人像", objectMapper.readTree(resultJson.getValue()).get(0).get("tagName").asText());
    }

    @Test
    void executeImageTagPreviewDoesNotWritePictureTag() {
        AsyncTaskEntity task = imageTagTask(false);
        when(asyncTaskMapper.selectById(46L)).thenReturn(task);
        when(fileMapper.selectFileByIds(List.of("file-1"), 7L))
                .thenReturn(List.of(imageFile()));
        when(imageTagUtil.classifyImage("object_key", "file/1.jpg"))
                .thenReturn(List.of(new TagResult("人物", "人像", 92.0)));

        asyncTaskService.executeClaimedTask(46L);

        verify(pictureTagMapper, never()).insertIfAbsent(anyString(), anyString(), anyString());
        verify(asyncTaskMapper).markSuccess(
                org.mockito.ArgumentMatchers.eq(46L),
                org.mockito.ArgumentMatchers.contains("\"tagName\":\"人像\"")
        );
    }

    @Test
    void getUserTaskReturnsParsedResult() {
        AsyncTaskEntity task = imageTagTask(false);
        task.setStatus(AsyncTaskStatus.SUCCESS.name());
        task.setResultJson("[{\"imageType\":\"人物\",\"tagName\":\"人像\",\"confidence\":92.0}]");
        when(asyncTaskMapper.selectOne(any(QueryWrapper.class))).thenReturn(task);

        AsyncTaskVO result = asyncTaskService.getUserTask(46L, 7L);

        assertNotNull(result.getResult());
        assertEquals("人像", result.getResult().get(0).get("tagName").asText());
    }

    @Test
    void retryRejectsTasksNotOwnedByUserOrNotRetryable() {
        when(asyncTaskMapper.resetForManualRetry(42L, 7L)).thenReturn(0);

        assertThrows(BusinessException.class, () -> asyncTaskService.retryTask(42L, 7L));
    }

    @Test
    void retryResetsAndDispatchesTask() {
        AsyncTaskEntity task = dispatchableVideoTask(42L);
        when(asyncTaskMapper.resetForManualRetry(42L, 7L)).thenReturn(1);
        when(asyncTaskMapper.selectById(42L)).thenReturn(task);
        when(asyncTaskMapper.claim(42L)).thenReturn(1);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0, Runnable.class);
            runnable.run();
            return null;
        }).when(fileTaskExecutor).execute(any(Runnable.class));

        asyncTaskService.retryTask(42L, 7L);

        verify(asyncTaskMapper).resetForManualRetry(42L, 7L);
        verify(fileTaskExecutor).execute(any(Runnable.class));
        verify(asyncTaskMapper).claim(42L);
    }

    @Test
    void startupRecoversStaleAndDueTasks() {
        AsyncTaskEntity task = dispatchableVideoTask(42L);
        when(asyncTaskMapper.recoverStaleRunning(any(LocalDateTime.class))).thenReturn(1);
        when(asyncTaskMapper.selectDueTaskIds(50)).thenReturn(List.of(42L));
        when(asyncTaskMapper.selectById(42L)).thenReturn(task);
        when(asyncTaskMapper.claim(42L)).thenReturn(1);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0, Runnable.class);
            runnable.run();
            return null;
        }).when(fileTaskExecutor).execute(any(Runnable.class));

        asyncTaskService.recoverTasksOnStartup();

        verify(asyncTaskMapper).recoverStaleRunning(any(LocalDateTime.class));
        verify(asyncTaskMapper).selectDueTaskIds(50);
        verify(asyncTaskMetrics).recordStaleRecovered(1);
        verify(fileTaskExecutor).execute(any(Runnable.class));
    }

    private AsyncTaskEntity dispatchableVideoTask(Long id) {
        AsyncTaskEntity task = videoTask();
        task.setId(id);
        task.setStatus(AsyncTaskStatus.PENDING.name());
        task.setNextRetryTime(null);
        return task;
    }

    private AsyncTaskEntity runningTask() {
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setId(42L);
        task.setTaskType(AsyncTaskType.IMAGE_FEATURE.name());
        task.setUserId(7L);
        task.setFileId("file-1");
        task.setPayloadJson("{\"objectName\":\"file/1.jpg\"}");
        task.setStatus(AsyncTaskStatus.RUNNING.name());
        task.setRetryCount(0);
        task.setMaxRetries(5);
        return task;
    }

    private AsyncTaskEntity faceTask() {
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setId(43L);
        task.setTaskType(AsyncTaskType.FACE_ANALYSIS.name());
        task.setUserId(7L);
        task.setFileId("file-1");
        task.setPayloadJson("{\"faceId\":9}");
        task.setStatus(AsyncTaskStatus.RUNNING.name());
        task.setRetryCount(0);
        task.setMaxRetries(5);
        return task;
    }

    private Face pendingFace() {
        Face face = new Face();
        face.setFaceId(9L);
        face.setFileId("file-1");
        face.setUserId(7L);
        face.setIsProcessed(false);
        return face;
    }

    private AsyncTaskEntity videoTask() {
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setId(44L);
        task.setTaskType(AsyncTaskType.VIDEO_PROCESSING.name());
        task.setUserId(7L);
        task.setFileId("video-1");
        task.setStatus(AsyncTaskStatus.RUNNING.name());
        task.setRetryCount(0);
        task.setMaxRetries(5);
        return task;
    }

    private FileEntity imageFile() {
        FileEntity file = new FileEntity();
        file.setFileId("file-1");
        file.setCategory("image");
        file.setFileObjectName("file/1.jpg");
        return file;
    }

    private FileEntity videoFile() {
        FileEntity file = new FileEntity();
        file.setFileId("video-1");
        file.setCategory("video");
        file.setFileObjectName("file/video-1.mp4");
        return file;
    }

    private AsyncTaskEntity geocodingTask() {
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setId(45L);
        task.setTaskType(AsyncTaskType.GEO_CODING.name());
        task.setUserId(7L);
        task.setFileId("geo-1");
        task.setPayloadJson("{\"latitude\":30.1,\"longitude\":120.2}");
        task.setStatus(AsyncTaskStatus.RUNNING.name());
        task.setRetryCount(0);
        task.setMaxRetries(5);
        return task;
    }

    private AsyncTaskEntity imageTagTask(boolean autoAddTag) {
        AsyncTaskEntity task = new AsyncTaskEntity();
        task.setId(46L);
        task.setTaskType(AsyncTaskType.IMAGE_TAG.name());
        task.setUserId(7L);
        task.setFileId("file-1");
        task.setPayloadJson("{\"autoAddTag\":" + autoAddTag + "}");
        task.setStatus(AsyncTaskStatus.RUNNING.name());
        task.setRetryCount(0);
        task.setMaxRetries(5);
        return task;
    }

    private FileEntity geocodingFile() {
        FileEntity file = new FileEntity();
        file.setFileId("geo-1");
        file.setCategory("image");
        file.setLatitude(30.1D);
        file.setLongitude(120.2D);
        return file;
    }
}
