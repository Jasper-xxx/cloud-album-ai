package com.memory.xzp.service.impl;

import com.memory.xzp.mapper.*;
import com.memory.xzp.model.entity.*;
import com.memory.xzp.utils.file.MinioOSSUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecycleServiceImplTest {
    @Mock FileMapper fileMapper;
    @Mock UserFileMapper userFileMapper;
    @Mock UserStorageMapper userStorageMapper;
    @Mock FileObjectGcMapper objectGcMapper;
    @Mock MinioOSSUtil minioOSSUtil;
    @InjectMocks RecycleServiceImpl service;

    private UserFileEntity expired() {
        UserFileEntity row = new UserFileEntity();
        row.setId(1L); row.setUserId(10L); row.setFileId("shared");
        row.setDeletedTime(LocalDateTime.now().minusDays(31)); row.setIsDeleted(true);
        return row;
    }
    private FileEntity file() {
        FileEntity file = new FileEntity(); file.setFileId("shared"); file.setSize(100L);
        file.setFileObjectName("original/shared.jpg"); return file;
    }
    @Test void onlyDeletedExpiredRelationReleasesItsOwnersQuota() {
        UserFileEntity row = expired();
        when(userFileMapper.selectSoftDeletedFiles(any())).thenReturn(List.of(row));
        when(fileMapper.selectBatchIds(List.of("shared"))).thenReturn(List.of(file()));
        when(userFileMapper.deleteDeletedRelation(eq(1L), eq(10L), eq(row.getDeletedTime()), any())).thenReturn(1);
        service.cronDropPicture();
        verify(userStorageMapper).releaseSpace(10L, 100L);
        verify(userFileMapper).deleteRemovedAlbumMembership(10L, "shared");
        verify(userFileMapper).deleteRemovedSimilarMembership(10L, "shared");
        verifyNoInteractions(minioOSSUtil, objectGcMapper);
    }
    @Test void concurrentRecoveryDoesNotReleaseQuotaOrDeleteObject() {
        when(userFileMapper.selectSoftDeletedFiles(any())).thenReturn(List.of(expired()));
        when(fileMapper.selectBatchIds(List.of("shared"))).thenReturn(List.of(file()));
        service.cronDropPicture();
        verifyNoInteractions(userStorageMapper, minioOSSUtil, objectGcMapper);
        verify(userFileMapper, never()).deleteRemovedAlbumMembership(any(), any());
        verify(userFileMapper, never()).deleteRemovedSimilarMembership(any(), any());
    }
    @Test void orphanDeletionQueuesObjectForAfterCommitRetry() {
        when(userFileMapper.selectExpiredPicture()).thenReturn(List.of(file()));
        service.cronDropPicture();
        verify(objectGcMapper).enqueue("original/shared.jpg");
        verify(fileMapper).deleteById("shared");
        verifyNoInteractions(minioOSSUtil);
    }
}
