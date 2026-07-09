package com.memory.xzp.service;

import com.memory.xzp.config.MinIOConfig;
import com.memory.xzp.config.UploadPolicy;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.metrics.BusinessMetrics;
import com.memory.xzp.model.dto.upload.DirectUploadRegistration;
import com.memory.xzp.model.dto.upload.MultipartUploadRefreshRequest;
import com.memory.xzp.model.dto.upload.MultipartUploadInitRequest;
import com.memory.xzp.model.dto.upload.MultipartUploadInitResponse;
import com.memory.xzp.utils.file.MinioOSSUtil;
import com.memory.xzp.utils.file.MultipartMinioClient;
import io.minio.StatObjectArgs;
import io.minio.messages.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
@Slf4j
public class MultipartUploadService {

    private static final Pattern MD5_PATTERN = Pattern.compile("^[a-fA-F0-9]{32}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final String SESSION_PREFIX = "upload:multipart:";
    private static final String COMPLETED_PREFIX = "upload:multipart:completed:";
    private static final String EXPIRY_KEY = "upload:multipart:expiry";
    private static final String DEDUP_LOCK_PREFIX = "upload:multipart:dedup:";
    private static final long COMPLETED_SESSION_TTL_SECONDS = 24 * 60 * 60;
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """,
            Long.class
    );

    private final MultipartMinioClient minioClient;
    private final MinIOConfig minIOConfig;
    private final UploadPolicy uploadPolicy;
    private final UploadSecurityValidator uploadSecurityValidator;
    private final StorageQuotaService quotaService;
    private final StringRedisTemplate redisTemplate;
    private final MinioOSSUtil minioOSSUtil;
    private final FileService fileService;
    private final AlbumService albumService;
    private final ExternalServiceExecutor externalServiceExecutor;
    private final BusinessMetrics businessMetrics;
    private final ScheduledTaskLockService scheduledTaskLockService;

    @Value("${upload.multipart.part-size:16777216}")
    private long partSize;

    @Value("${upload.multipart.session-ttl-seconds:7200}")
    private long sessionTtlSeconds;

    @Value("${upload.multipart.presigned-url-ttl-seconds:1800}")
    private int presignedUrlTtlSeconds;

    @Value("${app.scheduler-lock.multipart-cleanup-ttl-seconds:600}")
    private long multipartCleanupLockTtlSeconds;

    public MultipartUploadService(
            MultipartMinioClient minioClient,
            MinIOConfig minIOConfig,
            UploadPolicy uploadPolicy,
            UploadSecurityValidator uploadSecurityValidator,
            StorageQuotaService quotaService,
            StringRedisTemplate redisTemplate,
            MinioOSSUtil minioOSSUtil,
            FileService fileService,
            AlbumService albumService,
            ExternalServiceExecutor externalServiceExecutor,
            BusinessMetrics businessMetrics,
            ScheduledTaskLockService scheduledTaskLockService
    ) {
        this.minioClient = minioClient;
        this.minIOConfig = minIOConfig;
        this.uploadPolicy = uploadPolicy;
        this.uploadSecurityValidator = uploadSecurityValidator;
        this.quotaService = quotaService;
        this.redisTemplate = redisTemplate;
        this.minioOSSUtil = minioOSSUtil;
        this.fileService = fileService;
        this.albumService = albumService;
        this.externalServiceExecutor = externalServiceExecutor;
        this.businessMetrics = businessMetrics;
        this.scheduledTaskLockService = scheduledTaskLockService;
    }

    public MultipartUploadInitResponse initialize(Long userId, MultipartUploadInitRequest request) {
        if (request == null || request.getFileSize() == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Upload parameters cannot be empty");
        }
        UploadPolicy.ValidatedUpload validated = uploadPolicy.validate(
                request.getFileName(),
                request.getContentType(),
                request.getFileSize()
        );
        String mediaType = mediaTypeFor(validated.contentType());
        businessMetrics.recordUploadLifecycle("init", "multipart", mediaType, "accepted");
        businessMetrics.recordUploadBytes("multipart", mediaType, request.getFileSize());
        String md5 = normalizeMd5(request.getMd5());
        String sha256 = normalizeSha256(request.getSha256());
        Long albumId = request.getAlbumId() == null ? -1L : request.getAlbumId();
        if (albumId != -1 && albumService.selectAlbumById(albumId, userId) == null) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "Album does not exist or is not accessible");
        }

        String existingFileId = fileService.reuseOwnedFileIfPresent(userId, albumId, md5);
        if (existingFileId != null) {
            businessMetrics.recordUploadLifecycle("instant", "multipart", mediaType, "success");
            return instantUploadResponse(existingFileId);
        }

        long effectivePartSize = Math.max(partSize, 5L * 1024 * 1024);
        int partCount = Math.toIntExact((request.getFileSize() + effectivePartSize - 1) / effectivePartSize);
        if (partCount < 1 || partCount > 10_000) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Multipart count exceeds MinIO limit");
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");
        String dedupLockKey = dedupLockKey(userId, md5, sha256);
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(
                dedupLockKey,
                sessionId,
                sessionTtlSeconds + 3600,
                TimeUnit.SECONDS
        );
        if (!Boolean.TRUE.equals(locked)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "The same file is already uploading");
        }

        String objectName = "file/" + LocalDate.now() + "/" + userId + "/" + sessionId + "." + validated.suffix();
        String uploadId = null;
        try {
            existingFileId = fileService.reuseOwnedFileIfPresent(userId, albumId, md5);
            if (existingFileId != null) {
                releaseDedupLock(userId, md5, sha256, sessionId);
                businessMetrics.recordUploadLifecycle("instant", "multipart", mediaType, "success");
                return instantUploadResponse(existingFileId);
            }
            quotaService.reserve(userId, request.getFileSize(), sessionId);
            minioOSSUtil.ensureBucket();
            uploadId = externalServiceExecutor.execute(
                    ExternalServiceExecutor.MINIO,
                    () -> minioClient.createUpload(
                            minIOConfig.getBucketName(),
                            objectName,
                            validated.contentType()
                    )
            );
            List<MultipartUploadInitResponse.PartUploadUrl> parts = new ArrayList<>(partCount);
            long urlsExpireAt = urlsExpireAt();
            for (int partNumber = 1; partNumber <= partCount; partNumber++) {
                parts.add(presignPart(objectName, uploadId, partNumber, urlsExpireAt));
            }

            redisTemplate.opsForHash().putAll(sessionKey(sessionId), Map.ofEntries(
                    Map.entry("userId", String.valueOf(userId)),
                    Map.entry("uploadId", uploadId),
                    Map.entry("objectName", objectName),
                    Map.entry("fileName", request.getFileName()),
                    Map.entry("fileSize", String.valueOf(request.getFileSize())),
                    Map.entry("contentType", validated.contentType()),
                    Map.entry("md5", md5),
                    Map.entry("sha256", sha256),
                    Map.entry("lastModified", String.valueOf(
                            request.getLastModified() == null ? System.currentTimeMillis() : request.getLastModified()
                    )),
                    Map.entry("albumId", String.valueOf(albumId)),
                    Map.entry("partSize", String.valueOf(effectivePartSize)),
                    Map.entry("partCount", String.valueOf(partCount))
            ));
            redisTemplate.expire(sessionKey(sessionId), sessionTtlSeconds + 3600, TimeUnit.SECONDS);
            redisTemplate.opsForZSet().add(
                    EXPIRY_KEY,
                    sessionId,
                    System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(sessionTtlSeconds)
            );

            UploadSession session = new UploadSession(
                    uploadId,
                    objectName,
                    request.getFileName(),
                    request.getFileSize(),
                    validated.contentType(),
                    md5,
                    sha256,
                    request.getLastModified() == null ? System.currentTimeMillis() : request.getLastModified(),
                    albumId,
                    effectivePartSize,
                    partCount
            );
            businessMetrics.recordUploadLifecycle("initialized", "multipart", mediaType, "success");
            return buildSessionResponse(sessionId, session, parts, List.of(), urlsExpireAt);
        } catch (Exception e) {
            businessMetrics.recordUploadLifecycle("init", "multipart", mediaType, "failed");
            abortQuietly(objectName, uploadId);
            quotaService.release(userId, sessionId);
            redisTemplate.delete(sessionKey(sessionId));
            redisTemplate.opsForZSet().remove(EXPIRY_KEY, sessionId);
            releaseDedupLock(userId, md5, sha256, sessionId);
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "Failed to initialize multipart upload");
        }
    }

    public MultipartUploadInitResponse status(Long userId, String sessionId) {
        String completedFileId = completedFileId(sessionId);
        if (completedFileId != null) {
            MultipartUploadInitResponse response = instantUploadResponse(completedFileId);
            response.setCompleted(true);
            return response;
        }
        UploadSession session = getOwnedSession(userId, sessionId);
        extendCleanupDeadline(sessionId);
        List<Integer> uploadedParts = uploadedPartNumbers(session);
        return buildSessionResponse(sessionId, session, List.of(), uploadedParts, null);
    }

    public MultipartUploadInitResponse refreshUploadUrls(Long userId, MultipartUploadRefreshRequest request) {
        if (request == null || request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Upload session cannot be empty");
        }
        UploadSession session = getOwnedSession(userId, request.getSessionId());
        extendCleanupDeadline(request.getSessionId());
        List<Integer> uploadedParts = uploadedPartNumbers(session);
        Set<Integer> uploaded = new HashSet<>(uploadedParts);
        List<Integer> requested = request.getPartNumbers();
        if (requested == null || requested.isEmpty()) {
            requested = new ArrayList<>();
            for (int partNumber = 1; partNumber <= session.partCount(); partNumber++) {
                if (!uploaded.contains(partNumber)) {
                    requested.add(partNumber);
                }
            }
        }

        long urlsExpireAt = urlsExpireAt();
        List<MultipartUploadInitResponse.PartUploadUrl> refreshed = new ArrayList<>();
        for (Integer partNumber : requested) {
            if (partNumber == null || partNumber < 1 || partNumber > session.partCount()) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Invalid multipart number");
            }
            if (!uploaded.contains(partNumber)) {
                refreshed.add(presignPart(session.objectName(), session.uploadId(), partNumber, urlsExpireAt));
            }
        }
        return buildSessionResponse(request.getSessionId(), session, refreshed, uploadedParts, urlsExpireAt);
    }

    public String complete(Long userId, String sessionId) {
        String completedFileId = completedFileId(sessionId);
        if (completedFileId != null) {
            return completedFileId;
        }
        UploadSession session = getOwnedSession(userId, sessionId);
        String mediaType = mediaTypeFor(session.contentType());
        extendCleanupDeadline(sessionId);
        boolean objectCompleted = false;
        boolean registered = false;
        try {
            List<Part> parts = externalServiceExecutor.execute(
                            ExternalServiceExecutor.MINIO,
                            () -> minioClient.listUploadedParts(
                                    minIOConfig.getBucketName(),
                                    session.objectName(),
                                    session.uploadId()
                            )
                    ).stream()
                    .sorted(Comparator.comparingInt(Part::partNumber))
                    .toList();
            validateUploadedParts(parts, session);

            externalServiceExecutor.run(
                    ExternalServiceExecutor.MINIO,
                    () -> minioClient.completeUpload(
                            minIOConfig.getBucketName(),
                            session.objectName(),
                            session.uploadId(),
                            parts
                    )
            );
            objectCompleted = true;

            long objectSize = externalServiceExecutor.execute(
                    ExternalServiceExecutor.MINIO,
                    () -> minioClient.statObject(
                            StatObjectArgs.builder()
                                    .bucket(minIOConfig.getBucketName())
                                    .object(session.objectName())
                                    .build()
                    ).get().size()
            );
            if (objectSize != session.fileSize()) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Object size validation failed");
            }

            UploadPolicy.ValidatedUpload validated = uploadPolicy.validate(
                    session.fileName(),
                    session.contentType(),
                    session.fileSize()
            );
            uploadSecurityValidator.validateObject(session.objectName(), validated, session.fileSize());

            String existingFileId = fileService.reuseOwnedFileIfPresent(
                    userId,
                    session.albumId(),
                    session.md5()
            );
            if (existingFileId != null) {
                rememberCompletedSession(sessionId, existingFileId);
                cleanupDuplicateUpload(userId, sessionId, session);
                businessMetrics.recordUploadLifecycle("deduplicated", "multipart", mediaType, "success");
                return existingFileId;
            }

            String fileId = fileService.registerDirectUpload(
                    DirectUploadRegistration.builder()
                            .userId(userId)
                            .albumId(session.albumId())
                            .fileName(session.fileName())
                            .objectName(session.objectName())
                            .contentType(session.contentType())
                            .md5(session.md5())
                            .fileSize(session.fileSize())
                            .lastModified(session.lastModified())
                            .build()
            );
            registered = true;
            rememberCompletedSession(sessionId, fileId);
            try {
                quotaService.confirm(userId, sessionId);
            } catch (RuntimeException e) {
                log.warn("Upload registered but quota reservation cleanup failed: sessionId={}", sessionId, e);
            }
            deleteActiveSession(userId, sessionId, session);
            businessMetrics.recordUploadLifecycle("completed", "multipart", mediaType, "success");
            return fileId;
        } catch (BusinessException e) {
            businessMetrics.recordUploadLifecycle("complete", "multipart", mediaType, "failed");
            if (objectCompleted && !registered) {
                cleanupCompletedUpload(userId, sessionId, session.objectName());
            }
            throw e;
        } catch (Exception e) {
            businessMetrics.recordUploadLifecycle("complete", "multipart", mediaType, "failed");
            if (objectCompleted && !registered) {
                cleanupCompletedUpload(userId, sessionId, session.objectName());
            }
            log.error(
                    "Completing multipart upload failed: sessionId={}, objectName={}, fileName={}, contentType={}",
                    sessionId,
                    session.objectName(),
                    session.fileName(),
                    session.contentType(),
                    e
            );
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "Failed to complete multipart upload");
        }
    }

    public void abort(Long userId, String sessionId) {
        UploadSession session = getOwnedSession(userId, sessionId);
        abortQuietly(session.objectName(), session.uploadId());
        quotaService.release(userId, sessionId);
        deleteActiveSession(userId, sessionId, session);
        businessMetrics.recordUploadLifecycle("aborted", "multipart", mediaTypeFor(session.contentType()), "success");
    }

    @Scheduled(fixedDelayString = "${upload.multipart.cleanup-delay-ms:300000}")
    public void cleanupExpiredUploads() {
        scheduledTaskLockService.runWithLock(
                "upload:multipart-expired-cleanup",
                Duration.ofSeconds(multipartCleanupLockTtlSeconds),
                this::cleanupExpiredUploadsLocked
        );
    }

    private void cleanupExpiredUploadsLocked() {
        Set<String> expired = redisTemplate.opsForZSet().rangeByScore(
                EXPIRY_KEY,
                0,
                System.currentTimeMillis(),
                0,
                100
        );
        if (expired == null || expired.isEmpty()) {
            return;
        }
        for (String sessionId : expired) {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(sessionId));
            if (!values.isEmpty()) {
                try {
                    Long userId = Long.valueOf(String.valueOf(values.get("userId")));
                    abortQuietly(
                            String.valueOf(values.get("objectName")),
                            String.valueOf(values.get("uploadId"))
                    );
                    quotaService.release(userId, sessionId);
                    releaseDedupLock(
                            userId,
                            stringOrNull(values.get("md5")),
                            stringOrNull(values.get("sha256")),
                            sessionId
                    );
                    businessMetrics.recordUploadLifecycle(
                            "expired_cleanup",
                            "multipart",
                            mediaTypeFor(stringOrNull(values.get("contentType"))),
                            "success"
                    );
                } catch (RuntimeException e) {
                    log.warn("Failed to cleanup expired multipart upload: sessionId={}", sessionId, e);
                    businessMetrics.recordUploadLifecycle("expired_cleanup", "multipart", "unknown", "failed");
                    continue;
                }
            }
            redisTemplate.delete(sessionKey(sessionId));
            redisTemplate.opsForZSet().remove(EXPIRY_KEY, sessionId);
        }
    }

    private void validateUploadedParts(List<Part> parts, UploadSession session) {
        long uploadedSize = parts.stream().mapToLong(Part::partSize).sum();
        if (parts.size() != session.partCount() || uploadedSize != session.fileSize()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Multipart upload is incomplete");
        }
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            int expectedPartNumber = i + 1;
            if (part.partNumber() != expectedPartNumber) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Multipart upload is incomplete");
            }
            long expectedSize = partSizeFor(session, expectedPartNumber);
            if (part.partSize() != expectedSize) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Multipart part size validation failed");
            }
        }
    }

    private long partSizeFor(UploadSession session, int partNumber) {
        if (partNumber == session.partCount()) {
            long remaining = session.fileSize() - session.partSize() * (partNumber - 1L);
            return Math.max(remaining, 0);
        }
        return session.partSize();
    }

    private List<Integer> uploadedPartNumbers(UploadSession session) {
        try {
            return externalServiceExecutor.execute(
                            ExternalServiceExecutor.MINIO,
                            () -> minioClient.listUploadedParts(
                                    minIOConfig.getBucketName(),
                                    session.objectName(),
                                    session.uploadId()
                            )
                    ).stream()
                    .map(Part::partNumber)
                    .sorted()
                    .toList();
        } catch (Exception e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "Failed to query uploaded parts");
        }
    }

    private MultipartUploadInitResponse buildSessionResponse(
            String sessionId,
            UploadSession session,
            List<MultipartUploadInitResponse.PartUploadUrl> parts,
            List<Integer> uploadedParts,
            Long urlsExpireAt
    ) {
        MultipartUploadInitResponse response = new MultipartUploadInitResponse();
        response.setSessionId(sessionId);
        response.setObjectName(session.objectName());
        response.setPartSize(session.partSize());
        response.setPartCount(session.partCount());
        response.setParts(parts == null ? List.of() : parts);
        response.setUploadedParts(uploadedParts == null ? List.of() : uploadedParts);
        response.setUrlsExpireAt(urlsExpireAt);
        return response;
    }

    private MultipartUploadInitResponse.PartUploadUrl presignPart(
            String objectName,
            String uploadId,
            int partNumber,
            long urlsExpireAt
    ) {
        try {
            MultipartUploadInitResponse.PartUploadUrl part = new MultipartUploadInitResponse.PartUploadUrl(
                    partNumber,
                    externalServiceExecutor.execute(
                            ExternalServiceExecutor.MINIO,
                            () -> minioClient.presignPart(
                                    minIOConfig.getBucketName(),
                                    objectName,
                                    uploadId,
                                    partNumber,
                                    presignedUrlTtlSeconds
                            )
                    )
            );
            part.setExpiresAt(urlsExpireAt);
            return part;
        } catch (Exception e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "Failed to create multipart upload URL");
        }
    }

    private UploadSession getOwnedSession(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Upload session cannot be empty");
        }
        Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(sessionId));
        if (values.isEmpty()) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "Upload session does not exist or has expired");
        }
        Long ownerId = Long.valueOf(String.valueOf(values.get("userId")));
        if (!ownerId.equals(userId)) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "No permission for this upload session");
        }
        return new UploadSession(
                String.valueOf(values.get("uploadId")),
                String.valueOf(values.get("objectName")),
                String.valueOf(values.get("fileName")),
                Long.parseLong(String.valueOf(values.get("fileSize"))),
                String.valueOf(values.get("contentType")),
                String.valueOf(values.get("md5")),
                stringOrEmpty(values.get("sha256")),
                Long.parseLong(String.valueOf(values.get("lastModified"))),
                Long.parseLong(String.valueOf(values.get("albumId"))),
                parseLong(values.get("partSize"), Math.max(partSize, 5L * 1024 * 1024)),
                Integer.parseInt(String.valueOf(values.get("partCount")))
        );
    }

    private void abortQuietly(String objectName, String uploadId) {
        if (objectName == null || uploadId == null) {
            return;
        }
        try {
            externalServiceExecutor.run(
                    ExternalServiceExecutor.MINIO,
                    () -> minioClient.abortUpload(
                            minIOConfig.getBucketName(),
                            objectName,
                            uploadId
                    )
            );
        } catch (Exception ignored) {
            // The upload may already be completed or removed.
        }
    }

    private void cleanupCompletedUpload(Long userId, String sessionId, String objectName) {
        try {
            minioOSSUtil.delete(objectName);
        } catch (RuntimeException e) {
            log.warn("Failed to rollback completed object: objectName={}", objectName, e);
        }
        try {
            quotaService.release(userId, sessionId);
        } finally {
            Map<Object, Object> values = redisTemplate.opsForHash().entries(sessionKey(sessionId));
            String md5 = values.isEmpty() ? null : stringOrNull(values.get("md5"));
            String sha256 = values.isEmpty() ? null : stringOrNull(values.get("sha256"));
            redisTemplate.delete(sessionKey(sessionId));
            redisTemplate.opsForZSet().remove(EXPIRY_KEY, sessionId);
            releaseDedupLock(userId, md5, sha256, sessionId);
        }
    }

    private void cleanupDuplicateUpload(Long userId, String sessionId, UploadSession session) {
        try {
            minioOSSUtil.delete(session.objectName());
        } catch (RuntimeException e) {
            log.warn("Failed to delete duplicate multipart object: objectName={}", session.objectName(), e);
        } finally {
            quotaService.release(userId, sessionId);
            deleteActiveSession(userId, sessionId, session);
        }
    }

    private void deleteActiveSession(Long userId, String sessionId, UploadSession session) {
        redisTemplate.delete(sessionKey(sessionId));
        redisTemplate.opsForZSet().remove(EXPIRY_KEY, sessionId);
        releaseDedupLock(userId, session.md5(), session.sha256(), sessionId);
    }

    private MultipartUploadInitResponse instantUploadResponse(String fileId) {
        MultipartUploadInitResponse response = new MultipartUploadInitResponse();
        response.setFileId(fileId);
        response.setInstantUpload(true);
        response.setParts(List.of());
        response.setUploadedParts(List.of());
        return response;
    }

    private String normalizeMd5(String md5) {
        if (md5 == null || !MD5_PATTERN.matcher(md5).matches()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "MD5 format is invalid");
        }
        return md5.toLowerCase();
    }

    private String normalizeSha256(String sha256) {
        if (sha256 == null || !SHA256_PATTERN.matcher(sha256).matches()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "SHA-256 format is invalid");
        }
        return sha256.toLowerCase();
    }

    private String completedFileId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return redisTemplate.opsForValue().get(completedKey(sessionId));
    }

    private void rememberCompletedSession(String sessionId, String fileId) {
        if (sessionId == null || fileId == null) {
            return;
        }
        redisTemplate.opsForValue().set(
                completedKey(sessionId),
                fileId,
                COMPLETED_SESSION_TTL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private String dedupLockKey(Long userId, String md5, String sha256) {
        String fingerprint = (sha256 == null || sha256.isBlank()) ? md5 : sha256;
        return DEDUP_LOCK_PREFIX + userId + ":" + fingerprint.toLowerCase();
    }

    private void releaseDedupLock(Long userId, String md5, String sha256, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        String fingerprint = sha256 == null || sha256.isBlank() ? md5 : sha256;
        if (fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        redisTemplate.execute(
                RELEASE_LOCK_SCRIPT,
                List.of(dedupLockKey(userId, md5, sha256)),
                sessionId
        );
    }

    private void extendCleanupDeadline(String sessionId) {
        redisTemplate.opsForZSet().add(
                EXPIRY_KEY,
                sessionId,
                System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(sessionTtlSeconds)
        );
        redisTemplate.expire(sessionKey(sessionId), sessionTtlSeconds + 3600, TimeUnit.SECONDS);
    }

    private long urlsExpireAt() {
        return System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(presignedUrlTtlSeconds);
    }

    private long parseLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String mediaTypeFor(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "unknown";
        }
        String normalized = contentType.toLowerCase();
        if (normalized.startsWith("image/")) {
            return "image";
        }
        if (normalized.startsWith("video/")) {
            return "video";
        }
        return "other";
    }

    private String sessionKey(String sessionId) {
        return SESSION_PREFIX + sessionId;
    }

    private String completedKey(String sessionId) {
        return COMPLETED_PREFIX + sessionId;
    }

    private record UploadSession(
            String uploadId,
            String objectName,
            String fileName,
            long fileSize,
            String contentType,
            String md5,
            String sha256,
            long lastModified,
            long albumId,
            long partSize,
            int partCount
    ) {
    }
}
