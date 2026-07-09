package com.memory.xzp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.AlbumMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.model.dto.album.AlbumBatchAddFilesRequest;
import com.memory.xzp.model.dto.album.AlbumBatchCreateRequest;
import com.memory.xzp.model.dto.album.AlbumBatchUpdateRequest;
import com.memory.xzp.model.dto.album.SaveClassificationRequest;
import com.memory.xzp.model.entity.Album;
import com.memory.xzp.model.vo.album.AlbumBatchAddFilesResultVO;
import com.memory.xzp.model.vo.album.AlbumBatchUpdateResultVO;
import com.memory.xzp.model.vo.album.AlbumVO;
import com.memory.xzp.model.vo.album.SaveClassificationResultVO;
import com.memory.xzp.service.AlbumClassificationService;
import com.memory.xzp.service.AlbumService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AlbumClassificationServiceImpl implements AlbumClassificationService {

    private static final Set<String> AI_TAG_NAMES = Set.of(
            "person", "building", "document", "device", "food",
            "other", "pet", "plant", "travel", "transportation"
    );

    @Resource
    private AlbumMapper albumMapper;

    @Resource
    private AlbumService albumService;

    @Resource
    private FileMapper fileMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> batchCreateAlbums(AlbumBatchCreateRequest request, Long userId) {
        if (request == null || CollectionUtils.isEmpty(request.getAlbums())) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "albums不能为空");
        }
        return request.getAlbums().stream().map(item -> {
            String albumName = safeAlbumName(item.getName());
            Album album = new Album();
            album.setUserId(userId);
            album.setAlbumName(albumName);
            album.setDescription(StringUtils.hasText(item.getDescription()) ? item.getDescription().trim() : "");
            String tagName = normalizeTagName(item.getTagName());
            String type = normalizeType(item.getType(), tagName);
            album.setType(type);
            album.setTagName("tag".equals(type) ? tagName : null);
            albumMapper.insert(album);
            return album.getAlbumId();
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlbumBatchUpdateResultVO batchUpdateAlbums(AlbumBatchUpdateRequest request, Long userId) {
        if (request == null || CollectionUtils.isEmpty(request.getAlbums())) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "albums不能为空");
        }
        int updated = 0;
        for (AlbumBatchUpdateRequest.AlbumUpdateItem item : request.getAlbums()) {
            if (item == null || item.getId() == null) {
                continue;
            }
            String albumName = safeAlbumName(item.getName());
            Album existing = albumMapper.selectOne(new QueryWrapper<Album>()
                    .eq("album_id", item.getId())
                    .eq("user_id", userId)
                    .last("limit 1"));
            if (existing == null) {
                continue;
            }
            if (!Objects.equals(existing.getAlbumName(), albumName)) {
                UpdateWrapper<Album> wrapper = new UpdateWrapper<>();
                wrapper.eq("album_id", item.getId())
                        .eq("user_id", userId)
                        .set("album_name", albumName);
                albumMapper.update(null, wrapper);
                updated++;
            }
        }
        return new AlbumBatchUpdateResultVO(updated);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AlbumBatchAddFilesResultVO batchAddFiles(AlbumBatchAddFilesRequest request, Long userId) {
        if (request == null || CollectionUtils.isEmpty(request.getAlbumFiles())) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "albumFiles不能为空");
        }
        int added = 0;
        for (AlbumBatchAddFilesRequest.AlbumFilesItem item : request.getAlbumFiles()) {
            if (item == null || item.getAlbumId() == null || CollectionUtils.isEmpty(item.getFileIds())) {
                continue;
            }
            for (String fileId : item.getFileIds()) {
                if (!StringUtils.hasText(fileId) || fileMapper.isUserHasFile(fileId, userId) == null) {
                    continue;
                }
                Long existed = albumMapper.selectPictureIsExit(item.getAlbumId(), fileId);
                albumService.addPictureToAlbum(List.of(fileId), item.getAlbumId(), userId);
                if (existed == null) {
                    added++;
                }
            }
        }
        return new AlbumBatchAddFilesResultVO(added);
    }

    @Override
    public AlbumVO getAlbumByTagName(String tagName, Long userId) {
        if (!StringUtils.hasText(tagName)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "tagName不能为空");
        }
        Album album = findTagAlbumByTagName(tagName.trim(), userId);
        return album == null ? null : albumService.selectAlbumById(album.getAlbumId(), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SaveClassificationResultVO saveClassification(SaveClassificationRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求不能为空");
        }

        LinkedHashSet<String> targetFileIdSet = new LinkedHashSet<>();
        if (StringUtils.hasText(request.getFileId())) {
            targetFileIdSet.add(request.getFileId().trim());
        }
        if (!CollectionUtils.isEmpty(request.getFileIds())) {
            request.getFileIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(targetFileIdSet::add);
        }
        if (targetFileIdSet.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "fileId/fileIds不能为空");
        }

        List<String> targetFileIds = targetFileIdSet.stream().toList();
        for (String fileId : targetFileIds) {
            if (fileMapper.isUserHasFile(fileId, userId) == null) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "文件不存在或无权限");
            }
        }

        SaveClassificationResultVO result = new SaveClassificationResultVO();
        result.getStatistics().setTotalFiles(targetFileIds.size());
        List<SaveClassificationRequest.ClassificationGroup> groups = request.getGroups();
        if (CollectionUtils.isEmpty(groups)) {
            result.getStatistics().setClassifiedFiles(0);
            result.getStatistics().setUnclassifiedFiles(targetFileIds.size());
            return result;
        }

        Set<Long> updatedAlbumIds = new HashSet<>();
        Set<String> classifiedFileIds = new HashSet<>();
        for (SaveClassificationRequest.ClassificationGroup group : groups) {
            if (group == null || !StringUtils.hasText(group.getAlbumName())) {
                continue;
            }
            Album album = resolveAlbum(group, userId, result, updatedAlbumIds);
            if (album == null || album.getAlbumId() == null) {
                continue;
            }

            Set<String> desiredFileIds = resolveDesiredFileIds(group, targetFileIds, request);
            classifiedFileIds.addAll(desiredFileIds);
            for (String fileId : targetFileIds) {
                Long existed = albumMapper.selectPictureIsExit(album.getAlbumId(), fileId);
                if (desiredFileIds.contains(fileId)) {
                    albumService.addPictureToAlbum(List.of(fileId), album.getAlbumId(), userId);
                    if (existed == null) {
                        result.getAddedFiles().add(buildRelation(album.getAlbumId(), fileId));
                    }
                } else if (existed != null) {
                    albumService.removePictureFromAlbum(List.of(fileId), album.getAlbumId(), userId);
                    result.getRemovedFiles().add(buildRelation(album.getAlbumId(), fileId));
                }
            }
        }

        result.getStatistics().setClassifiedFiles(classifiedFileIds.size());
        result.getStatistics().setUnclassifiedFiles(targetFileIds.size() - classifiedFileIds.size());
        return result;
    }

    private Set<String> resolveDesiredFileIds(SaveClassificationRequest.ClassificationGroup group,
                                              List<String> targetFileIds,
                                              SaveClassificationRequest request) {
        if (!CollectionUtils.isEmpty(group.getFileIds())) {
            Set<String> allowedFileIds = new HashSet<>(targetFileIds);
            return group.getFileIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .filter(allowedFileIds::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        boolean checked = group.getChecked() == null || group.getChecked();
        if (!checked) {
            return new LinkedHashSet<>();
        }

        if (!CollectionUtils.isEmpty(request.getUnclassifiedFileIds())) {
            Set<String> unclassifiedSet = request.getUnclassifiedFileIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .collect(Collectors.toSet());
            return targetFileIds.stream()
                    .filter(fileId -> !unclassifiedSet.contains(fileId))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        if (Boolean.TRUE.equals(request.getUnclassified())) {
            return new LinkedHashSet<>();
        }

        return new LinkedHashSet<>(targetFileIds);
    }

    private Album resolveAlbum(SaveClassificationRequest.ClassificationGroup group,
                               Long userId,
                               SaveClassificationResultVO result,
                               Set<Long> updatedAlbumIds) {
        String albumName = safeAlbumName(group.getAlbumName());
        String tagName = normalizeTagName(group.getTagName());
        boolean isNew = Boolean.TRUE.equals(group.getIsNew()) || group.getAlbumId() == null;

        Album album;
        if (!isNew && group.getAlbumId() != null) {
            album = albumMapper.selectOne(new QueryWrapper<Album>()
                    .eq("album_id", group.getAlbumId())
                    .eq("user_id", userId)
                    .last("limit 1"));
            if (album == null) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "相册不存在");
            }
            if (!Objects.equals(album.getAlbumName(), albumName)) {
                UpdateWrapper<Album> wrapper = new UpdateWrapper<>();
                wrapper.eq("album_id", album.getAlbumId())
                        .eq("user_id", userId)
                        .set("album_name", albumName);
                albumMapper.update(null, wrapper);
                album.setAlbumName(albumName);
                if (updatedAlbumIds.add(album.getAlbumId())) {
                    result.getUpdatedAlbums().add(buildAlbumSummary(album));
                }
            }
            return album;
        }

        album = StringUtils.hasText(tagName) ? findTagAlbumByTagName(tagName, userId) : findAlbumByName(albumName, userId);
        if (album != null) {
            return album;
        }

        Album newAlbum = new Album();
        newAlbum.setUserId(userId);
        newAlbum.setAlbumName(albumName);
        boolean tagAlbum = StringUtils.hasText(tagName) && AI_TAG_NAMES.contains(tagName);
        newAlbum.setType(tagAlbum ? "tag" : "normal");
        newAlbum.setTagName(tagAlbum ? tagName : null);
        newAlbum.setDescription(tagAlbum ? "自动创建的标签相册" : "");
        albumMapper.insert(newAlbum);
        result.getCreatedAlbums().add(buildAlbumSummary(newAlbum));
        return newAlbum;
    }

    private Album findTagAlbumByTagName(String tagName, Long userId) {
        return albumMapper.selectOne(new QueryWrapper<Album>()
                .eq("user_id", userId)
                .eq("type", "tag")
                .eq("tag_name", tagName)
                .last("limit 1"));
    }

    private Album findAlbumByName(String albumName, Long userId) {
        return albumMapper.selectOne(new QueryWrapper<Album>()
                .eq("user_id", userId)
                .eq("album_name", albumName)
                .last("limit 1"));
    }

    private String safeAlbumName(String albumName) {
        if (!StringUtils.hasText(albumName)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "相册名称不能为空");
        }
        String normalized = albumName.trim();
        if (normalized.length() > 100) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "相册名称过长");
        }
        return normalized;
    }

    private String normalizeTagName(String tagName) {
        return StringUtils.hasText(tagName) ? tagName.trim().toLowerCase() : null;
    }

    private String normalizeType(String type, String tagName) {
        if ("person".equalsIgnoreCase(type)) {
            return "person";
        }
        if ("tag".equalsIgnoreCase(type) && StringUtils.hasText(tagName)) {
            return "tag";
        }
        return "normal";
    }

    private SaveClassificationResultVO.AlbumSummary buildAlbumSummary(Album album) {
        SaveClassificationResultVO.AlbumSummary summary = new SaveClassificationResultVO.AlbumSummary();
        summary.setId(album.getAlbumId());
        summary.setName(album.getAlbumName());
        return summary;
    }

    private SaveClassificationResultVO.AlbumFileRelation buildRelation(Long albumId, String fileId) {
        SaveClassificationResultVO.AlbumFileRelation relation = new SaveClassificationResultVO.AlbumFileRelation();
        relation.setAlbumId(albumId);
        relation.setFileId(fileId);
        return relation;
    }
}
