package com.memory.xzp.service;

import com.memory.xzp.config.MinIOConfig;
import com.memory.xzp.config.UploadPolicy;
import com.memory.xzp.metrics.BusinessMetrics;
import com.memory.xzp.model.dto.upload.MultipartUploadInitRequest;
import com.memory.xzp.model.dto.upload.MultipartUploadInitResponse;
import com.memory.xzp.utils.file.MinioOSSUtil;
import com.memory.xzp.utils.file.MultipartMinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MultipartUploadServiceTest {

    @Test
    void existingOwnedFileUsesInstantUploadWithoutReservingStorage() {
        MultipartMinioClient minioClient = mock(MultipartMinioClient.class);
        MinIOConfig minIOConfig = mock(MinIOConfig.class);
        UploadPolicy uploadPolicy = mock(UploadPolicy.class);
        UploadSecurityValidator uploadSecurityValidator = mock(UploadSecurityValidator.class);
        StorageQuotaService quotaService = mock(StorageQuotaService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        MinioOSSUtil minioOSSUtil = mock(MinioOSSUtil.class);
        FileService fileService = mock(FileService.class);
        AlbumService albumService = mock(AlbumService.class);
        ExternalServiceExecutor externalServiceExecutor = mock(ExternalServiceExecutor.class);
        BusinessMetrics businessMetrics = mock(BusinessMetrics.class);
        ScheduledTaskLockService scheduledTaskLockService = mock(ScheduledTaskLockService.class);
        MultipartUploadService service = new MultipartUploadService(
                minioClient,
                minIOConfig,
                uploadPolicy,
                uploadSecurityValidator,
                quotaService,
                redisTemplate,
                minioOSSUtil,
                fileService,
                albumService,
                externalServiceExecutor,
                businessMetrics,
                scheduledTaskLockService
        );
        MultipartUploadInitRequest request = new MultipartUploadInitRequest();
        request.setFileName("photo.jpg");
        request.setFileSize(1024L);
        request.setContentType("image/jpeg");
        request.setAlbumId(-1L);
        request.setMd5("0123456789abcdef0123456789abcdef");
        request.setSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        when(uploadPolicy.validate("photo.jpg", "image/jpeg", 1024L))
                .thenReturn(new UploadPolicy.ValidatedUpload("jpg", "image", "image/jpeg"));
        when(fileService.reuseOwnedFileIfPresent(
                7L,
                -1L,
                "0123456789abcdef0123456789abcdef"
        )).thenReturn("existing-file-id");

        MultipartUploadInitResponse response = service.initialize(7L, request);

        assertTrue(response.isInstantUpload());
        assertEquals("existing-file-id", response.getFileId());
        assertTrue(response.getParts().isEmpty());
        verifyNoInteractions(quotaService, redisTemplate, minioClient, minioOSSUtil);
    }
}
