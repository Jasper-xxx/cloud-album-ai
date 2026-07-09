package com.memory.xzp.controller;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.service.AlbumService;
import com.memory.xzp.service.FileService;
import com.memory.xzp.service.RecordService;
import com.memory.xzp.service.SimilarDetectService;
import com.memory.xzp.utils.auth.RedisUtil;
import com.memory.xzp.utils.file.FileUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock
    private FileUtil fileUtil;
    @Mock
    private FileService fileService;
    @Mock
    private RecordService recordService;
    @Mock
    private RedisUtil redisUtil;
    @Mock
    private AlbumService albumService;
    @Mock
    private SimilarDetectService similarDetectService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    @InjectMocks
    private FileController fileController;

    @Test
    void rejectsMissingDownloadTokenBeforeReadingRedis() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> fileController.downloadFileByToken(request, response, " ")
        );

        assertEquals(StatusCode.PARAMS_ERROR.getCode(), exception.getCode());
    }

    @Test
    void rejectsExpiredDownloadToken() {
        when(redisUtil.get("tempDownload:expired")).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> fileController.downloadFileByToken(request, response, "expired")
        );

        assertEquals(StatusCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
    }
}
