package com.memory.xzp.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.*;
import com.memory.xzp.model.dto.DownLoadInfoDTO;
import com.memory.xzp.model.dto.upload.DirectUploadRegistration;
import com.memory.xzp.model.entity.*;
import com.memory.xzp.mapper.FileFeatureMapper;
import com.memory.xzp.model.enums.FileStatus;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.model.vo.entity.FileInfo;
import com.memory.xzp.model.vo.entity.FileMetaDataVO;
import com.memory.xzp.model.vo.entity.ShareFileVO;
import com.memory.xzp.model.vo.picture.BatchGetPictureTagResponseVO;
import com.memory.xzp.model.vo.task.ImageTagTaskVO;
import com.memory.xzp.service.AlbumService;
import com.memory.xzp.service.AsyncTaskService;
import com.memory.xzp.service.FileService;
import com.memory.xzp.service.RecordService;
import com.memory.xzp.service.UserService;
import com.memory.xzp.utils.auth.RedisUtil;
import com.memory.xzp.utils.file.FileUtil;
import com.memory.xzp.utils.file.MinioOSSUtil;
import com.memory.xzp.utils.metaData.ImageMetadataParserUtil;
import com.memory.xzp.utils.picture.ImageSimilarityUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;

import static cn.dev33.satoken.SaManager.log;


/**
 * @description:
 * @author: xzp
 * @date: 2025/2/20,0:22
 */
@Service
public class FileServiceImpl implements FileService {

    @Resource
    private MinioOSSUtil minioOSSUtil;
    @Resource
    private FileUtil fileUtil;
    @Resource
    private ImageMetadataParserUtil imageMetadataParserUtil;

    @Resource
    private FileMapper fileMapper;
    @Resource
    private UserFileMapper userFileMapper;
    @Resource
    private UserStorageMapper userStorageMapper;

    @Resource
    private AlbumService albumService;

    @Resource
    private ImageMetaDataMapper imageMetadataMapper;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private UserService userService;

    @Resource
    private PictureTagMapper pictureTagMapper;

    @Resource
    private FaceMapper faceMapper;

    @Resource
    private RecordService recordService;

    @Resource
    private ImageSimilarityUtil imageSimilarityUtil;

    @Resource
    private SimilarPictureMapper similarPictureMapper;

    @Resource
    private PersonFaceMapper personFaceMapper;

    /** 地点 Mapper：用于手动修正位置时更新 location 表 */
    @Resource
    private com.memory.xzp.mapper.LocationMapper locationMapper;

    @Resource
    private AsyncTaskService asyncTaskService;
    
    @Resource
    private FileFeatureMapper fileFeatureMapper;
    
    @Resource
    private ObjectMapper objectMapper;

    @Resource(name = "fileTaskExecutor")
    private Executor fileTaskExecutor;

    @Value("${ai.feature.provider:aliyun}")
    private String featureProvider;

