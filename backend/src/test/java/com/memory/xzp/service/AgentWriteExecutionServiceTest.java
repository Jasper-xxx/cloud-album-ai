package com.memory.xzp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.memory.xzp.mapper.AlbumMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PictureTagMapper;
import com.memory.xzp.mapper.UserMapper;
import com.memory.xzp.model.dto.agent.AgentPendingActionPayload;
import com.memory.xzp.model.entity.Album;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.UserFileEntity;
import com.memory.xzp.model.vo.agent.AgentActionResultVO;
import com.memory.xzp.model.vo.album.AlbumVO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AgentWriteExecutionServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private AlbumService albumService;
    @Mock
    private AlbumMapper albumMapper;
    @Mock
    private FileMapper fileMapper;
    @Mock
    private PictureTagMapper pictureTagMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private RecordService recordService;
    @Mock
    private AgentPendingActionService pendingActionService;
    @Mock
    private HttpServletRequest servletRequest;

    @InjectMocks
    private AgentWriteExecutionService service;

    @BeforeEach
    void setUp() {
        lenient().when(userMapper.lockUserForAlbumCreation(USER_ID)).thenReturn(USER_ID);
    }

    @Test
    void createsEmptyAlbumAsSuccessfulBusinessChange() {
        AgentPendingActionPayload payload = payload("album", "create_album", List.of());
        payload.setAlbumName("Empty album");
        doAnswer(invocation -> {
            Album album = invocation.getArgument(0);
            album.setAlbumId(42L);
            return 1;
        }).when(albumMapper).insert(any(Album.class));

        AgentActionResultVO result = service.executeAlbum(payload, USER_ID, servletRequest);

        assertTrue(result.getSuccess());
        assertEquals(1, result.getCreatedAlbumCount());
        assertEquals(0, result.getAffectedFileCount());
        assertEquals(0, result.getSkippedFileCount());
        assertEquals(42L, result.getAlbumId());
        verify(pendingActionService).complete("pending-1", USER_ID, result);
        verify(recordService).createRecordLog(any(), eq(1), eq(USER_ID), eq(servletRequest));
    }

    @Test
    void creatingAlreadyExistingEmptyAlbumIsIdempotentNoOp() {
        AgentPendingActionPayload payload = payload("album", "create_album", List.of());
        payload.setAlbumName("Existing");
        Album existing = new Album();
        existing.setAlbumId(11L);
        existing.setAlbumName("Existing");
        when(albumMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        AgentActionResultVO result = service.executeAlbum(payload, USER_ID, servletRequest);

        assertTrue(result.getSuccess());
        assertEquals(0, result.getCreatedAlbumCount());
        assertEquals(0, result.getAffectedFileCount());
        assertEquals(0, result.getSkippedFileCount());
        verify(albumMapper, never()).insert(any(Album.class));
        verify(recordService, never()).createRecordLog(any(), any(), any(), any());
        verify(pendingActionService).complete("pending-1", USER_ID, result);
    }

    @Test
    void addToAlbumReportsMapperActualCountNotRequestedCount() {
        AgentPendingActionPayload payload =
                payload("album", "add_files_to_album", List.of("f1", "f2", "f3"));
        payload.setAlbumId(21L);
        payload.setAlbumName("Trip");
        when(fileMapper.selectActiveOwnershipRowsForUpdate(List.of("f1", "f2", "f3")))
                .thenReturn(ownership("f1", "f2", "f3"));
        AlbumVO album = new AlbumVO();
        album.setAlbumId(21L);
        album.setAlbumName("Trip");
        when(albumService.selectAlbumById(21L, USER_ID)).thenReturn(album);
        when(albumMapper.selectExistingPictureFileIds(
                21L,
                USER_ID,
                List.of("f1", "f2", "f3")
        )).thenReturn(List.of("f2"));
        when(albumMapper.addPicturesToAlbum(21L, USER_ID, List.of("f1", "f3")))
                .thenReturn(1);

        AgentActionResultVO result = service.executeAlbum(payload, USER_ID, servletRequest);

        assertEquals(1, result.getAffectedFileCount());
        assertEquals(2, result.getSkippedFileCount());
        verify(albumMapper).addPicturesToAlbum(21L, USER_ID, List.of("f1", "f3"));
        verify(recordService).createRecordLog(any(), eq(1), eq(USER_ID), eq(servletRequest));
        verify(pendingActionService).complete("pending-1", USER_ID, result);
    }

    @Test
    void addTagReportsOnlyRowsActuallyInserted() {
        AgentPendingActionPayload payload = payload("tag", "add_tags", List.of("f1", "f2"));
        payload.setTagName("sunset");
        payload.setImageType("picture");
        when(fileMapper.selectActiveOwnershipRowsForUpdate(List.of("f1", "f2")))
                .thenReturn(ownership("f1", "f2"));
        when(pictureTagMapper.insertIfAbsent("f1", "picture", "sunset")).thenReturn(1);
        when(pictureTagMapper.insertIfAbsent("f2", "picture", "sunset")).thenReturn(0);

        AgentActionResultVO result = service.executeTag(payload, USER_ID, servletRequest);

        assertEquals(1, result.getAffectedFileCount());
        assertEquals(1, result.getSkippedFileCount());
        verify(recordService).createRecordLog(any(), eq(1), eq(USER_ID), eq(servletRequest));
        verify(pendingActionService).complete("pending-1", USER_ID, result);
    }

    @Test
    void removeFromAlbumReportsOnlyRowsActuallyDeleted() {
        AgentPendingActionPayload payload =
                payload("album", "remove_files_from_album", List.of("f1", "f2", "f3"));
        payload.setAlbumId(21L);
        when(fileMapper.selectActiveOwnershipRowsForUpdate(List.of("f1", "f2", "f3")))
                .thenReturn(ownership("f1", "f2", "f3"));
        AlbumVO album = new AlbumVO();
        album.setAlbumId(21L);
        album.setAlbumName("Trip");
        when(albumService.selectAlbumById(21L, USER_ID)).thenReturn(album);
        when(albumMapper.selectExistingPictureFileIds(
                21L,
                USER_ID,
                List.of("f1", "f2", "f3")
        )).thenReturn(List.of("f1", "f2"));
        when(albumMapper.removePictureFromAlbum(List.of(21L), List.of("f1", "f2"), USER_ID))
                .thenReturn(1);

        AgentActionResultVO result = service.executeAlbum(payload, USER_ID, servletRequest);

        assertEquals(1, result.getAffectedFileCount());
        assertEquals(2, result.getSkippedFileCount());
        verify(albumMapper).removePictureFromAlbum(List.of(21L), List.of("f1", "f2"), USER_ID);
        verify(pendingActionService).complete("pending-1", USER_ID, result);
    }

    private AgentPendingActionPayload payload(
            String family,
            String action,
            List<String> fileIds
    ) {
        AgentPendingActionPayload payload = new AgentPendingActionPayload();
        payload.setPendingActionId("pending-1");
        payload.setUserId(USER_ID);
        payload.setFamily(family);
        payload.setAction(action);
        payload.setFileIds(fileIds);
        return payload;
    }

    private List<FileEntity> files(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> {
            FileEntity file = new FileEntity();
            file.setFileId(id);
            return file;
        }).toList();
    }

    private List<UserFileEntity> ownership(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> {
            UserFileEntity row = new UserFileEntity();
            row.setUserId(USER_ID);
            row.setFileId(id);
            row.setIsDeleted(false);
            return row;
        }).toList();
    }
}
