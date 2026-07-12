package com.memory.xzp.service.impl;

import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.VideoMetaDataMapper;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.VideoMetaData;
import com.memory.xzp.service.VideoPostProcessingService;
import com.memory.xzp.utils.file.MinioOSSUtil;
import com.memory.xzp.utils.metaData.VideoMetadataParserUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class VideoPostProcessingServiceImpl implements VideoPostProcessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoPostProcessingServiceImpl.class);

    @Resource
    private MinioOSSUtil minioOSSUtil;

    @Resource
    private VideoMetadataParserUtil videoMetadataParserUtil;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private VideoMetaDataMapper videoMetaDataMapper;

    @Value("${app.async.task.video-temp-dir:${java.io.tmpdir}}")
    private String videoTempDirectory;

    @Override
    public void process(FileEntity file) {
        if (file == null || file.getFileId() == null || file.getFileObjectName() == null
                || file.getFileObjectName().isBlank()) {
            throw new IllegalArgumentException("Video processing file data is incomplete");
        }
        if (file.getThumbnailObjectName() != null
                && !file.getThumbnailObjectName().isBlank()
                && videoMetaDataMapper.selectById(file.getFileId()) != null) {
            return;
        }

        Path tempVideo = null;
        try {
            Path tempDirectory = Path.of(videoTempDirectory).toAbsolutePath().normalize();
            Files.createDirectories(tempDirectory);
            tempVideo = Files.createTempFile(
                    tempDirectory,
                    "memory-video-task-",
                    sourceSuffix(file.getFileObjectName())
            );
            minioOSSUtil.downloadToFile(file.getFileObjectName(), tempVideo);

            VideoMetaData metadata = videoMetadataParserUtil.GetVideoMetadata(tempVideo.toString());
            if (metadata.getDateTimeOriginal() == null) {
                metadata.setDateTimeOriginal(file.getLastModifiedTime());
            }
            byte[] cover = videoMetadataParserUtil.extractCover(tempVideo.toString());
            if (cover == null || cover.length == 0) {
                throw new IllegalStateException("Video cover is empty");
            }

            String thumbnailObjectName = file.getThumbnailObjectName();
            if (thumbnailObjectName == null || thumbnailObjectName.isBlank()) {
                thumbnailObjectName = "thumbnail/video/" + file.getFileId() + ".jpg";
            }
            minioOSSUtil.uploadToOSS(
                    thumbnailObjectName,
                    new ByteArrayInputStream(cover),
                    cover.length,
                    "image/jpeg"
            );

            FileEntity update = buildFileUpdate(file.getFileId(), thumbnailObjectName, metadata);
            fileMapper.updateById(update);
            upsertMetadata(file.getFileId(), metadata);
        } catch (IOException e) {
            throw new IllegalStateException("Video temporary file processing failed", e);
        } finally {
            deleteTempFile(tempVideo);
        }
    }

    private FileEntity buildFileUpdate(
            String fileId,
            String thumbnailObjectName,
            VideoMetaData metadata
    ) {
        FileEntity update = new FileEntity();
        update.setFileId(fileId);
        update.setThumbnailObjectName(thumbnailObjectName);
        update.setThumbnailUrl(minioOSSUtil.getFileUrl(thumbnailObjectName));
        update.setWidth(metadata.getWidth());
        update.setHeight(metadata.getHeight());
        int rotation = metadata.getRotation() == null ? 0 : metadata.getRotation().intValue();
        if (rotation == 90 || rotation == 270) {
            update.setWidth(metadata.getHeight());
            update.setHeight(metadata.getWidth());
        }
        update.setMake(metadata.getMake());
        update.setModel(metadata.getModel());
        update.setDateTimeOriginal(metadata.getDateTimeOriginal());
        update.setLatitude(metadata.getLatitude());
        update.setLatitudeRef(metadata.getLatitudeRef());
        update.setLongitude(metadata.getLongitude());
        update.setLongitudeRef(metadata.getLongitudeRef());
        return update;
    }

    private void upsertMetadata(String fileId, VideoMetaData metadata) {
        metadata.setFileId(fileId);
        if (videoMetaDataMapper.selectById(fileId) == null) {
            videoMetaDataMapper.insert(metadata);
        } else {
            videoMetaDataMapper.updateById(metadata);
        }
    }

    private String sourceSuffix(String objectName) {
        int dot = objectName.lastIndexOf('.');
        if (dot < 0 || dot == objectName.length() - 1) {
            return ".video";
        }
        String suffix = objectName.substring(dot);
        return suffix.matches("\\.[A-Za-z0-9]{1,10}") ? suffix : ".video";
    }

    private void deleteTempFile(Path tempVideo) {
        if (tempVideo == null) {
            return;
        }
        try {
            Files.deleteIfExists(tempVideo);
        } catch (IOException e) {
            log.warn("Failed to delete video task temporary file: path={}", tempVideo);
        }
    }
}
