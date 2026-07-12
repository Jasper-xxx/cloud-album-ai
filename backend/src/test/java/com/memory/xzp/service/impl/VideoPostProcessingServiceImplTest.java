package com.memory.xzp.service.impl;

import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.VideoMetaDataMapper;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.VideoMetaData;
import com.memory.xzp.utils.file.MinioOSSUtil;
import com.memory.xzp.utils.metaData.VideoMetadataParserUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoPostProcessingServiceImplTest {

    @Mock
    private MinioOSSUtil minioOSSUtil;

    @Mock
    private VideoMetadataParserUtil videoMetadataParserUtil;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private VideoMetaDataMapper videoMetaDataMapper;

    @InjectMocks
    private VideoPostProcessingServiceImpl service;

    @Test
    void processGeneratesDeterministicCoverAndUpsertsMetadata() {
        ReflectionTestUtils.setField(service, "videoTempDirectory", "target/test-video-temp");
        FileEntity file = videoFile();
        VideoMetaData metadata = new VideoMetaData();
        metadata.setWidth(1920);
        metadata.setHeight(1080);
        metadata.setRotation(90D);
        metadata.setDuration(12.5D);
        metadata.setLatitude(30.1D);
        metadata.setLongitude(120.2D);
        when(videoMetaDataMapper.selectById("video-1")).thenReturn(null);
        when(videoMetadataParserUtil.GetVideoMetadata(anyString())).thenReturn(metadata);
        when(videoMetadataParserUtil.extractCover(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(minioOSSUtil.getFileUrl("thumbnail/video/video-1.jpg"))
                .thenReturn("http://minio/thumbnail/video/video-1.jpg");

        service.process(file);

        verify(minioOSSUtil).downloadToFile(org.mockito.ArgumentMatchers.eq("file/video-1.mp4"), any());
        verify(minioOSSUtil).uploadToOSS(
                org.mockito.ArgumentMatchers.eq("thumbnail/video/video-1.jpg"),
                any(InputStream.class),
                org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("image/jpeg")
        );
        ArgumentCaptor<FileEntity> update = ArgumentCaptor.forClass(FileEntity.class);
        verify(fileMapper).updateById(update.capture());
        assertEquals(1080, update.getValue().getWidth());
        assertEquals(1920, update.getValue().getHeight());
        assertEquals("thumbnail/video/video-1.jpg", update.getValue().getThumbnailObjectName());
        verify(videoMetaDataMapper).insert(metadata);
    }

    @Test
    void processSkipsAlreadyCompletedVideo() {
        ReflectionTestUtils.setField(service, "videoTempDirectory", "target/test-video-temp");
        FileEntity file = videoFile();
        file.setThumbnailObjectName("thumbnail/video/video-1.jpg");
        when(videoMetaDataMapper.selectById("video-1")).thenReturn(new VideoMetaData());

        service.process(file);

        verify(minioOSSUtil, never()).downloadToFile(anyString(), any());
        verify(videoMetadataParserUtil, never()).GetVideoMetadata(anyString());
        verify(fileMapper, never()).updateById(any(FileEntity.class));
    }

    private FileEntity videoFile() {
        FileEntity file = new FileEntity();
        file.setFileId("video-1");
        file.setFileObjectName("file/video-1.mp4");
        file.setLastModifiedTime(LocalDateTime.of(2026, 6, 13, 10, 0));
        return file;
    }
}
