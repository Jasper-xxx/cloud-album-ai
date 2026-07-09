package com.memory.xzp.config;

import com.memory.xzp.mapper.FaceMapper;
import com.memory.xzp.mapper.FileFeatureMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.ImageMetaDataMapper;
import com.memory.xzp.mapper.LocationMapper;
import com.memory.xzp.mapper.PictureTagMapper;
import com.memory.xzp.mapper.SimilarPictureMapper;
import com.memory.xzp.mapper.UserFileMapper;
import com.memory.xzp.mapper.VideoMetaDataMapper;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.UserFileEntity;
import com.memory.xzp.service.ScheduledTaskLockService;
import com.memory.xzp.utils.file.MinioOSSUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileCleanupTaskTest {

    @Mock
    private UserFileMapper userFileMapper;
    @Mock
    private FileMapper fileMapper;
    @Mock
    private FileFeatureMapper fileFeatureMapper;
    @Mock
    private FaceMapper faceMapper;
    @Mock
    private PictureTagMapper pictureTagMapper;
    @Mock
    private SimilarPictureMapper similarPictureMapper;
    @Mock
    private ImageMetaDataMapper imageMetadataMapper;
    @Mock
    private VideoMetaDataMapper videoMetaDataMapper;
    @Mock
    private LocationMapper locationMapper;
    @Mock
    private MinioOSSUtil minioOSSUtil;
    @Mock
    private ScheduledTaskLockService scheduledTaskLockService;

    @InjectMocks
    private FileCleanupTask cleanupTask;

    @BeforeEach
    void setUp() {
        when(scheduledTaskLockService.runWithLock(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    Runnable action = invocation.getArgument(2, Runnable.class);
                    action.run();
                    return true;
                });
    }

    @Test
    void keepsPhysicalFileWhenAnotherUserStillReferencesIt() {
        UserFileEntity expired = expiredUserFile();
        when(userFileMapper.selectSoftDeletedFiles(any())).thenReturn(List.of(expired));
        when(userFileMapper.countByFileId("file-1")).thenReturn(2L);

        cleanupTask.cleanupSoftDeletedFiles();

        verify(userFileMapper).deleteById(10L);
        verify(fileMapper, never()).deleteById("file-1");
        verify(minioOSSUtil, never()).delete(any());
        verify(fileFeatureMapper, never()).deleteByFileId("file-1");
    }

    @Test
    void removesPhysicalFileAfterLastReferenceIsDeleted() {
        UserFileEntity expired = expiredUserFile();
        FileEntity file = new FileEntity();
        file.setFileId("file-1");
        file.setFileObjectName("original/file-1.jpg");
        file.setThumbnailObjectName("thumbnail/file-1.jpg");

        when(userFileMapper.selectSoftDeletedFiles(any())).thenReturn(List.of(expired));
        when(userFileMapper.countByFileId("file-1")).thenReturn(1L);
        when(fileMapper.selectById("file-1")).thenReturn(file);

        cleanupTask.cleanupSoftDeletedFiles();

        verify(userFileMapper).deleteById(10L);
        verify(minioOSSUtil).delete("original/file-1.jpg");
        verify(minioOSSUtil).delete("thumbnail/file-1.jpg");
        verify(fileFeatureMapper).deleteByFileId("file-1");
        verify(faceMapper).deleteByFileId("file-1");
        verify(fileMapper).deleteById("file-1");
    }

    @Test
    void keepsLastUserRelationWhenPhysicalDeletionFails() {
        UserFileEntity expired = expiredUserFile();
        FileEntity file = new FileEntity();
        file.setFileId("file-1");
        file.setFileObjectName("original/file-1.jpg");

        when(userFileMapper.selectSoftDeletedFiles(any())).thenReturn(List.of(expired));
        when(userFileMapper.countByFileId("file-1")).thenReturn(1L);
        when(fileMapper.selectById("file-1")).thenReturn(file);
        doThrow(new RuntimeException("MinIO unavailable"))
                .when(minioOSSUtil).delete("original/file-1.jpg");

        cleanupTask.cleanupSoftDeletedFiles();

        verify(userFileMapper, never()).deleteById(10L);
        verify(fileMapper, never()).deleteById("file-1");
    }

    private UserFileEntity expiredUserFile() {
        UserFileEntity expired = new UserFileEntity();
        expired.setId(10L);
        expired.setUserId(20L);
        expired.setFileId("file-1");
        expired.setIsDeleted(true);
        return expired;
    }
}
