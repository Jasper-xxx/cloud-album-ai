package com.memory.xzp.service;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentPendingActionServiceTest {

    private static final Long USER_ID = 7L;
    private static final String PENDING_ID = "00000000-0000-4000-8000-000000000007";
    private static final String TOKEN = "confirmation_token_1234567890ABCDEFGHIJK";
    private static final String IDEMPOTENCY_KEY = "idempotency_key_1234567890ABCDEFGHIJKL";

    @Mock
    private AgentPendingActionMapper pendingActionMapper;

    private ObjectMapper objectMapper;
    private AgentPendingActionService service;

    @org.junit.jupiter.api.AfterEach
    void clearRequest() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void otherConversationCannotClaimEvenWithValidCredentials() throws Exception {
        AgentPendingActionEntity entity = validEntity();
        entity.setConversationId("conversation-b");
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(entity);
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album"));
        assertCode(StatusCode.FORBIDDEN_ERROR, error);
        verify(pendingActionMapper, never()).claim(anyString(), anyLong(), any());
    }

    @BeforeEach
    void setUp() {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setParameter("conversationId", "conversation-a");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        objectMapper = new ObjectMapper();
        service = new AgentPendingActionService(pendingActionMapper, objectMapper);
        ReflectionTestUtils.setField(service, "confirmationTtlSeconds", 300L);
        ReflectionTestUtils.setField(service, "workflowVersion", "test-workflow");
        ReflectionTestUtils.setField(service, "executionLeaseSeconds", 120L);
    }

    @Test
    void registerWithoutConfirmationPreservesOldPreviewAndIssuesNoCredentials() {
        AgentActionPreviewVO preview = new AgentActionPreviewVO();
        preview.setRequiresConfirmation(false);

        service.register(preview, payload(), USER_ID);

        verifyNoInteractions(pendingActionMapper);
        verify(pendingActionMapper, never()).insert(any(AgentPendingActionEntity.class));
        assertNull(preview.getPendingActionId());
        assertNull(preview.getConfirmationToken());
        assertNull(preview.getIdempotencyKey());
    }

    @Test
    void registerPersistsFrozenPayloadAndOnlyHashesSecrets() throws Exception {
        AgentActionPreviewVO preview = new AgentActionPreviewVO();
        preview.setRequiresConfirmation(true);
        preview.setAffectedFileCount(2);
        preview.setCreatedAlbumCount(1);
        preview.setSummary("create an album");
        AgentPendingActionPayload payload = payload();
        when(pendingActionMapper.insert(any(AgentPendingActionEntity.class))).thenReturn(1);

        service.register(preview, payload, USER_ID);

        ArgumentCaptor<AgentPendingActionEntity> captor =
                ArgumentCaptor.forClass(AgentPendingActionEntity.class);
        verify(pendingActionMapper).insert(captor.capture());
        AgentPendingActionEntity stored = captor.getValue();
        assertEquals("conversation-a", stored.getConversationId());
        verify(pendingActionMapper).lockConversation(USER_ID, "conversation-a");
        verify(pendingActionMapper).supersedeOpenPreviews(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq("conversation-a"), any(LocalDateTime.class));

        assertNotNull(preview.getPendingActionId());
        assertNotNull(preview.getConfirmationToken());
        assertNotNull(preview.getIdempotencyKey());
        assertNotNull(preview.getExpiresAt());
        assertEquals(preview.getPendingActionId(), stored.getPendingActionId());
        assertEquals(USER_ID, stored.getUserId());
        assertEquals("album", stored.getFamily());
        assertEquals("create_album_and_add_files", stored.getAction());
        assertEquals(AgentPendingActionService.STATUS_PREVIEWED, stored.getStatus());
        assertEquals("test-workflow", stored.getWorkflowVersion());
        assertEquals(2, stored.getPreviewAffectedFileCount());
        assertEquals(1, stored.getPreviewCreatedAlbumCount());
        assertEquals(64, stored.getConfirmationTokenHash().length());
        assertEquals(64, stored.getIdempotencyKeyHash().length());
        assertNotEquals(preview.getConfirmationToken(), stored.getConfirmationTokenHash());
        assertNotEquals(preview.getIdempotencyKey(), stored.getIdempotencyKeyHash());
        assertFalse(stored.getPayloadJson().contains(preview.getConfirmationToken()));
        assertFalse(stored.getPayloadJson().contains(preview.getIdempotencyKey()));

        AgentPendingActionPayload frozen =
                objectMapper.readValue(stored.getPayloadJson(), AgentPendingActionPayload.class);
        assertEquals(preview.getPendingActionId(), frozen.getPendingActionId());
        assertEquals(USER_ID, frozen.getUserId());
        assertEquals(List.of("f1", "f2"), frozen.getFileIds());
    }

    @Test
    void claimRejectsMissingCredentialsBeforeDatabaseLookup() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, " ", IDEMPOTENCY_KEY, USER_ID, "album")
        );

        assertCode(StatusCode.PARAMS_ERROR, exception);
        verifyNoInteractions(pendingActionMapper);
    }

    @Test
    void claimTreatsUnknownPendingActionAsGone() {
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album")
        );

        assertCode(StatusCode.GONE_ERROR, exception);
    }

    @Test
    void claimRejectsAnotherUsersPendingAction() throws Exception {
        AgentPendingActionEntity entity = validEntity();
        entity.setUserId(99L);
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(entity);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album")
        );

        assertCode(StatusCode.FORBIDDEN_ERROR, exception);
        verify(pendingActionMapper, never()).claim(anyString(), anyLong(), any());
    }

    @Test
    void claimRejectsWrongConfirmationToken() throws Exception {
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(validEntity());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, "wrong-token-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", IDEMPOTENCY_KEY, USER_ID, "album")
        );

        assertCode(StatusCode.FORBIDDEN_ERROR, exception);
        verify(pendingActionMapper, never()).claim(anyString(), anyLong(), any());
    }

    @Test
    void claimRejectsWrongIdempotencyKey() throws Exception {
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(validEntity());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, "wrong-key-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", USER_ID, "album")
        );

        assertCode(StatusCode.FORBIDDEN_ERROR, exception);
        verify(pendingActionMapper, never()).claim(anyString(), anyLong(), any());
    }

    @Test
    void claimRejectsTamperedFrozenPayload() throws Exception {
        AgentPendingActionEntity entity = validEntity();
        entity.setPayloadJson(entity.getPayloadJson().replace("Trip", "Injected"));
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(entity);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album")
        );

        assertCode(StatusCode.CONFLICT_ERROR, exception);
        verify(pendingActionMapper, never()).claim(anyString(), anyLong(), any());
    }

    @Test
    void claimRejectsMismatchedActionFamily() throws Exception {
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(validEntity());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "tag")
        );

        assertCode(StatusCode.FORBIDDEN_ERROR, exception);
        verify(pendingActionMapper, never()).claim(anyString(), anyLong(), any());
    }

    @Test
    void claimExpiresStalePreviewBeforeExecution() throws Exception {
        AgentPendingActionEntity entity = validEntity();
        entity.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(entity);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album")
        );

        assertCode(StatusCode.GONE_ERROR, exception);
        verify(pendingActionMapper).markExpired(
                org.mockito.ArgumentMatchers.eq(PENDING_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any(LocalDateTime.class)
        );
        verify(pendingActionMapper, never()).claim(anyString(), anyLong(), any());
    }

    @Test
    void claimReturnsFrozenPayloadAfterAtomicStateTransition() throws Exception {
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(validEntity());
        when(pendingActionMapper.claim(
                org.mockito.ArgumentMatchers.eq(PENDING_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any(LocalDateTime.class)
        )).thenReturn(1);

        AgentPendingActionClaim claim =
                service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album");

        assertFalse(claim.isIdempotentReplay());
        assertNotNull(claim.getPayload());
        assertEquals(PENDING_ID, claim.getPayload().getPendingActionId());
        assertEquals(List.of("f1", "f2"), claim.getPayload().getFileIds());
    }

    @Test
    void claimReportsConflictWhenAtomicTransitionLosesRace() throws Exception {
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(validEntity());
        when(pendingActionMapper.claim(
                org.mockito.ArgumentMatchers.eq(PENDING_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any(LocalDateTime.class)
        )).thenReturn(0);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album")
        );

        assertCode(StatusCode.CONFLICT_ERROR, exception);
    }

    @Test
    void claimReturnsCachedResultForSafeIdempotentReplay() throws Exception {
        AgentPendingActionEntity entity = validEntity();
        entity.setStatus(AgentPendingActionService.STATUS_SUCCEEDED);
        AgentActionResultVO cached = new AgentActionResultVO();
        cached.setSuccess(true);
        cached.setAffectedFileCount(2);
        cached.setPendingActionId(PENDING_ID);
        entity.setResultJson(objectMapper.writeValueAsString(cached));
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(entity);

        AgentPendingActionClaim claim =
                service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album");

        assertTrue(claim.isIdempotentReplay());
        assertTrue(claim.getCachedResult().getIdempotentReplay());
        assertEquals(2, claim.getCachedResult().getAffectedFileCount());
        verify(pendingActionMapper, never()).claim(anyString(), anyLong(), any());
    }

    @Test
    void cancelledPreviewCannotBeClaimed() throws Exception {
        AgentPendingActionEntity entity = validEntity();
        entity.setStatus(AgentPendingActionService.STATUS_CANCELLED);
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(entity);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.claim(PENDING_ID, TOKEN, IDEMPOTENCY_KEY, USER_ID, "album")
        );

        assertCode(StatusCode.CONFLICT_ERROR, exception);
    }

    @Test
    void cancelTransitionsOnlyValidOwnedPreview() throws Exception {
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(validEntity());
        when(pendingActionMapper.cancel(
                org.mockito.ArgumentMatchers.eq(PENDING_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any(LocalDateTime.class)
        )).thenReturn(1);

        AgentPendingActionStatusVO result = service.cancel(PENDING_ID, TOKEN, USER_ID);

        assertEquals(AgentPendingActionService.STATUS_CANCELLED, result.getStatus());
        assertEquals(PENDING_ID, result.getPendingActionId());
        assertNull(result.getExecutedAt());
        assertNotNull(result.getCompletedAt());
    }

    @Test
    void cancelRejectsInvalidToken() throws Exception {
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(validEntity());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.cancel(PENDING_ID, "wrong-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", USER_ID)
        );

        assertCode(StatusCode.FORBIDDEN_ERROR, exception);
        verify(pendingActionMapper, never()).cancel(anyString(), anyLong(), any());
    }

    @Test
    void statusReadMarksExpiredPreview() throws Exception {
        AgentPendingActionEntity entity = validEntity();
        entity.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(pendingActionMapper.selectByPendingActionId(PENDING_ID)).thenReturn(entity);

        AgentPendingActionStatusVO result = service.getStatus(PENDING_ID, USER_ID);

        assertEquals(AgentPendingActionService.STATUS_EXPIRED, result.getStatus());
        assertNull(result.getExecutedAt());
        assertNotNull(result.getCompletedAt());
        verify(pendingActionMapper).markExpired(
                org.mockito.ArgumentMatchers.eq(PENDING_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any(LocalDateTime.class)
        );
    }

    @Test
    void completePersistsActualAndSkippedCounts() {
        AgentActionResultVO result = new AgentActionResultVO();
        result.setAffectedFileCount(1);
        result.setCreatedAlbumCount(1);
        result.setSkippedFileCount(3);
        when(pendingActionMapper.markSuccess(
                org.mockito.ArgumentMatchers.eq(PENDING_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                anyString(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(3),
                any(LocalDateTime.class)
        )).thenReturn(1);

        service.complete(PENDING_ID, USER_ID, result);

        verify(pendingActionMapper).markSuccess(
                org.mockito.ArgumentMatchers.eq(PENDING_ID),
                org.mockito.ArgumentMatchers.eq(USER_ID),
                anyString(),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(3),
                any(LocalDateTime.class)
        );
    }

    private AgentPendingActionPayload payload() {
        AgentPendingActionPayload payload = new AgentPendingActionPayload();
        payload.setPendingActionId(PENDING_ID);
        payload.setUserId(USER_ID);
        payload.setFamily("album");
        payload.setAction("create_album_and_add_files");
        payload.setAlbumName("Trip");
        payload.setFileIds(List.of("f1", "f2"));
        return payload;
    }

    private AgentPendingActionEntity validEntity() throws Exception {
        AgentPendingActionPayload payload = payload();
        String payloadJson = objectMapper.writeValueAsString(payload);
        AgentPendingActionEntity entity = new AgentPendingActionEntity();
        entity.setPendingActionId(PENDING_ID);
        entity.setUserId(USER_ID);
        entity.setConversationId("conversation-a");
        entity.setFamily("album");
        entity.setAction(payload.getAction());
        entity.setPayloadJson(payloadJson);
        entity.setPayloadHash(sha256(payloadJson));
        entity.setConfirmationTokenHash(sha256(TOKEN));
        entity.setIdempotencyKeyHash(sha256(IDEMPOTENCY_KEY));
        entity.setStatus(AgentPendingActionService.STATUS_PREVIEWED);
        entity.setWorkflowVersion("test-workflow");
        entity.setSummary("summary");
        entity.setPreviewAffectedFileCount(2);
        entity.setPreviewCreatedAlbumCount(1);
        entity.setCreateTime(LocalDateTime.now().minusSeconds(1));
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return entity;
    }

    private String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertCode(StatusCode expected, BusinessException actual) {
        assertEquals(expected.getCode(), actual.getCode());
    }
}
