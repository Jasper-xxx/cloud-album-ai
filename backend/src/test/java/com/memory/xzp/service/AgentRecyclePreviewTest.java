package com.memory.xzp.service;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.mapper.*;
import com.memory.xzp.model.dto.agent.AgentExtendedActionRequest;
import com.memory.xzp.model.dto.agent.AgentPendingActionPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AgentRecyclePreviewTest {
    @Test void restoreAllFreezesOnlyOwnedRecycleImages() {
        var request = new AgentExtendedActionRequest();
        request.setAction("restore_files");
        request.setAllRecycleImages(true);
        when(extended.selectOwnedRecycleImageIds(7L, 51)).thenReturn(List.of("f1"));
        when(extended.selectOwnedDeletedFileIds(7L, List.of("f1"))).thenReturn(List.of("f1"));
        var preview = service.previewP3(request, 7L);
        assertEquals(List.of("f1"), preview.getFileIds());
        assertEquals(1, preview.getAffectedFileCount());
        verifyNoInteractions(fileService, recycle);
    }

    @Test void emptyRecycleReturnsNoopAndOversizeRefusesToTruncate() {
        var request = new AgentExtendedActionRequest();
        request.setAction("restore_files");
        request.setAllRecycleImages(true);
        when(extended.selectOwnedRecycleImageIds(7L, 51)).thenReturn(List.of());
        var preview = service.previewP3(request, 7L);
        assertFalse(preview.getRequiresConfirmation());
        assertEquals(0, preview.getAffectedFileCount());
        assertTrue(preview.getSummary().contains("没有可恢复"));
        when(extended.selectOwnedRecycleImageIds(7L, 51)).thenReturn(IntStream.range(0,51).mapToObj(i -> "f"+i).toList());
        assertThrows(BusinessException.class, () -> service.previewP3(request, 7L));
    }

    @Test void restoreScopeCannotMixIdsOrBeUsedByAnotherAction() {
        var request = request();
        request.setAllRecycleImages(true);
        assertThrows(BusinessException.class, () -> service.previewP4(request, 7L));
        request.setAction("restore_files");
        request.setAlbumName(null);
        request.setFileIds(List.of("f1"));
        assertThrows(BusinessException.class, () -> service.previewP3(request, 7L));
        verifyNoInteractions(extended, pending);
    }
    @Mock AgentPendingActionService pending;
    @Mock AgentExtendedActionMapper extended;
    @Mock FileMapper files;
    @Mock FileService fileService;
    @Mock AsyncTaskService async;
    @Mock RecycleService recycle;
    @Mock PersonService people;
    @Mock PersonMapper personMapper;
    @Mock AlbumService albums;
    @Mock RecordService records;
    @InjectMocks AgentExtendedActionService service;

    private AgentExtendedActionRequest request() {
        var request = new AgentExtendedActionRequest();
        request.setAction("move_files_to_recycle_bin");
        request.setAlbumName("测试旅行");
        return request;
    }

    @Test void freezesOwnedImagesAndLeavesAlbumAndFilesUntouchedDuringPreview() {
        when(extended.selectOwnedNormalAlbumsByName(7L, "测试旅行")).thenReturn(List.of(11L));
        when(extended.selectOwnedAlbumImageIds(7L, 11L, 21)).thenReturn(List.of("f1", "f2"));
        when(files.selectOwnedActiveFileIds(List.of("f1", "f2"), 7L)).thenReturn(List.of("f1", "f2"));
        var preview = service.previewP4(request(), 7L);
        assertFalse(preview.getIrreversible());
        assertEquals(2, preview.getAffectedFileCount());
        assertTrue(preview.getSummary().contains("相册保留"));
        var payload = ArgumentCaptor.forClass(AgentPendingActionPayload.class);
        verify(pending).register(eq(preview), payload.capture(), eq(7L), eq(90L));
        assertEquals(List.of("f1", "f2"), payload.getValue().getFileIds());
        assertEquals("move_files_to_recycle_bin", payload.getValue().getAction());
        verifyNoInteractions(fileService, recycle, albums);
    }

    @Test void missingOrAmbiguousAlbumCannotCreatePendingAction() {
        for (var matches : List.of(List.<Long>of(), List.of(11L, 12L))) {
            when(extended.selectOwnedNormalAlbumsByName(7L, "测试旅行")).thenReturn(matches);
            assertThrows(BusinessException.class, () -> service.previewP4(request(), 7L));
        }
        verifyNoInteractions(pending);
        verify(extended, never()).selectOwnedAlbumImageIds(anyLong(), anyLong(), anyInt());
    }

    @Test void emptyOrOversizedAlbumIsNeverSilentlyTruncated() {
        when(extended.selectOwnedNormalAlbumsByName(7L, "测试旅行")).thenReturn(List.of(11L));
        for (var ids : List.of(List.<String>of(), IntStream.range(0,21).mapToObj(i -> "f"+i).toList())) {
            when(extended.selectOwnedAlbumImageIds(7L, 11L, 21)).thenReturn(ids);
            assertThrows(BusinessException.class, () -> service.previewP4(request(), 7L));
        }
        verifyNoInteractions(pending);
    }

    @Test void albumNameCannotEscalateToPermanentDeleteOrMixScopes() {
        var request = request();
        request.setAction("permanently_delete_files");
        assertThrows(BusinessException.class, () -> service.previewP4(request, 7L));
        request.setAction("move_files_to_recycle_bin");
        request.setFileIds(List.of("foreign-file"));
        assertThrows(BusinessException.class, () -> service.previewP4(request, 7L));
        verifyNoInteractions(extended, pending);
    }
}
