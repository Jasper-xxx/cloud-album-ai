package com.memory.xzp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PictureTagMapper;
import com.memory.xzp.model.dto.agent.AgentExecuteActionRequest;
import com.memory.xzp.service.AlbumService;
import com.memory.xzp.service.AgentPendingActionService;
import com.memory.xzp.service.AgentWriteExecutionService;
import com.memory.xzp.service.FileService;
import com.memory.xzp.service.PersonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AgentControllerSecurityTest {

    @Mock
    private FileService fileService;
    @Mock
    private AlbumService albumService;
    @Mock
    private PersonService personService;
    @Mock
    private PictureTagMapper pictureTagMapper;
    @Mock
    private FileMapper fileMapper;
    @Mock
    private AgentPendingActionService pendingActionService;
    @Mock
    private AgentWriteExecutionService writeExecutionService;

    @InjectMocks
    private AgentController controller;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void executeDtoAcceptsOnlyConfirmationCredentials() throws Exception {
        AgentExecuteActionRequest request = objectMapper.readValue(
                """
                {
                  "pendingActionId": "pending-1",
                  "confirmationToken": "token",
                  "idempotencyKey": "key",
                  "confirmed": true
                }
                """,
                AgentExecuteActionRequest.class
        );

        assertEquals("pending-1", request.getPendingActionId());
        assertEquals("token", request.getConfirmationToken());
        assertEquals("key", request.getIdempotencyKey());
        assertTrue(request.getConfirmed());
        assertTrue(request.getUnexpectedFields().isEmpty());
    }

    @Test
    void executeDtoCapturesMutableBusinessFieldsAsUnexpected() throws Exception {
        AgentExecuteActionRequest request = objectMapper.readValue(
                """
                {
                  "pendingActionId": "pending-1",
                  "confirmationToken": "token",
                  "idempotencyKey": "key",
                  "confirmed": true,
                  "action": "remove_files_from_album",
                  "albumId": 999,
                  "fileIds": ["foreign-file"],
                  "tagName": "injected"
                }
                """,
                AgentExecuteActionRequest.class
        );

        assertEquals(4, request.getUnexpectedFields().size());
        assertTrue(request.getUnexpectedFields().containsKey("action"));
        assertTrue(request.getUnexpectedFields().containsKey("albumId"));
        assertTrue(request.getUnexpectedFields().containsKey("fileIds"));
        assertTrue(request.getUnexpectedFields().containsKey("tagName"));
    }

    @Test
    void executeRejectsAnythingOtherThanExplicitTrue() {
        AgentExecuteActionRequest request = credentials();
        request.setConfirmed(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> controller.executeAlbumAction(null, request)
        );

        assertEquals(StatusCode.PARAMS_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(pendingActionService, writeExecutionService);
    }

    @Test
    void executeRejectsMutableBusinessParametersBeforeClaiming() {
        AgentExecuteActionRequest request = credentials();
        request.captureUnexpectedField("fileIds", java.util.List.of("foreign-file"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> controller.executeTagAction(null, request)
        );

        assertEquals(StatusCode.PARAMS_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(pendingActionService, writeExecutionService);
    }

    @Test
    void jacksonRejectsStringInsteadOfLiteralConfirmationBoolean() {
        assertThrows(
                com.fasterxml.jackson.databind.exc.InvalidFormatException.class,
                () -> objectMapper.readValue(
                        """
                        {
                          "pendingActionId": "pending-1",
                          "confirmationToken": "token",
                          "idempotencyKey": "key",
                          "confirmed": "true"
                        }
                        """,
                        AgentExecuteActionRequest.class
                )
        );
    }

    private AgentExecuteActionRequest credentials() {
        AgentExecuteActionRequest request = new AgentExecuteActionRequest();
        request.setPendingActionId("pending-1");
        request.setConfirmationToken("token");
        request.setIdempotencyKey("key");
        request.setConfirmed(true);
        return request;
    }
}