    @Value("${ai.feature.model:qwen3-vl-embedding}")
    private String featureModel;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean uploadImageFile(Long userId, MultipartFile multipartFile, String fileSuffix, LocalDateTime lastModifiedTime, Long albumId) {
        validateAlbum(albumId, userId);
        consumeStorage(userId, multipartFile.getSize());

        //用于计算MD5
        InputStream originalInputStream1;
        //用于上传
        InputStream originalInputStream2;
        //压缩图片上传
        InputStream thumbnailInputStream;
        byte[] fileBytes;
        try {
            originalInputStream1 = multipartFile.getInputStream();
            originalInputStream2 = multipartFile.getInputStream();
            thumbnailInputStream = multipartFile.getInputStream();
            fileBytes = multipartFile.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("文件流错误: " + e.getMessage(), e);
        }
        //计算MD5
        String fileMd5 = fileUtil.getMD5(originalInputStream1);
        // 获取图片元数据
        ImageMetaData imageMetadata = new ImageMetaData();
        try {
            imageMetadata = imageMetadataParserUtil.GetIMageMetadata(multipartFile);
        } catch (Exception e) {
            log.info(String.valueOf(e));
        }

        LocalDateTime dateTimeOriginal = imageMetadata.getDateTimeOriginal();

        if (dateTimeOriginal == null) {
            imageMetadata.setDateTimeOriginal(lastModifiedTime);
        }
        long thumbnailFileSize;
        //大于500kb进行压缩
        if (multipartFile.getSize() > 500 * 1000) {
            // 压缩图片
            ByteArrayOutputStream thumbnailArrayOutputStream = fileUtil.outputQuality(fileBytes);
            byte[] thumbnailByteArray = thumbnailArrayOutputStream.toByteArray();
            thumbnailFileSize = thumbnailByteArray.length;
            thumbnailInputStream = new ByteArrayInputStream(thumbnailByteArray);
        } else {
            thumbnailFileSize = multipartFile.getSize();
        }


        LocalDateTime uploadTime = LocalDateTime.now();
        LocalDate uploadDate = uploadTime.toLocalDate();
        //替换为合法 Minio ObjectName
        String time = uploadDate.toString();

        String fileObjectName = "file/" + time + "/" + fileMd5 + "." + fileSuffix;
        String thumbnailObjectName = "thumbnail/" + time + "/" + fileMd5 + "." + fileSuffix;
        //上传原文件
        minioOSSUtil.uploadToOSS(fileObjectName, originalInputStream2, multipartFile.getSize(), multipartFile.getContentType());
        //上传缩略图
        minioOSSUtil.uploadToOSS(thumbnailObjectName, thumbnailInputStream, thumbnailFileSize, multipartFile.getContentType());


        String fileId = UUID.randomUUID().toString().replace("-", "");
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileId(fileId);
        fileEntity.setOriginFileName(multipartFile.getOriginalFilename());
        fileEntity.setSize(multipartFile.getSize());
        fileEntity.setLastModifiedTime(lastModifiedTime);
        fileEntity.setContentType(multipartFile.getContentType());
        //类别
        fileEntity.setCategory("image");
        fileEntity.setStatus(FileStatus.PROCESSING.name());
        fileEntity.setStatusUpdateTime(uploadTime);
        fileEntity.setStatusMessage("Waiting for image feature processing");
        String fileUrl = minioOSSUtil.getFileUrl(fileObjectName);
        String thumbnailFileUrl = minioOSSUtil.getFileUrl(thumbnailObjectName);
        fileEntity.setFileUrl(fileUrl);
        fileEntity.setThumbnailUrl(thumbnailFileUrl);
        fileEntity.setFileObjectName(fileObjectName);
        fileEntity.setThumbnailObjectName(thumbnailObjectName);
        fileEntity.setMd5(fileMd5);

        fileEntity.setDateTimeOriginal(imageMetadata.getDateTimeOriginal());
        fileEntity.setWidth(imageMetadata.getWidth());
        fileEntity.setHeight(imageMetadata.getHeight());
        fileEntity.setMake(imageMetadata.getMake());
        fileEntity.setModel(imageMetadata.getModel());
        fileEntity.setLatitude(imageMetadata.getLatitude());
        fileEntity.setLatitudeRef(imageMetadata.getLatitudeRef());
        fileEntity.setLongitude(imageMetadata.getLongitude());
        fileEntity.setLongitudeRef(imageMetadata.getLongitudeRef());
        //插入数据库
        fileMapper.insert(fileEntity);
        Face face = new Face();
        face.setFileId(fileId);
        face.setUserId(userId);
        faceMapper.insert(face);
        UserFileEntity userFileEntity = new UserFileEntity();
        userFileEntity.setUserId(userId);
        userFileEntity.setFileId(fileId);
        userFileEntity.setIsDeleted(false);
        userFileEntity.setUploadTime(uploadTime);
        userFileMapper.insert(userFileEntity);
        imageMetadata.setFileId(fileId);

        //-1表示不上传到相册
        if (albumId != -1) {
            //加入相册
            List<String> list = new ArrayList<>();
            list.add(fileId);
            albumService.addPictureToAlbum(list, albumId, userId);

        }
        imageMetadataMapper.insert(imageMetadata);

        Long featureTaskId = asyncTaskService.enqueueImageFeature(fileId, userId, fileObjectName);
        Long faceTaskId = asyncTaskService.enqueueFaceAnalysis(face.getFaceId(), fileId, userId);
        Long geocodingTaskId = hasValidCoordinates(
                imageMetadata.getLatitude(),
                imageMetadata.getLongitude()
        ) ? asyncTaskService.enqueueGeocoding(fileId, userId) : null;

        // 主事务提交后记录已派发的持久化任务
        final String finalFileId = fileId;
        final Long finalUserId = userId;

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("[以图搜图] 持久化特征任务已提交: taskId={}, fileId={}, userId={}",
                        featureTaskId, finalFileId, finalUserId);
                log.info("[人脸检测] 持久化任务已提交: taskId={}, fileId={}, faceId={}, userId={}",
                        faceTaskId, finalFileId, face.getFaceId(), finalUserId);
                if (geocodingTaskId != null) {
                    log.info("[地点] 持久化任务已提交: taskId={}, fileId={}, userId={}",
                            geocodingTaskId, finalFileId, finalUserId);
                }
            }
        });

        return true;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean uploadVideoFile(Long userId, MultipartFile multipartFile, String fileSuffix, LocalDateTime lastModifiedTime, Long albumId) {
        validateAlbum(albumId, userId);
        consumeStorage(userId, multipartFile.getSize());

        //用于计算MD5
        String fileMd5;
        try (InputStream inputStream = multipartFile.getInputStream()) {
            fileMd5 = fileUtil.getMD5(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("文件MD5计算失败: " + e.getMessage(), e);
        }


        LocalDateTime uploadTime = LocalDateTime.now();

        String time = uploadTime.toLocalDate().toString();

        String fileObjectName = "file/" + time + "/" + fileMd5 + "." + fileSuffix;

        //上传原文件
        try (InputStream inputStream = multipartFile.getInputStream()) {
            minioOSSUtil.uploadToOSS(
                    fileObjectName,
                    inputStream,
                    multipartFile.getSize(),
                    multipartFile.getContentType()
            );
        } catch (IOException e) {
            throw new RuntimeException("视频上传MinIO失败: " + e.getMessage(), e);
        }

        //上传时间
        String fileId = UUID.randomUUID().toString().replace("-", "");
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileId(fileId);

        fileEntity.setOriginFileName(multipartFile.getOriginalFilename());
        fileEntity.setSize(multipartFile.getSize());

        fileEntity.setLastModifiedTime(lastModifiedTime);
        fileEntity.setDateTimeOriginal(lastModifiedTime);
        fileEntity.setContentType(multipartFile.getContentType());
        //类别
        fileEntity.setCategory("video");
        fileEntity.setStatus(FileStatus.PROCESSING.name());
        fileEntity.setStatusUpdateTime(uploadTime);
        fileEntity.setStatusMessage("Waiting for video post processing");
        String fileUrl = minioOSSUtil.getFileUrl(fileObjectName);
        fileEntity.setFileUrl(fileUrl);
        fileEntity.setFileObjectName(fileObjectName);
        String thumbnailObjectName = videoThumbnailObjectName(fileId);
        fileEntity.setThumbnailObjectName(thumbnailObjectName);
        fileEntity.setThumbnailUrl(minioOSSUtil.getFileUrl(thumbnailObjectName));
        fileEntity.setMd5(fileMd5);
        //插入数据库
        fileMapper.insert(fileEntity);
        UserFileEntity userFileEntity = new UserFileEntity();
        userFileEntity.setUserId(userId);
        userFileEntity.setFileId(fileId);
        userFileEntity.setIsDeleted(false);
        userFileEntity.setUploadTime(uploadTime);
        userFileMapper.insert(userFileEntity);
        //-1表示不上传到相册
        if (albumId != -1) {
            albumService.addPictureToAlbum(List.of(fileId), albumId, userId);
        }

        registerVideoPostCommit(fileId, userId);

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String registerDirectUpload(DirectUploadRegistration registration) {
        if (registration == null || registration.getUserId() == null
                || registration.getFileSize() == null || registration.getFileSize() <= 0
                || registration.getFileName() == null || registration.getObjectName() == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "直传文件参数错误");
        }
        int dot = registration.getFileName().lastIndexOf('.');
        if (dot <= 0 || dot == registration.getFileName().length() - 1) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件扩展名错误");
        }
        String suffix = registration.getFileName().substring(dot + 1).toLowerCase(Locale.ROOT);
        String category = fileUtil.getFileType(suffix);
        if (category.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的文件类型");
        }

        validateAlbum(registration.getAlbumId(), registration.getUserId());
        consumeStorage(registration.getUserId(), registration.getFileSize());

        LocalDateTime uploadTime = LocalDateTime.now();
        LocalDateTime lastModifiedTime = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(registration.getLastModified()),
                java.time.ZoneId.systemDefault()
        );
        String fileId = UUID.randomUUID().toString().replace("-", "");
        FileEntity fileEntity = new FileEntity();
        fileEntity.setFileId(fileId);
        fileEntity.setOriginFileName(registration.getFileName());
        fileEntity.setSize(registration.getFileSize());
        fileEntity.setLastModifiedTime(lastModifiedTime);
        fileEntity.setContentType(registration.getContentType());
        fileEntity.setCategory(category);
        fileEntity.setStatus(FileStatus.PROCESSING.name());
        fileEntity.setStatusUpdateTime(uploadTime);
        fileEntity.setStatusMessage("Waiting for direct upload post processing");
        fileEntity.setFileUrl(minioOSSUtil.getFileUrl(registration.getObjectName()));
        fileEntity.setFileObjectName(registration.getObjectName());
        fileEntity.setMd5(registration.getMd5());

        Face face = null;
        try {
            if ("image".equals(category)) {
                // 先使用原图地址展示，缩略图与元数据在事务提交后异步生成。
                fileEntity.setThumbnailObjectName(registration.getObjectName());
                fileEntity.setThumbnailUrl(fileEntity.getFileUrl());
            } else if ("video".equals(category)) {
                String thumbnailObjectName = videoThumbnailObjectName(fileId);
                fileEntity.setDateTimeOriginal(lastModifiedTime);
                fileEntity.setThumbnailObjectName(thumbnailObjectName);
                fileEntity.setThumbnailUrl(minioOSSUtil.getFileUrl(thumbnailObjectName));
            }

            fileMapper.insert(fileEntity);
            UserFileEntity userFile = new UserFileEntity();
            userFile.setUserId(registration.getUserId());
            userFile.setFileId(fileId);
            userFile.setIsDeleted(false);
            userFile.setUploadTime(uploadTime);
            userFileMapper.insert(userFile);

            if ("image".equals(category)) {
                face = new Face();
                face.setFileId(fileId);
                face.setUserId(registration.getUserId());
                faceMapper.insert(face);
            }
            if (registration.getAlbumId() != null && registration.getAlbumId() != -1) {
                albumService.addPictureToAlbum(
                        List.of(fileId),
                        registration.getAlbumId(),
                        registration.getUserId()
                );
            }
            if ("image".equals(category)) {
                registerImagePostCommit(
                        fileId,
                        registration.getUserId(),
                        registration.getObjectName(),
                        registration.getFileName(),
                        registration.getContentType(),
                        registration.getMd5(),
                        suffix,
                        lastModifiedTime,
                        face
                );
            } else {
                registerVideoPostCommit(
                        fileId,
                        registration.getUserId()
                );
            }
            return fileId;
        } catch (RuntimeException e) {
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String reuseOwnedFileIfPresent(Long userId, Long albumId, String md5) {
        if (userId == null || md5 == null || md5.isBlank()) {
            return null;
        }
        String fileId = fileMapper.selectOwnedFileIdByMd5(userId, md5);
        if (fileId == null) {
            return null;
        }
        userFileMapper.updateUseFile(userId, List.of(fileId), false);
        if (albumId != null && albumId != -1) {
            albumService.addPictureToAlbum(List.of(fileId), albumId, userId);
        }
        return fileId;
    }

    private void consumeStorage(Long userId, long size) {
        if (userStorageMapper.consumeSpace(userId, size) != 1) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "存储空间不足");
        }
    }

    private void validateAlbum(Long albumId, Long userId) {
        if (albumId != null && albumId != -1 && albumService.selectAlbumById(albumId, userId) == null) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "相册不存在或无权访问");
        }
    }

    private void copyImageMetadata(FileEntity fileEntity, ImageMetaData metadata) {
        fileEntity.setDateTimeOriginal(metadata.getDateTimeOriginal());
        fileEntity.setWidth(metadata.getWidth());
        fileEntity.setHeight(metadata.getHeight());
        fileEntity.setMake(metadata.getMake());
        fileEntity.setModel(metadata.getModel());
        fileEntity.setLatitude(metadata.getLatitude());
        fileEntity.setLatitudeRef(metadata.getLatitudeRef());
        fileEntity.setLongitude(metadata.getLongitude());
        fileEntity.setLongitudeRef(metadata.getLongitudeRef());
    }

    private void registerImagePostCommit(
            String fileId,
            Long userId,
            String objectName,
            String fileName,
            String contentType,
            String expectedMd5,
            String suffix,
            LocalDateTime lastModifiedTime,
            Face face
    ) {
        Long featureTaskId = asyncTaskService.enqueueImageFeature(fileId, userId, objectName);
        Long faceTaskId = face == null
                ? null
                : asyncTaskService.enqueueFaceAnalysis(face.getFaceId(), fileId, userId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("直传图片持久化特征任务已提交: taskId={}, fileId={}", featureTaskId, fileId);
                submitFileTask(
                        "direct-image-metadata:" + fileId,
                        () -> processDirectImage(
                                fileId,
                                userId,
                                objectName,
                                fileName,
                                contentType,
                                expectedMd5,
                                suffix,
                                lastModifiedTime
                        )
                );
                if (faceTaskId != null) {
                    log.info("直传图片持久化人脸任务已提交: taskId={}, fileId={}, faceId={}",
                            faceTaskId, fileId, face.getFaceId());
                }
            }
        });
    }

    private void processDirectImage(
            String fileId,
            Long userId,
            String objectName,
            String fileName,
            String contentType,
            String expectedMd5,
            String suffix,
            LocalDateTime lastModifiedTime
    ) {
        try {
            byte[] imageBytes = minioOSSUtil.getFileBytes(objectName);
            String actualMd5 = fileUtil.getMD5(new ByteArrayInputStream(imageBytes));
            if (expectedMd5 != null && !expectedMd5.equalsIgnoreCase(actualMd5)) {
                log.error("直传图片 MD5 校验失败: fileId={}, expected={}, actual={}",
                        fileId, expectedMd5, actualMd5);
                fileMapper.updateStatusIfNotDeleting(
                        fileId,
                        FileStatus.FAILED.name(),
                        "Direct image MD5 check failed"
                );
                return;
            }

            ImageMetaData metadata;
            try {
                metadata = imageMetadataParserUtil.getImageMetadata(
                        new ByteArrayInputStream(imageBytes),
                        fileName
                );
            } catch (Exception e) {
                log.warn("读取直传图片元数据失败: fileId={}, error={}", fileId, e.getMessage());
                metadata = new ImageMetaData();
            }
            if (metadata.getDateTimeOriginal() == null) {
                metadata.setDateTimeOriginal(lastModifiedTime);
            }

            byte[] thumbnailBytes = imageBytes.length > 500 * 1000
                    ? fileUtil.outputQuality(imageBytes).toByteArray()
                    : imageBytes;
            String thumbnailObjectName = "thumbnail/" + LocalDate.now() + "/"
                    + userId + "/" + fileId + "." + suffix;
            minioOSSUtil.uploadToOSS(
                    thumbnailObjectName,
                    new ByteArrayInputStream(thumbnailBytes),
                    thumbnailBytes.length,
                    contentType
            );

            FileEntity update = new FileEntity();
            update.setFileId(fileId);
            update.setMd5(actualMd5);
            update.setThumbnailObjectName(thumbnailObjectName);
            update.setThumbnailUrl(minioOSSUtil.getFileUrl(thumbnailObjectName));
            copyImageMetadata(update, metadata);
            fileMapper.updateById(update);

            metadata.setFileId(fileId);
            if (imageMetadataMapper.selectOne(
                    new QueryWrapper<ImageMetaData>().eq("file_id", fileId)
            ) == null) {
                imageMetadataMapper.insert(metadata);
            } else {
                imageMetadataMapper.update(
                        metadata,
                        new UpdateWrapper<ImageMetaData>().eq("file_id", fileId)
                );
            }
            fileMapper.updateStatusIfCurrent(
                    fileId,
                    FileStatus.PROCESSING.name(),
                    FileStatus.READY.name(),
                    "Direct image post processing completed"
            );
            if (metadata.getLatitude() != null && metadata.getLongitude() != null) {
                Long taskId = enqueueGeocodingSafely(fileId, userId);
                log.info("直传图片地理编码任务已提交: taskId={}, fileId={}", taskId, fileId);
            }
        } catch (Exception e) {
            fileMapper.updateStatusIfNotDeleting(
                    fileId,
                    FileStatus.FAILED.name(),
                    statusMessage("Direct image post processing failed: " + e.getMessage())
            );
            log.warn("直传图片后处理失败: fileId={}, error={}", fileId, e.getMessage());
        }
    }

    private void registerVideoPostCommit(
            String fileId,
            Long userId
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            enqueueVideoProcessingSafely(fileId, userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                enqueueVideoProcessingSafely(fileId, userId);
            }
        });
    }

    private void enqueueVideoProcessingSafely(String fileId, Long userId) {
        try {
            Long taskId = asyncTaskService.enqueueVideoProcessing(fileId, userId);
            log.info("视频后处理持久化任务已提交: taskId={}, fileId={}", taskId, fileId);
        } catch (RuntimeException e) {
            fileMapper.updateStatusIfNotDeleting(
                    fileId,
                    FileStatus.FAILED.name(),
                    statusMessage("Video processing task enqueue failed: " + e.getMessage())
            );
            log.warn("视频上传已完成，但提交视频后处理任务失败: fileId={}, userId={}", fileId, userId, e);
        }
    }

    private Long enqueueGeocodingSafely(String fileId, Long userId) {
        try {
            return asyncTaskService.enqueueGeocoding(fileId, userId);
        } catch (RuntimeException e) {
            log.warn("Direct image geocoding task enqueue failed: fileId={}, userId={}", fileId, userId, e);
            return null;
        }
    }

    private String videoThumbnailObjectName(String fileId) {
        return "thumbnail/video/" + fileId + ".jpg";
    }

    private String statusMessage(String message) {
        if (message == null || message.length() <= 500) {
            return message;
        }
        return message.substring(0, 500);
    }

    private void logPersistentTaskAfterCommit(String taskName, Long taskId, String fileId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.info("{}持久化任务已提交: taskId={}, fileId={}", taskName, taskId, fileId);
            }
        });
    }

    @Override
    public Boolean checkIsUpload(Long userId, Long albumId, String fileMd5) {
        return reuseOwnedFileIfPresent(userId, albumId, fileMd5) != null;
    }

    @Override
    public Long validateShareAccess(String shareToken, List<String> fileIds) {
        if (shareToken == null || shareToken.isBlank() || fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "分享令牌和文件不能为空");
        }
        Map<String, String> shareMap = redisUtil.hgetAllAsString("share:link:" + shareToken);
        if (shareMap == null || shareMap.isEmpty()) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "分享链接不存在或已过期");
        }
        try {
            Long sharerId = Long.parseLong(shareMap.get("userId"));
            List<String> sharedFileIds = parseFileIds(shareMap.get("fileIds"));
            Set<String> requested = new HashSet<>(fileIds);
            if (sharedFileIds == null || !new HashSet<>(sharedFileIds).containsAll(requested)) {
                throw new BusinessException(StatusCode.NO_AUTH_ERROR, "请求包含未分享的文件");
            }
            if (fileMapper.selectFileByIds(new ArrayList<>(requested), sharerId).size() != requested.size()) {
                throw new BusinessException(StatusCode.NO_AUTH_ERROR, "分享文件已失效或无权访问");
            }
            return sharerId;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "分享链接数据异常");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSharePicture(Long userId, Long albumId, List<String> fileIds, String shareToken) {
        Long sharerId = validateShareAccess(shareToken, fileIds);
        validateAlbum(albumId, userId);

        List<String> distinctFileIds = fileIds.stream().distinct().toList();
        Map<String, FileEntity> sharedFiles = fileMapper
                .selectFileByIds(distinctFileIds, sharerId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(FileEntity::getFileId, file -> file));
        List<String> missingFileIds = distinctFileIds.stream()
                .filter(fileId -> fileMapper.isUserHasFile(fileId, userId) == null)
                .toList();
        long requiredSpace = missingFileIds.stream()
                .map(sharedFiles::get)
                .filter(Objects::nonNull)
                .mapToLong(FileEntity::getSize)
                .sum();
        if (requiredSpace > 0) {
            consumeStorage(userId, requiredSpace);
        }

        for (String fileId : missingFileIds) {
            String userHasFile = fileMapper.isUserHasFile(fileId, userId);
            //用户没有这些文件
            if (userHasFile == null) {
                UserFileEntity userFileEntity = new UserFileEntity();
                userFileEntity.setUserId(userId);
                userFileEntity.setFileId(fileId);
                userFileEntity.setIsDeleted(false);
                userFileEntity.setUploadTime(LocalDateTime.now());
                userFileMapper.insert(userFileEntity);
            }
        }
        //如果要求保存到相册
        if (albumId != -1) {
            albumService.addPictureToAlbum(distinctFileIds, albumId, userId);
        }

    }


    public Page<FileInfoListVO> getFileInfoList(Integer current, Integer size, String orderType, String orderKeyword, String imageTypeText, String locationLevel, String locationValue, String tagFilter, Long userId, Long albumId, Boolean isDeleted) {
        Page<FileInfoListVO> page = new Page<>(current, size);
        List<FileInfoListVO> fileInfoListVOPage = fileMapper.getFileInfoList(page, orderType, orderKeyword, imageTypeText, locationLevel, locationValue, tagFilter, userId, albumId, false);
        page.setRecords(fileInfoListVOPage);
        return page;
    }

    @Override
    public Page<FileInfoListVO> getTagFileInfo(Integer current, Integer size, Long userId, String orderType, String orderKeyword, String imageTypeText, String tagName) {
        Page<FileInfoListVO> page = new Page<>(current, size);
        List<FileInfoListVO> fileInfoListVOPage = fileMapper.getTagFileInfo(page, userId, orderType, orderKeyword, imageTypeText, tagName, false);
        page.setRecords(fileInfoListVOPage);
        return page;
    }

    @Override
    public List<FileInfoListVO> getSimilarFileList(String imageTypeText,Long userId) {


        return fileMapper.selectAllSimilarPicture(userId, imageTypeText);
    }

    @Override
    public void createSimilarFileList(Double similarity, Integer size, Long userId) {
        QueryWrapper<SimilarPicture> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id",userId);
        similarPictureMapper.delete(queryWrapper);
        List<FileInfo> fileInfos = fileMapper.selectAllFileByUserId(userId,size);
        Map<Integer, List<FileInfo>> picturesMap = new HashMap<>();
        int groupId = 0;  // 分组ID自增器
        for (FileInfo currentFile : fileInfos) {
            try {
                // 1. 获取缩略图字节数据
                String thumbnailObjectName = currentFile.getThumbnailObjectName();
                byte[] currentBytes = minioOSSUtil.getFileBytes(thumbnailObjectName);

                boolean isGrouped = false;

                // 2. 遍历已有分组寻找相似组
                for (Map.Entry<Integer, List<FileInfo>> entry : picturesMap.entrySet()) {
                    // 取组内第一个文件作为代表进行比较
                    FileInfo sampleFile = entry.getValue().get(0);
                    byte[] sampleBytes = minioOSSUtil.getFileBytes(sampleFile.getThumbnailObjectName());
                    if (sampleBytes == null) continue;

                    // 3. 使用轻量图像指纹计算相似度
                    if (currentBytes == null || currentBytes.length == 0) {
                        break;
                    }
                    boolean samePicture = imageSimilarityUtil.isSamePicture(similarity, currentBytes, sampleBytes);
                    if (samePicture) {
                        entry.getValue().add(currentFile);
                        isGrouped = true;
                        break;
                    }
                }
                // 4. 若无相似组则创建新组
                if (!isGrouped) {
                    List<FileInfo> newGroup = new ArrayList<>();
                    newGroup.add(currentFile);
                    picturesMap.put(groupId++, newGroup);
                }
            } catch (Exception e) {
                // 处理文件读取异常
                log.error("处理文件失败: {}", currentFile.getFileId(), e);
            }
        }
        for (Map.Entry<Integer, List<FileInfo>> entry : picturesMap.entrySet()) {
            if(entry.getValue().size()<2){
                continue;
            }
            List<FileInfo> list = entry.getValue();
            FileInfo fileInfo = list.get(0);
            list.remove(0);
            SimilarPicture mainPicture = new SimilarPicture();
            mainPicture.setFileId(fileInfo.getFileId());
            mainPicture.setUserId(userId);
            String id = UUID.randomUUID().toString().replace("-", "");
            mainPicture.setSimilarId(id);
            similarPictureMapper.insert(mainPicture);
            for (FileInfo file : list) {
                SimilarPicture similar = new SimilarPicture();
                similar.setSimilarId(id);
                similar.setFileId(file.getFileId());
                similar.setUserId(userId);
                similarPictureMapper.insert(similar);
            }
        }
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean setIsDeleted(List<String> fileIds, boolean isDeleted, Long userId) {
        int update = fileMapper.setIsDeletedByFileIds(fileIds, isDeleted, userId);
        //从相册里删除
        albumService.removePictureFromAlbum(fileIds, null, userId);

        // 处理关联表
        if (isDeleted) {
            // 软删除时，删除相关的关联记录
            // 1. 删除 picture_tag 记录
            QueryWrapper<PictureTag> pictureTagQueryWrapper = new QueryWrapper<>();
            pictureTagQueryWrapper.in("file_id", fileIds);
            pictureTagMapper.delete(pictureTagQueryWrapper);

            // 2. 删除 similar_picture 记录
            QueryWrapper<SimilarPicture> similarPictureQueryWrapper = new QueryWrapper<>();
            similarPictureQueryWrapper.in("file_id", fileIds)
                                   .eq("user_id", userId);
            similarPictureMapper.delete(similarPictureQueryWrapper);

            // 3. 保留 person_face 记录，只修改 user_file.is_deleted 状态
            // 这样图片从回收站恢复后，能自动重新在人物模块显示
        }

        return update > 0;
    }

    @Override
    public void downloadFileByIds(HttpServletResponse response, List<String> fileIds, Long userId) {
        // 1. 获取文件列表
        List<FileEntity> fileEntities = fileMapper.selectFileByIds(fileIds, userId);
        for (FileEntity fileEntity : fileEntities) {
            minioOSSUtil.download(response, fileEntity);
        }
    }

    @Override
    public String getDownloadToken(List<String> fileIds, Long userId) {
        String Token = (UUID.randomUUID().toString().replace("-", ""));
        // 存储到临时缓存（Sa-Token内置缓存API）
        DownLoadInfoDTO downLoadInfoDTO = new DownLoadInfoDTO();
        downLoadInfoDTO.setFileIds(fileIds);
        downLoadInfoDTO.setUserId(userId);
        redisUtil.set("tempDownload:" + Token, downLoadInfoDTO, 60, TimeUnit.MINUTES);
        return Token;
    }


    /**
     * 获取下载相册的token
     *
     * @param albumId
     * @param userId
     * @return
     */
    @Override
    public String getDownloadAlbumToken(Long albumId, Long userId) {
        String Token = (UUID.randomUUID().toString().replace("-", ""));
        // 存储到临时缓存（Sa-Token内置缓存API）
        DownLoadInfoDTO downLoadInfoDTO = new DownLoadInfoDTO();
        downLoadInfoDTO.setAlbumId(albumId);
        downLoadInfoDTO.setUserId(userId);
        redisUtil.set("tempDownload:" + Token, downLoadInfoDTO, 60, TimeUnit.MINUTES);
        return Token;
    }

    @Override
    public void downloadAlbumByToken(HttpServletRequest request, HttpServletResponse response, Long albumId, Long userId) {
        List<String> fileIds = fileMapper.selectFileIdByAlbumId(albumId, userId);
        if (fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "相册没有文件!");
        }
        Album album = albumService.getById(albumId);
        // 2. 动态生成文件名
        String downloadZipName = "Memory_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH时mm分")) +
                "_" + album.getAlbumName() + fileIds.size() + "项.zip";
        this.downloadZipFileByIds(response, fileIds, userId, downloadZipName);
        //日志记录
        recordService.createRecordLog("下载相册:" + album.getAlbumName(), fileIds.size(), userId, request);
    }


    @Override
    public void downloadZipFileByIds(HttpServletResponse response, List<String> fileIds, Long userId, String downloadZipName) {
        try {
            List<FileEntity> fileEntities = fileMapper.selectFileByIds(fileIds, userId);
            // 1. 设置响应头（不设置Content-Length）
            response.setContentType("application/zip");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Transfer-Encoding", "binary");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);

            long totalSize = minioOSSUtil.calculateZipTotalSize(fileEntities);
            response.setContentLengthLong(totalSize);


            String encodedFileName = URLEncoder.encode(downloadZipName, StandardCharsets.UTF_8).replace("+", "%20");

            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encodedFileName);


            // 3. 流式生成ZIP并传输
            try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
                Map<String, Integer> fileNameCounter = new HashMap<>();
                for (FileEntity fileEntity : fileEntities) {
                    minioOSSUtil.downloadFileToZip(zos, fileEntity, fileNameCounter);
                    // 强制刷新输出流
                    zos.flush();
                    response.flushBuffer(); // 关键：确保数据实时推送
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public FileMetaDataVO selectFileMetaDataById(String fileId, Long userId) {
        return fileMapper.selectFileMetaDataById(fileId, userId);
    }

    @Override
    public FileMetaDataVO selectSharedFileMetaDataById(String fileId, String shareToken) {
        if (fileId == null || fileId.isBlank() || shareToken == null || shareToken.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "fileId和shareToken不能为空");
        }

        Map<String, String> shareMap = redisUtil.hgetAllAsString("share:link:" + shareToken);
        if (shareMap == null || shareMap.isEmpty()) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "分享链接已过期或不存在");
        }

        Long sharerId;
        List<String> sharedFileIds;
        try {
            sharerId = Long.parseLong(shareMap.get("userId"));
            sharedFileIds = parseFileIds(shareMap.get("fileIds"));
        } catch (Exception e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "分享链接数据异常");
        }

        if (sharedFileIds == null || !sharedFileIds.contains(fileId)) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "无权访问该文件元数据");
        }

        FileMetaDataVO metadata = fileMapper.selectFileMetaDataById(fileId, sharerId);
        if (metadata == null) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "文件不存在或已被删除");
        }
        return metadata;
    }

    @Override
    public String createShareUrl(List<String> fileIds, Long userId, Integer shareDay) {
        List<String> distinctFileIds = fileIds.stream().distinct().toList();
        if (distinctFileIds.size() > 500) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "单次最多分享 500 个文件");
        }
        List<FileEntity> ownedFiles = fileMapper.selectFileByIds(distinctFileIds, userId);
        if (ownedFiles.size() != distinctFileIds.size()) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "包含不存在或无权分享的文件");
        }

        String shareKey = UUID.randomUUID().toString().replace("-", "");
        // 构造Redis存储数据
        String redisKey = "share:link:" + shareKey;
        Map<String, String> shareData = new HashMap<>();
        shareData.put("userId", userId.toString());
        shareData.put("fileIds", serializeFileIds(distinctFileIds));
        shareData.put("visitCount", "0");

        // 一次性设置Hash和过期时间
        redisUtil.hmset(redisKey, shareData, shareDay * 24, TimeUnit.HOURS);

        return shareKey;
    }


    @Override
    public String createAlbumShareUrl(List<Long> albumIds, Long userId, Integer shareDay) {
        List<String> fileIds = new ArrayList<>();
        for (Long albumId : albumIds) {
            List<String> res = fileMapper.selectFileIdByAlbumId(albumId, userId);
            fileIds.addAll(res);
        }

        return this.createShareUrl(fileIds, userId, shareDay);
    }

    @Override
    public ShareFileVO getShareInfo(String shareKey) {
        shareKey = "share:link:" + shareKey;

        Map<String, String> shareMap = redisUtil.hgetAllAsString(shareKey);
        if (shareMap == null || shareMap.isEmpty()) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "分享链接已过期或不存在");
        }
        Long expireMillis = redisUtil.getExpire(shareKey);
        if (expireMillis == null || expireMillis <= 0) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "分享链接已过期或不存在");
        }
        LocalDateTime expireTime = LocalDateTime.now().plus(expireMillis, ChronoUnit.MILLIS);
        Long sharerId = Long.parseLong((String) shareMap.get("userId"));
        List<String> fileIds = parseFileIds(shareMap.get("fileIds"));
        Long visitCount = Long.parseLong((String) shareMap.get("visitCount"));


        // 3. 获取用户信息
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("id", sharerId);
        User user = userService.getOne(queryWrapper);
        if (user == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "分享用户不存在");
        }
        List<FileInfoListVO> fileInfoListVOS = fileMapper.selectFileInfoListByIds(fileIds, sharerId);
        ShareFileVO vo = new ShareFileVO();
        vo.setExpireTime(expireTime);
        vo.setFileInfoList(fileInfoListVOS);
        vo.setSharePersonName(user.getUserName());
        vo.setSharePersonAvatar(user.getAvatarUrl());
        vo.setVisitCount(visitCount);
        return vo;
    }

    @Override
    public boolean addTag(List<String> fileIds,String imageType, String tagName, Long userId) {
        List<String> normalizedFileIds = normalizeFileIdsForBatch(fileIds);
        if (normalizedFileIds.isEmpty()) {
            return true;
        }
        List<String> ownedFileIds = fileMapper.selectOwnedActiveFileIds(normalizedFileIds, userId);
        for (String fileId : ownedFileIds) {
            pictureTagMapper.insertIfAbsent(fileId, imageType, tagName);
        }
        return true;
    }

    @Override
    public boolean removeTag(List<String> fileIds, String tagName, Long userId) {
        List<String> normalizedFileIds = normalizeFileIdsForBatch(fileIds);
        if (normalizedFileIds.isEmpty()) {
            return true;
        }
        List<String> ownedFileIds = fileMapper.selectOwnedActiveFileIds(normalizedFileIds, userId);
        if (!ownedFileIds.isEmpty()) {
            pictureTagMapper.deleteByFileIdsAndTag(ownedFileIds, tagName, userId);
        }

        return true;
    }

    @Override
    public ImageTagTaskVO getPictureTag(
            String fileId,
            String thumbnailObjectName,
            Boolean autoAddTag,
            Long userId
    ) {
        Long taskId = asyncTaskService.enqueueImageTag(
                fileId,
                userId,
                Boolean.TRUE.equals(autoAddTag)
        );
        return new ImageTagTaskVO(taskId, fileId, "PENDING");
    }

    @Override
    public BatchGetPictureTagResponseVO batchGetPictureTag(List<String> fileIds, Boolean autoAddTag, Long userId) {
        LinkedHashSet<String> distinctFileIds = new LinkedHashSet<>();
        if (fileIds != null) {
            for (String fileId : fileIds) {
                if (fileId != null && !fileId.isBlank()) {
                    distinctFileIds.add(fileId.trim());
                }
            }
        }
        if (distinctFileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "fileIds不能为空");
        }

        List<String> orderedFileIds = new ArrayList<>(distinctFileIds);
        BatchGetPictureTagResponseVO response = new BatchGetPictureTagResponseVO();
        List<BatchGetPictureTagResponseVO.Item> items = orderedFileIds.stream()
                .map(fileId -> submitImageTagTaskItem(
                        fileId,
                        Boolean.TRUE.equals(autoAddTag),
                        userId
                ))
                .toList();
        response.setItems(items);

        BatchGetPictureTagResponseVO.Statistics statistics = new BatchGetPictureTagResponseVO.Statistics();
        statistics.setTotal(response.getItems().size());
        statistics.setSuccess((int) response.getItems().stream().filter(item -> Boolean.TRUE.equals(item.getSuccess())).count());
        statistics.setFailed(statistics.getTotal() - statistics.getSuccess());
        response.setStatistics(statistics);
        return response;
    }

    private BatchGetPictureTagResponseVO.Item submitImageTagTaskItem(
            String fileId,
            boolean autoAddTag,
            Long userId
    ) {
        BatchGetPictureTagResponseVO.Item item = new BatchGetPictureTagResponseVO.Item();
        item.setFileId(fileId);
        try {
            Long taskId = asyncTaskService.enqueueImageTag(fileId, userId, autoAddTag);
            item.setTaskId(taskId);
            item.setStatus("PENDING");
            item.setSuccess(true);
        } catch (Exception e) {
            item.setSuccess(false);
            item.setError(e.getMessage());
        }
        return item;
    }

    @Override
    public List<String> selectTagByFileId(String fileId, Long userId) {
        return pictureTagMapper.selectTagsByFileId(fileId, userId);
    }

    @Override
    public List<String> selectAllModels(Long userId) {
        return fileMapper.selectAllModels(userId);
    }

    /**
     * 由于minio获取的url最长为7天，所以需要定时更换数据库Url
     */
    @Override
    public void cronUpdateFileUrl() {
        Wrapper<FileEntity> queryWrapper1 = new QueryWrapper<>();
        //更新照片链接
        List<FileEntity> fileEntities = fileMapper.selectList(queryWrapper1);
        for (FileEntity fileEntity : fileEntities) {
            String fileId = fileEntity.getFileId();
            String fileObjectName = fileEntity.getFileObjectName();
            String thumbnailObjectName = fileEntity.getThumbnailObjectName();
            String fileUrl = minioOSSUtil.getFileUrl(fileObjectName);
            String thumbnailUrl = minioOSSUtil.getFileUrl(thumbnailObjectName);
            UpdateWrapper<FileEntity> updateWrapper1 = new UpdateWrapper<>();
            updateWrapper1.eq("file_id", fileId);
            updateWrapper1.set("file_url", fileUrl);
            updateWrapper1.set("thumbnail_url", thumbnailUrl);
            fileMapper.update(updateWrapper1);
        }
        //更新人物照片链接
        Wrapper<Face> queryWrapper2 = new QueryWrapper<>();
        List<Face> faces = faceMapper.selectList(queryWrapper2);

        for (Face face : faces) {
            if (face.getPersonCoverUrl() == null) {
                continue;
            }
            Long faceId = face.getFaceId();
            String personObjectName = face.getPersonObjectName();
            UpdateWrapper<Face> updateWrapper2 = new UpdateWrapper<>();
            String fileUrl = minioOSSUtil.getFileUrl(personObjectName);
            updateWrapper2.eq("face_id", faceId);
            updateWrapper2.set("person_cover_url", fileUrl);
            faceMapper.update(updateWrapper2);
        }
        //更新用户头像链接
        Wrapper<User> queryWrapper3 = new UpdateWrapper<>();
        BaseMapper<User> userMapper = userService.getBaseMapper();
        List<User> users = userMapper.selectList(queryWrapper3);
        for (User user : users) {
            if (user.getAvatarUrl() == null) {
                continue;
            }
            Long userId = user.getUserId();
            String avatarObjectName = user.getAvatarObjectName();
            UpdateWrapper<User> updateWrapper3 = new UpdateWrapper<>();
            String fileUrl = minioOSSUtil.getFileUrl(avatarObjectName);
            updateWrapper3.eq("id", userId);
            updateWrapper3.set("avatar_url", fileUrl);
            userMapper.update(updateWrapper3);
        }
    }

    /**
     * 手动修正图片地理位置。
     * 1. 校验当前用户对该文件的所有权
     * 2. 更新 file.location（图片详情页展示用）
     * 3. 更新或插入 location 表的 city 字段（使图片能出现在地点相册聚合中）
     */
    @Override
    public void updateLocation(Long userId, String fileId, String locationValue) {
        // 权限校验：user_file 中必须存在该用户与文件的关联且未被删除
        UserFileEntity uf = userFileMapper.selectOne(
                new QueryWrapper<UserFileEntity>()
                        .eq("user_id", userId)
                        .eq("file_id", fileId)
                        .eq("is_deleted", false));
        if (uf == null) {
            throw new com.memory.xzp.exception.BusinessException(
                    com.memory.xzp.exception.StatusCode.NO_AUTH_ERROR);
        }

        // 更新 file.location 展示字段
        UpdateWrapper<FileEntity> fw = new UpdateWrapper<>();
        fw.eq("file_id", fileId).set("location", locationValue);
        fileMapper.update(fw);

        // 更新或插入 location 表，确保地点相册 INNER JOIN 能匹配到该文件
        com.memory.xzp.model.entity.Location existing = locationMapper.selectOne(
                new QueryWrapper<com.memory.xzp.model.entity.Location>()
                        .eq("file_id", fileId));
        if (existing == null) {
            // 首次手动设置：新建一条 location 记录，city = 用户输入值
            com.memory.xzp.model.entity.Location loc =
                    new com.memory.xzp.model.entity.Location(
                            fileId, "中国", "", locationValue, "", "", "", "", locationValue);
            locationMapper.insert(loc);
        } else {
            // 已有解码结果：仅覆盖 city 和 full_address，保留省/区等原始数据
            UpdateWrapper<com.memory.xzp.model.entity.Location> lw = new UpdateWrapper<>();
            lw.eq("file_id", fileId)
                    .set("city", locationValue)
                    .set("full_address", locationValue);
            locationMapper.update(lw);
        }
        log.info("[地点] 手动修正位置成功: fileId={}, location={}", fileId, locationValue);
    }

    /**
     * 手动设置 GPS 坐标并重新触发逆地理编码。
     * 流程：
     *   1. 校验用户对该文件的所有权
     *   2. 更新 file 表的 latitude / longitude 字段
     *   3. 清除旧的 location 文字（置为"解析中"），供定时任务/即时编码填充
     *   4. 创建可恢复的持久化地理编码任务
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGpsCoordinate(Long userId, String fileId, Double latitude, Double longitude) {
        // 1. 权限校验
        UserFileEntity uf = userFileMapper.selectOne(
                new QueryWrapper<UserFileEntity>()
                        .eq("user_id", userId)
                        .eq("file_id", fileId)
                        .eq("is_deleted", false));
        if (uf == null) {
            throw new com.memory.xzp.exception.BusinessException(
                    com.memory.xzp.exception.StatusCode.NO_AUTH_ERROR);
        }

        // 2. 更新 file 表中的 GPS 坐标及 location 字段（先清空，等逆地理编码回填）
        UpdateWrapper<FileEntity> fw = new UpdateWrapper<>();
        fw.eq("file_id", fileId)
                .set("latitude", latitude)
                .set("longitude", longitude)
                .set("location", "解析中");
        fileMapper.update(fw);
        log.info("[地点] 手动设置GPS坐标: fileId={}, lat={}, lon={}", fileId, latitude, longitude);

        Long taskId = asyncTaskService.enqueueGeocoding(fileId, userId);
        logPersistentTaskAfterCommit("手动 GPS 地理编码", taskId, fileId);
    }

    private void submitFileTask(String taskName, Runnable task) {
        try {
            CompletableFuture.runAsync(task, fileTaskExecutor);
        } catch (RejectedExecutionException e) {
            log.warn("后台任务队列已满，任务被拒绝: taskName={}", taskName);
        }
    }

    private boolean hasValidCoordinates(Double latitude, Double longitude) {
        return latitude != null
                && longitude != null
                && Double.isFinite(latitude)
                && Double.isFinite(longitude)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    private String serializeFileIds(List<String> fileIds) {
        try {
            return objectMapper.writeValueAsString(fileIds);
        } catch (JsonProcessingException e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "分享文件列表序列化失败");
        }
    }

    private List<String> parseFileIds(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "分享文件列表格式异常");
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "分享文件列表格式异常");
        }
    }

    private List<String> normalizeFileIdsForBatch(List<String> fileIds) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (fileIds == null) {
            return new ArrayList<>();
        }
        for (String fileId : fileIds) {
            if (fileId != null && !fileId.isBlank()) {
                normalized.add(fileId.trim());
            }
        }
        return new ArrayList<>(normalized);
    }
}
