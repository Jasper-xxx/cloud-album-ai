package com.memory.xzp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.service.AsyncTaskService;
import com.memory.xzp.model.vo.picture.BatchGetPictureTagResponseVO;
import com.memory.xzp.model.vo.task.ImageTagTaskVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileServiceImplInfrastructureTest {

    @Test
    void shareFileIdsRoundTripThroughJackson() {
        FileServiceImpl service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        String json = ReflectionTestUtils.invokeMethod(
                service,
                "serializeFileIds",
                List.of("file-1", "file-2")
        );
        List<String> parsed = ReflectionTestUtils.invokeMethod(service, "parseFileIds", json);

        assertEquals(List.of("file-1", "file-2"), parsed);
    }

    @Test
    void rejectedBackgroundTaskDoesNotEscapeToRequestThread() {
        FileServiceImpl service = new FileServiceImpl();
        ReflectionTestUtils.setField(service, "fileTaskExecutor", rejectingExecutor());

        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                service,
                "submitFileTask",
                "test-task",
                (Runnable) () -> {
                }
        ));
    }

    @Test
    void pictureTagRequestSubmitsPersistentTask() {
        FileServiceImpl service = new FileServiceImpl();
        AsyncTaskService asyncTaskService = mock(AsyncTaskService.class);
        when(asyncTaskService.enqueueImageTag("file-1", 7L, true)).thenReturn(51L);
        ReflectionTestUtils.setField(service, "asyncTaskService", asyncTaskService);

        ImageTagTaskVO result = service.getPictureTag(
                "file-1",
                "untrusted/other-user.jpg",
                true,
                7L
        );

        assertEquals(51L, result.getTaskId());
        assertEquals("file-1", result.getFileId());
        verify(asyncTaskService).enqueueImageTag("file-1", 7L, true);
    }

    @Test
    void batchPictureTagRequestReturnsSubmissionPerFile() {
        FileServiceImpl service = new FileServiceImpl();
        AsyncTaskService asyncTaskService = mock(AsyncTaskService.class);
        when(asyncTaskService.enqueueImageTag("file-1", 7L, false)).thenReturn(51L);
        when(asyncTaskService.enqueueImageTag("file-2", 7L, false)).thenReturn(52L);
        ReflectionTestUtils.setField(service, "asyncTaskService", asyncTaskService);

        BatchGetPictureTagResponseVO response = service.batchGetPictureTag(
                List.of("file-1", "file-2"),
                false,
                7L
        );

        assertEquals(2, response.getItems().size());
        assertEquals(51L, response.getItems().get(0).getTaskId());
        assertEquals(52L, response.getItems().get(1).getTaskId());
        assertEquals(2, response.getStatistics().getSuccess());
    }

    private Executor rejectingExecutor() {
        return command -> {
            throw new RejectedExecutionException("queue full");
        };
    }
}
