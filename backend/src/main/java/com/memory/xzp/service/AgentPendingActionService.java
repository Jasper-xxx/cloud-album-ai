package com.memory.xzp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.AgentPendingActionMapper;
import com.memory.xzp.model.dto.agent.AgentPendingActionClaim;
import com.memory.xzp.model.dto.agent.AgentPendingActionPayload;
import com.memory.xzp.model.entity.AgentPendingActionEntity;
import com.memory.xzp.model.vo.agent.AgentActionPreviewVO;
import com.memory.xzp.model.vo.agent.AgentActionResultVO;
import com.memory.xzp.model.vo.agent.AgentPendingActionStatusVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 持久化、领取和完成智能体待确认操作。
 */
@Service
public class AgentPendingActionService {

    public static final String STATUS_PREVIEWED = "PREVIEWED";
    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    private static final String SAFE_EXECUTION_FAILURE = "执行未完成，请重新预览";
    private static final String SAFE_INVALID_PAYLOAD = "待执行内容无效，请重新预览";
    private static final String SAFE_STALE_EXECUTION = "执行超时未完成，请重新预览";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    );
    private static final Pattern OPAQUE_SECRET_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{32,128}$");

    private final AgentPendingActionMapper pendingActionMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.pending-action.ttl-seconds:300}")
    private long confirmationTtlSeconds;

    @Value("${agent.pending-action.workflow-version:1.6.0}")
    private String workflowVersion;

    @Value("${agent.pending-action.execution-lease-seconds:120}")
    private long executionLeaseSeconds;

    public AgentPendingActionService(
            AgentPendingActionMapper pendingActionMapper,
            ObjectMapper objectMapper
    ) {
        this.pendingActionMapper = pendingActionMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void register(
            AgentActionPreviewVO preview,
            AgentPendingActionPayload payload,
            Long userId
    ) {
        register(preview, payload, userId, null);
    }

    @Transactional
    public void register(
            AgentActionPreviewVO preview,
            AgentPendingActionPayload payload,
            Long userId,
            Long ttlSeconds
    ) {
        if (preview == null || userId == null) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "无法保存待确认操作");
        }

        LocalDateTime now = LocalDateTime.now();
        pendingActionMapper.supersedeOpenPreviews(userId, now);
        if (!Boolean.TRUE.equals(preview.getRequiresConfirmation())) {
            return;
        }
        if (payload == null) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "无法保存待确认操作");
        }

        String pendingActionId = UUID.randomUUID().toString();
        String confirmationToken = newSecret();
        String idempotencyKey = newSecret();
        long effectiveTtl = ttlSeconds == null ? confirmationTtlSeconds : ttlSeconds;
        LocalDateTime expiresAt = now.plusSeconds(Math.max(30L, effectiveTtl));

        payload.setPendingActionId(pendingActionId);
        payload.setUserId(userId);
        String payloadJson = writeJson(payload);

        AgentPendingActionEntity entity = new AgentPendingActionEntity();
        entity.setPendingActionId(pendingActionId);
        entity.setUserId(userId);
        entity.setFamily(payload.getFamily());
        entity.setAction(payload.getAction());
        entity.setPayloadJson(payloadJson);
        entity.setPayloadHash(sha256(payloadJson));
        entity.setConfirmationTokenHash(sha256(confirmationToken));
        entity.setIdempotencyKeyHash(sha256(idempotencyKey));
        entity.setStatus(STATUS_PREVIEWED);
        entity.setWorkflowVersion(workflowVersion);
        entity.setPreviewAffectedFileCount(nullToZero(preview.getAffectedFileCount()));
        entity.setPreviewCreatedAlbumCount(nullToZero(preview.getCreatedAlbumCount()));
        entity.setSummary(safeSummary(preview.getSummary()));
        entity.setExpiresAt(expiresAt);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        if (pendingActionMapper.insert(entity) != 1) {
            throw new BusinessException(StatusCode.OPERATION_ERROR, "保存待确认操作失败");
        }

        preview.setPendingActionId(pendingActionId);
        preview.setConfirmationToken(confirmationToken);
        preview.setIdempotencyKey(idempotencyKey);
        preview.setExpiresAt(toInstant(expiresAt));
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = BusinessException.class
    )
    public AgentPendingActionClaim claim(
            String pendingActionId,
            String confirmationToken,
            String idempotencyKey,
            Long userId,
            String expectedFamily
    ) {
        requireCredentials(pendingActionId, confirmationToken, idempotencyKey, userId);
        AgentPendingActionEntity entity = requireOwned(pendingActionId, userId);
        validateSecrets(entity, confirmationToken, idempotencyKey);
        if (expectedFamily != null && !expectedFamily.equals(entity.getFamily())) {
            throw new BusinessException(StatusCode.FORBIDDEN_ERROR, "确认内容与当前操作不匹配");
        }

        if (STATUS_SUCCEEDED.equals(entity.getStatus())) {
            validatePayloadHash(entity);
            AgentPendingActionClaim replay = new AgentPendingActionClaim();
            replay.setCachedResult(readResult(entity.getResultJson()));
            replay.setIdempotentReplay(true);
            replay.getCachedResult().setIdempotentReplay(true);
            return replay;
        }
        if (!STATUS_PREVIEWED.equals(entity.getStatus())) {
            failIfExecutionLeaseExpired(entity, userId, LocalDateTime.now());
            throw stateException(entity.getStatus(), "该确认已使用或当前操作不能执行");
        }

        LocalDateTime now = LocalDateTime.now();
        if (!workflowVersion.equals(entity.getWorkflowVersion())) {
            pendingActionMapper.markInvalidPreviewFailed(
                    pendingActionId,
                    userId,
                    SAFE_INVALID_PAYLOAD,
                    now
            );
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "工作流版本已变化，请重新预览");
        }
        if (!entity.getExpiresAt().isAfter(now)) {
            pendingActionMapper.markExpired(pendingActionId, userId, now);
            throw new BusinessException(StatusCode.GONE_ERROR, "确认已过期，请重新预览");
        }
        AgentPendingActionPayload payload;
        try {
            validatePayloadHash(entity);
            payload = readPayload(entity.getPayloadJson());
            validatePayloadBinding(entity, payload, userId, expectedFamily);
        } catch (BusinessException exception) {
            pendingActionMapper.markInvalidPreviewFailed(
                    pendingActionId,
                    userId,
                    SAFE_INVALID_PAYLOAD,
                    now
            );
            throw exception;
        }
        if (pendingActionMapper.claim(pendingActionId, userId, now) != 1) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "该确认正在执行或已经被使用");
        }

        AgentPendingActionClaim claim = new AgentPendingActionClaim();
        claim.setPayload(payload);
        return claim;
    }

    @Transactional
    public void complete(
            String pendingActionId,
            Long userId,
            AgentActionResultVO result
    ) {
        String resultJson = writeJson(result);
        int updated = pendingActionMapper.markSuccess(
                pendingActionId,
                userId,
                resultJson,
                nullToZero(result.getAffectedFileCount()),
                nullToZero(result.getCreatedAlbumCount()),
                nullToZero(result.getSkippedFileCount()),
                LocalDateTime.now()
        );
        if (updated != 1) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "待确认操作状态已变化，无法提交结果");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String pendingActionId, Long userId, String failureMessage) {
        if (pendingActionId == null || pendingActionId.isBlank() || userId == null) {
            return;
        }
        pendingActionMapper.markFailed(
                pendingActionId,
                userId,
                safeFailureMessage(failureMessage),
                LocalDateTime.now()
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = BusinessException.class
    )
    public AgentPendingActionStatusVO cancel(
            String pendingActionId,
            String confirmationToken,
            Long userId
    ) {
        if (!isValidPendingActionId(pendingActionId)
                || !isValidOpaqueSecret(confirmationToken)
                || userId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "pendingActionId或confirmationToken格式无效");
        }
        AgentPendingActionEntity entity = requireOwned(pendingActionId, userId);
        if (!constantTimeEquals(entity.getConfirmationTokenHash(), sha256(confirmationToken))) {
            throw new BusinessException(StatusCode.FORBIDDEN_ERROR, "确认凭证无效");
        }
        if (!STATUS_PREVIEWED.equals(entity.getStatus())) {
            throw stateException(entity.getStatus(), "该操作已处理，不能再次取消");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!entity.getExpiresAt().isAfter(now)) {
            pendingActionMapper.markExpired(pendingActionId, userId, now);
            throw new BusinessException(StatusCode.GONE_ERROR, "确认已过期");
        }
        if (pendingActionMapper.cancel(pendingActionId, userId, now) != 1) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "该操作状态已变化，不能取消");
        }
        entity.setStatus(STATUS_CANCELLED);
        entity.setCompletedAt(now);
        return toStatus(entity);
    }

    @Transactional
    public AgentPendingActionStatusVO getStatus(String pendingActionId, Long userId) {
        if (!isValidPendingActionId(pendingActionId) || userId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "pendingActionId格式无效");
        }
        AgentPendingActionEntity entity = requireOwned(pendingActionId, userId);
        LocalDateTime now = LocalDateTime.now();
        if (STATUS_PREVIEWED.equals(entity.getStatus()) && !entity.getExpiresAt().isAfter(now)) {
            pendingActionMapper.markExpired(pendingActionId, userId, now);
            entity.setStatus(STATUS_EXPIRED);
            entity.setCompletedAt(now);
        }
        failIfExecutionLeaseExpired(entity, userId, now);
        return toStatus(entity);
    }

    @Scheduled(
            fixedDelayString = "${agent.pending-action.cleanup-delay-ms:60000}",
            initialDelayString = "${agent.pending-action.cleanup-initial-delay-ms:60000}"
    )
    @Transactional
    public void recoverExpiredAndStaleActions() {
        LocalDateTime now = LocalDateTime.now();
        pendingActionMapper.sweepExpired(now);
        pendingActionMapper.sweepStaleExecuting(
                executionLeaseCutoff(now),
                SAFE_STALE_EXECUTION,
                now
        );
    }

    private AgentPendingActionEntity requireOwned(String pendingActionId, Long userId) {
        AgentPendingActionEntity entity = pendingActionMapper.selectByPendingActionId(pendingActionId);
        if (entity == null) {
            throw new BusinessException(StatusCode.GONE_ERROR, "待确认操作不存在或已清理");
        }
        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException(StatusCode.FORBIDDEN_ERROR, "无权访问该待确认操作");
        }
        return entity;
    }

    private void validateSecrets(
            AgentPendingActionEntity entity,
            String confirmationToken,
            String idempotencyKey
    ) {
        if (!constantTimeEquals(entity.getConfirmationTokenHash(), sha256(confirmationToken))
                || !constantTimeEquals(entity.getIdempotencyKeyHash(), sha256(idempotencyKey))) {
            throw new BusinessException(StatusCode.FORBIDDEN_ERROR, "确认凭证无效");
        }
    }

    private void validatePayloadHash(AgentPendingActionEntity entity) {
        if (!constantTimeEquals(entity.getPayloadHash(), sha256(entity.getPayloadJson()))) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "待执行参数完整性校验失败");
        }
    }

    private void validatePayloadBinding(
            AgentPendingActionEntity entity,
            AgentPendingActionPayload payload,
            Long userId,
            String expectedFamily
    ) {
        boolean valid = payload != null
                && Objects.equals(entity.getPendingActionId(), payload.getPendingActionId())
                && Objects.equals(userId, payload.getUserId())
                && Objects.equals(entity.getFamily(), payload.getFamily())
                && Objects.equals(entity.getAction(), payload.getAction())
                && (expectedFamily == null || Objects.equals(expectedFamily, payload.getFamily()))
                && (payload.getFileIds() == null || payload.getFileIds().size() <= 100)
                && (payload.getAlbumIds() == null || payload.getAlbumIds().size() <= 20)
                && (payload.getPersonIds() == null || payload.getPersonIds().size() <= 20);
        if (!valid) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "待执行内容绑定校验失败");
        }
    }

    private void failIfExecutionLeaseExpired(
            AgentPendingActionEntity entity,
            Long userId,
            LocalDateTime now
    ) {
        if (!STATUS_EXECUTING.equals(entity.getStatus())
                || entity.getStartedAt() == null
                || entity.getStartedAt().isAfter(executionLeaseCutoff(now))) {
            return;
        }
        if (pendingActionMapper.markStaleExecutingFailed(
                entity.getPendingActionId(),
                userId,
                executionLeaseCutoff(now),
                SAFE_STALE_EXECUTION,
                now
        ) == 1) {
            entity.setStatus(STATUS_FAILED);
            entity.setLastError(SAFE_STALE_EXECUTION);
            entity.setCompletedAt(now);
        }
    }

    private LocalDateTime executionLeaseCutoff(LocalDateTime now) {
        return now.minusSeconds(Math.max(30L, executionLeaseSeconds));
    }

    private void requireCredentials(
            String pendingActionId,
            String confirmationToken,
            String idempotencyKey,
            Long userId
    ) {
        if (!isValidPendingActionId(pendingActionId)
                || !isValidOpaqueSecret(confirmationToken)
                || !isValidOpaqueSecret(idempotencyKey)
                || userId == null) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "pendingActionId、confirmationToken或idempotencyKey格式无效"
            );
        }
    }

    private BusinessException stateException(String status, String fallback) {
        if (STATUS_EXPIRED.equals(status)) {
            return new BusinessException(StatusCode.GONE_ERROR, "确认已过期，请重新预览");
        }
        if (STATUS_CANCELLED.equals(status)) {
            return new BusinessException(StatusCode.CONFLICT_ERROR, "该操作已取消");
        }
        if (STATUS_FAILED.equals(status)) {
            return new BusinessException(StatusCode.CONFLICT_ERROR, "上次执行失败，请重新预览");
        }
        return new BusinessException(StatusCode.CONFLICT_ERROR, fallback);
    }

    private AgentPendingActionPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, AgentPendingActionPayload.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "待执行参数解析失败");
        }
    }

    private AgentActionResultVO readResult(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "已执行操作缺少可复用结果");
        }
        try {
            return objectMapper.readValue(json, AgentActionResultVO.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "历史执行结果解析失败");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "待确认操作序列化失败");
        }
    }

    private AgentPendingActionStatusVO toStatus(AgentPendingActionEntity entity) {
        AgentPendingActionStatusVO response = new AgentPendingActionStatusVO();
        response.setPendingActionId(entity.getPendingActionId());
        response.setStatus(entity.getStatus());
        response.setFamily(entity.getFamily());
        response.setAction(entity.getAction());
        response.setSummary(entity.getSummary());
        boolean succeeded = STATUS_SUCCEEDED.equals(entity.getStatus());
        boolean previewed = STATUS_PREVIEWED.equals(entity.getStatus());
        int previewAffected = nullToZero(entity.getPreviewAffectedFileCount());
        int previewCreated = nullToZero(entity.getPreviewCreatedAlbumCount());
        Integer actualAffected = succeeded ? nullToZero(entity.getActualAffectedFileCount()) : null;
        Integer actualCreated = succeeded ? nullToZero(entity.getActualCreatedAlbumCount()) : null;
        response.setPreviewAffectedFileCount(previewAffected);
        response.setActualAffectedFileCount(actualAffected);
        response.setSkippedFileCount(succeeded ? nullToZero(entity.getSkippedFileCount()) : null);
        response.setPreviewCreatedAlbumCount(previewCreated);
        response.setActualCreatedAlbumCount(actualCreated);
        response.setAffectedFileCount(succeeded ? actualAffected : (previewed ? previewAffected : 0));
        response.setCreatedAlbumCount(succeeded ? actualCreated : (previewed ? previewCreated : 0));
        response.setCreatedAt(toInstant(entity.getCreateTime()));
        response.setExpiresAt(toInstant(entity.getExpiresAt()));
        response.setConfirmedAt(toInstant(entity.getConfirmedAt()));
        boolean executionCompleted = succeeded || STATUS_FAILED.equals(entity.getStatus());
        response.setExecutedAt(executionCompleted ? toInstant(entity.getCompletedAt()) : null);
        response.setCompletedAt(isTerminal(entity.getStatus()) ? toInstant(entity.getCompletedAt()) : null);
        response.setFailureMessage(STATUS_FAILED.equals(entity.getStatus()) ? entity.getLastError() : null);
        return response;
    }

    private boolean isValidPendingActionId(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    private boolean isValidOpaqueSecret(String value) {
        return value != null && OPAQUE_SECRET_PATTERN.matcher(value).matches();
    }

    private boolean isTerminal(String status) {
        return STATUS_SUCCEEDED.equals(status)
                || STATUS_FAILED.equals(status)
                || STATUS_CANCELLED.equals(status)
                || STATUS_EXPIRED.equals(status);
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(ZoneId.systemDefault()).toInstant();
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeSummary(String summary) {
        String value = summary == null ? "" : summary.trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String safeFailureMessage(String message) {
        if (SAFE_STALE_EXECUTION.equals(message)
                || SAFE_INVALID_PAYLOAD.equals(message)
                || SAFE_EXECUTION_FAILURE.equals(message)) {
            return message;
        }
        return SAFE_EXECUTION_FAILURE;
    }
}
