package com.memory.xzp.config;

import com.memory.xzp.mapper.*;
import com.memory.xzp.model.entity.*;
import com.memory.xzp.model.enums.FileStatus;
import com.memory.xzp.service.ScheduledTaskLockService;
import com.memory.xzp.utils.file.MinioOSSUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件清理定时任务
 * 定期清理已软删除的文件及其关联数据
 */
@Component
public class FileCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupTask.class);

    @Autowired
    private UserFileMapper userFileMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FileFeatureMapper fileFeatureMapper;

    @Autowired
    private FaceMapper faceMapper;

    @Autowired
    private PictureTagMapper pictureTagMapper;

    @Autowired
    private SimilarPictureMapper similarPictureMapper;

    @Autowired
    private ImageMetaDataMapper imageMetadataMapper;

    @Autowired
    private VideoMetaDataMapper videoMetaDataMapper;

    @Autowired
    private LocationMapper locationMapper;

    @Autowired
    private MinioOSSUtil minioOSSUtil;

    @Autowired
    private ScheduledTaskLockService scheduledTaskLockService;

    @Value("${app.scheduler-lock.file-cleanup-ttl-seconds:14400}")
    private long fileCleanupLockTtlSeconds;

    /**
     * 每天凌晨2点执行清理任务
     * 清理30天前软删除的文件
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupSoftDeletedFiles() {
        scheduledTaskLockService.runWithLock(
                "file:soft-delete-cleanup",
                Duration.ofSeconds(fileCleanupLockTtlSeconds),
                this::cleanupSoftDeletedFilesLocked
        );
    }

    private void cleanupSoftDeletedFilesLocked() {
        log.info("开始执行软删除文件清理任务...");
        try {
            // 计算30天前的时间
            LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
            
            // 1. 查询30天前软删除的user_file记录
            List<UserFileEntity> deletedUserFiles = userFileMapper.selectSoftDeletedFiles(thirtyDaysAgo);
            
            if (deletedUserFiles.isEmpty()) {
                log.info("没有需要清理的软删除文件");
                return;
            }
            
            log.info("找到 {} 个需要清理的软删除文件", deletedUserFiles.size());
            
            // 2. 遍历清理每个文件
            for (UserFileEntity userFile : deletedUserFiles) {
                String fileId = userFile.getFileId();
                Long userId = userFile.getUserId();
                
                try {
                    // 3. 底层文件可能被其他用户通过秒传复用。
                    // 引用数大于1时只删除当前用户关系，保留共享文件。
                    if (userFileMapper.countByFileId(fileId) > 1) {
                        userFileMapper.deleteById(userFile.getId());
                        log.info("文件仍被其他用户引用，仅清理用户关系: fileId={}, userId={}", fileId, userId);
                        continue;
                    }

                    // 4. 最后一个引用已删除，清理MinIO中的文件
                    FileEntity fileEntity = fileMapper.selectById(fileId);
                    if (fileEntity != null) {
                        fileMapper.updateStatus(fileId, FileStatus.DELETING.name(), "Soft delete cleanup started");
                        // 删除原文件
                        if (fileEntity.getFileObjectName() != null) {
                            minioOSSUtil.delete(fileEntity.getFileObjectName());
                        }
                        // 删除缩略图
                        if (fileEntity.getThumbnailObjectName() != null) {
                            minioOSSUtil.delete(fileEntity.getThumbnailObjectName());
                        }
                    }
                    
                    // 5. 清理关联表数据
                    // 删除file_feature记录
                    fileFeatureMapper.deleteByFileId(fileId);
                    // 删除face记录
                    faceMapper.deleteByFileId(fileId);
                    // 删除picture_tag记录
                    pictureTagMapper.deleteByFileId(fileId);
                    // 删除similar_picture记录
                    similarPictureMapper.deleteByFileId(fileId);
                    // 删除image_meta_data记录
                    imageMetadataMapper.deleteByFileId(fileId);
                    // 删除video_meta_data记录
                    videoMetaDataMapper.deleteByFileId(fileId);
                    // 删除location记录
                    locationMapper.deleteByFileId(fileId);
                    
                    // 6. 删除file记录
                    fileMapper.deleteById(fileId);

                    // 7. 物理清理成功后再删除最后一条用户关系，失败时可由下次任务重试。
                    userFileMapper.deleteById(userFile.getId());

                    log.info("清理文件成功: fileId={}, userId={}", fileId, userId);
                } catch (Exception e) {
                    log.error("清理文件失败: fileId={}, userId={}, error={}", fileId, userId, e.getMessage());
                    // 继续清理其他文件，不影响整体任务
                }
            }
            
            log.info("软删除文件清理任务完成");
        } catch (Exception e) {
            log.error("软删除文件清理任务失败: {}", e.getMessage());
        }
    }
}
