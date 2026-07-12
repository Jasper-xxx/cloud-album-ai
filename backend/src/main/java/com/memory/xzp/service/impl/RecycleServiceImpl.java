package com.memory.xzp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.mapper.FaceMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PersonMapper;
import com.memory.xzp.mapper.UserFileMapper;
import com.memory.xzp.mapper.UserStorageMapper;
import com.memory.xzp.model.entity.Face;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.UserFileEntity;
import com.memory.xzp.model.enums.FileStatus;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.service.RecycleService;
import com.memory.xzp.utils.file.MinioOSSUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @description:
 * @author: xzp
 * @date: 2025/3/5,19:44
 */
@Service
public class RecycleServiceImpl implements RecycleService {

    private static final Logger log = LoggerFactory.getLogger(RecycleServiceImpl.class);

    @Resource
    FileMapper fileMapper;
    @Resource
    UserFileMapper userFileMapper;
    @Resource
    UserStorageMapper userStorageMapper;
    @Resource
    MinioOSSUtil minioOSSUtil;
    /** 用于删除文件前查询受影响的人脸 userId，以便精确清理空人物分组 */
    @Resource
    FaceMapper faceMapper;
    /** 清理删除照片后残留的空人物分组 */
    @Resource
    PersonMapper personMapper;

    @Override
    public Page<FileInfoListVO> getFileInfoList(Integer current, Integer size, String orderType, String orderKeyword, String imageTypeText, Long userId) {
        Page<FileInfoListVO> page = new Page<>(current, size);
        List<FileInfoListVO> fileInfoListVOS = fileMapper.getFileInfoList(page, orderType, orderKeyword, imageTypeText, null, null, "all", userId, null, true);
        page.setRecords(fileInfoListVOS);
        return page;
    }

    @Override
    public void recoverPicture(List<String> fileIds, Long userId) {

        userFileMapper.updateUseFile(userId, fileIds, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dropPicture(List<String> fileIds, Long userId) {
        List<UserFileEntity> deletedRelations = userFileMapper.selectList(
                new QueryWrapper<UserFileEntity>()
                        .eq("user_id", userId)
                        .eq("is_deleted", true)
                        .in("file_id", fileIds)
        );
        List<String> ownedFileIds = deletedRelations.stream()
                .map(UserFileEntity::getFileId)
                .distinct()
                .toList();
        if (ownedFileIds.isEmpty()) {
            return;
        }
        long releasedSpace = fileMapper.selectBatchIds(ownedFileIds).stream()
                .mapToLong(FileEntity::getSize)
                .sum();
        //只删除关联表，物理删除，等服务器定时任务执行
        userFileMapper.dropFile(userId, ownedFileIds);
        if (releasedSpace > 0) {
            userStorageMapper.releaseSpace(userId, releasedSpace);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cronDropPicture() {
        {
            List<UserFileEntity> expiredRelations = userFileMapper.selectSoftDeletedFiles(
                    LocalDateTime.now().minusDays(30)
            );
            if (!expiredRelations.isEmpty()) {
                List<String> expiredFileIds = expiredRelations.stream()
                        .map(UserFileEntity::getFileId)
                        .distinct()
                        .toList();
                Map<String, Long> fileSizes = fileMapper.selectBatchIds(expiredFileIds).stream()
                        .collect(Collectors.toMap(FileEntity::getFileId, FileEntity::getSize));
                Map<Long, Long> releasedByUser = new HashMap<>();
                for (UserFileEntity relation : expiredRelations) {
                    releasedByUser.merge(
                            relation.getUserId(),
                            fileSizes.getOrDefault(relation.getFileId(), 0L),
                            Long::sum
                    );
                }
                for (Map.Entry<Long, Long> entry : releasedByUser.entrySet()) {
                    if (entry.getValue() > 0) {
                        userStorageMapper.releaseSpace(entry.getKey(), entry.getValue());
                    }
                }
                userFileMapper.dropAllFile(expiredFileIds);
            }
        }
        List<FileEntity> list = userFileMapper.selectExpiredPicture();
        List<String> fileIds = new ArrayList<>();
        if (list.isEmpty()) {
            return;
        }
        for (FileEntity fileEntity : list) {
            fileIds.add(fileEntity.getFileId());
        }
        // 删除 user_file 关联记录
        userFileMapper.dropAllFile(fileIds);

        QueryWrapper<UserFileEntity> queryWrapper = new QueryWrapper<>();

        // 记录本次被物理删除的文件所涉及的 userId，用于后续精确清理空人物分组
        // 必须在 fileMapper.delete() 之前查询，因为 face 会随 file 一起级联删除
        Set<Long> affectedUserIds = new HashSet<>();

        for (FileEntity fileEntity : list) {
            String fileId = fileEntity.getFileId();
            queryWrapper.clear();
            queryWrapper.eq("file_id", fileId);
            List<UserFileEntity> userFileEntities = userFileMapper.selectList(queryWrapper);

            if (userFileEntities.isEmpty()) {
                fileMapper.updateStatus(fileId, FileStatus.DELETING.name(), "Recycle cleanup started");
                // 在 file 被物理删除（级联删 face）之前，记录该文件关联的人脸所属 userId
                QueryWrapper<Face> faceQuery = new QueryWrapper<>();
                faceQuery.eq("file_id", fileId).select("user_id");
                List<Face> faces = faceMapper.selectList(faceQuery);
                for (Face face : faces) {
                    affectedUserIds.add(face.getUserId());
                }

                // 物理删除 file 记录（外键级联：face → person_face 同步删除）
                deleteObjectIfPresent(fileEntity.getFileObjectName());
                if (fileEntity.getThumbnailObjectName() != null
                        && !fileEntity.getThumbnailObjectName().equals(fileEntity.getFileObjectName())) {
                    deleteObjectIfPresent(fileEntity.getThumbnailObjectName());
                }

                QueryWrapper<FileEntity> delete = new QueryWrapper<>();
                delete.eq("file_id", fileId);
                fileMapper.delete(delete);

                // 删除 MinIO 中的原文件和缩略图
            }
        }

        // 清理本次删除操作波及用户下无任何关联人脸的空人物分组
        // 触发原因：face 已随 file 级联删除，person_face 随之级联删除，但 person 记录不会自动清理
        for (Long userId : affectedUserIds) {
            int deleted = personMapper.deleteEmptyPersonsByUserId(userId);
            if (deleted > 0) {
                log.info("清理空人物分组: userId={}, 删除 {} 条空 person 记录", userId, deleted);
            }
        }
    }

    private void deleteObjectIfPresent(String objectName) {
        if (objectName != null && !objectName.isBlank()) {
            minioOSSUtil.delete(objectName);
        }
    }

}
