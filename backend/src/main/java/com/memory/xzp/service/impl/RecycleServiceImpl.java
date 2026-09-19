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
        releaseRelations(deletedRelations, LocalDateTime.now());
    }

    private void releaseRelations(List<UserFileEntity> relations, LocalDateTime cutoff) {
        if (relations.isEmpty()) return;
        Map<String, Long> sizes = fileMapper.selectBatchIds(relations.stream().map(UserFileEntity::getFileId).distinct().toList())
                .stream().collect(Collectors.toMap(FileEntity::getFileId, FileEntity::getSize));
        for (UserFileEntity relation : relations) {
            // Recovery/redeletion changes the predicate; only an actually removed row releases quota.
            if (userFileMapper.deleteDeletedRelation(relation.getId(), relation.getUserId(), relation.getDeletedTime(), cutoff) == 1) {
                userFileMapper.deleteRemovedAlbumMembership(relation.getUserId(), relation.getFileId());
                userFileMapper.deleteRemovedSimilarMembership(relation.getUserId(), relation.getFileId());
                long size = sizes.getOrDefault(relation.getFileId(), 0L);
                if (size > 0) userStorageMapper.releaseSpace(relation.getUserId(), size);
            }
        }
    }

    @Resource
    private com.memory.xzp.mapper.FileObjectGcMapper objectGcMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cronDropPicture() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        releaseRelations(userFileMapper.selectSoftDeletedFiles(cutoff), cutoff);
        // Lock orphan file rows; FK checks prevent a new relation racing this deletion.
        for (FileEntity file : userFileMapper.selectExpiredPicture()) {
            if (userFileMapper.countByFileId(file.getFileId()) != 0) continue;
            if (file.getFileObjectName() != null) objectGcMapper.enqueue(file.getFileObjectName());
            if (file.getThumbnailObjectName() != null && !file.getThumbnailObjectName().equals(file.getFileObjectName()))
                objectGcMapper.enqueue(file.getThumbnailObjectName());
            fileMapper.deleteById(file.getFileId());
        }
    }

}
