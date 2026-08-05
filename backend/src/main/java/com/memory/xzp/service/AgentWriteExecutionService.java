package com.memory.xzp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.AlbumMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PictureTagMapper;
import com.memory.xzp.mapper.UserMapper;
import com.memory.xzp.model.dto.agent.AgentPendingActionPayload;
import com.memory.xzp.model.dto.agent.AgentSuggestedTagPayload;
import com.memory.xzp.model.entity.Album;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.UserFileEntity;
import com.memory.xzp.model.vo.agent.AgentActionResultVO;
import com.memory.xzp.model.vo.agent.AgentFileReferenceVO;
import com.memory.xzp.model.vo.album.AlbumVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只使用预览阶段冻结载荷执行智能体写操作。
 */
@Service
public class AgentWriteExecutionService {

    private final AlbumService albumService;
    private final AlbumMapper albumMapper;
    private final FileMapper fileMapper;
    private final PictureTagMapper pictureTagMapper;
    private final UserMapper userMapper;
    private final RecordService recordService;
    private final AgentPendingActionService pendingActionService;

    public AgentWriteExecutionService(
            AlbumService albumService,
            AlbumMapper albumMapper,
            FileMapper fileMapper,
            PictureTagMapper pictureTagMapper,
            UserMapper userMapper,
            RecordService recordService,
            AgentPendingActionService pendingActionService
    ) {
        this.albumService = albumService;
        this.albumMapper = albumMapper;
        this.fileMapper = fileMapper;
        this.pictureTagMapper = pictureTagMapper;
        this.userMapper = userMapper;
        this.recordService = recordService;
        this.pendingActionService = pendingActionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentActionResultVO executeAlbum(
            AgentPendingActionPayload payload,
            Long userId,
            HttpServletRequest servletRequest
    ) {
        requirePayload(payload, userId, "album");
        List<String> frozenFileIds = normalizeFrozenFileIds(payload.getFileIds());
        List<String> ownedFileIds = lockOwnedFileIds(frozenFileIds, userId);

        AgentActionResultVO result = baseResult(payload);
        switch (payload.getAction()) {
            case "create_album" -> executeCreateAlbum(payload, userId, servletRequest, result);
            case "create_album_and_add_files" -> executeCreateAlbumAndAdd(
                    payload, ownedFileIds, userId, servletRequest, result
            );
            case "add_files_to_album" -> executeAddToAlbum(
                    payload, ownedFileIds, userId, servletRequest, result
            );
            case "remove_files_from_album" -> executeRemoveFromAlbum(
                    payload, ownedFileIds, userId, servletRequest, result
            );
            default -> throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的相册操作");
        }

        int affected = nullToZero(result.getAffectedFileCount());
        int unavailable = Math.max(0, frozenFileIds.size() - ownedFileIds.size());
        int unchanged = Math.max(0, ownedFileIds.size() - affected);
        result.setSkippedFileCount(Math.max(0, frozenFileIds.size() - affected));
        addSkippedReason(result, unavailable, "张照片已删除或不再属于当前用户");
        if ("remove_files_from_album".equals(payload.getAction())) {
            addSkippedReason(result, unchanged, "张照片已不在目标相册中");
        } else if (!"create_album".equals(payload.getAction())) {
            addSkippedReason(result, unchanged, "张照片已在目标相册中或并发时已被加入");
        }
        result.setSuccess(true);
        pendingActionService.complete(payload.getPendingActionId(), userId, result);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentActionResultVO executeTag(
            AgentPendingActionPayload payload,
            Long userId,
            HttpServletRequest servletRequest
    ) {
        requirePayload(payload, userId, "tag");
        List<String> frozenFileIds = normalizeFrozenFileIds(payload.getFileIds());
        TagOwnership ownership = lockExclusiveTagOwnership(frozenFileIds, userId);
        List<String> ownedFileIds = ownership.fileIds();

        AgentActionResultVO result = baseResult(payload);
        int affected;
        List<String> changedFileIds = new ArrayList<>();
        if ("add_tags".equals(payload.getAction())) {
            affected = 0;
            String imageType = payload.getImageType() == null || payload.getImageType().isBlank()
                    ? "其他"
                    : payload.getImageType();
            for (String fileId : ownedFileIds) {
                int inserted = pictureTagMapper.insertIfAbsent(fileId, imageType, payload.getTagName());
                affected += inserted;
                if (inserted > 0) {
                    changedFileIds.add(fileId);
                }
            }
            if (affected > 0) {
                recordService.createRecordLog(
                        "智能体:添加照片标签 " + payload.getTagName(),
                        affected,
                        userId,
                        servletRequest
                );
            }
            result.setMessage(affected > 0
                    ? "标签添加成功"
                    : (ownedFileIds.isEmpty()
                            ? "没有可安全修改标签的照片"
                            : "照片已具有该标签，无需重复添加"));
        } else if ("remove_tags".equals(payload.getAction())) {
            Set<String> matchingFileIds = ownedFileIds.isEmpty()
                    ? new LinkedHashSet<>()
                    : pictureTagMapper.selectTagsByFileIds(ownedFileIds, userId)
                            .stream()
                            .filter(mapping -> payload.getTagName().equals(mapping.getTagName()))
                            .map(mapping -> mapping.getFileId())
                            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            affected = ownedFileIds.isEmpty()
                    ? 0
                    : pictureTagMapper.deleteByFileIdsAndTag(
                            ownedFileIds,
                            payload.getTagName(),
                            userId
                    );
            if (affected == matchingFileIds.size()) {
                changedFileIds.addAll(ownedFileIds.stream().filter(matchingFileIds::contains).toList());
            }
            if (affected > 0) {
                recordService.createRecordLog(
                        "智能体:移除照片标签 " + payload.getTagName(),
                        affected,
                        userId,
                        servletRequest
                );
            }
            result.setMessage(affected > 0
                    ? "标签移除成功"
                    : (ownedFileIds.isEmpty()
                            ? "没有可安全修改标签的照片"
                            : "照片已不含该标签，无需重复移除"));
        } else {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的标签操作");
        }

        result.setAffectedFileCount(affected);
        result.setAffectedFiles(toFileReferences(changedFileIds, userId));
        result.setSkippedFileCount(Math.max(0, frozenFileIds.size() - affected));
        addSkippedReason(result, ownership.unavailableCount(), "张照片已删除或不再属于当前用户");
        addSkippedReason(result, ownership.sharedCount(), "张照片由多个用户共享，已跳过标签修改");
        addSkippedReason(
                result,
                Math.max(0, ownedFileIds.size() - affected),
                "张照片的标签状态无需变化"
        );
        result.setSuccess(true);
        pendingActionService.complete(payload.getPendingActionId(), userId, result);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentActionResultVO executeSuggestedTags(
            AgentPendingActionPayload payload,
            Long userId,
            HttpServletRequest servletRequest
    ) {
        requirePayload(payload, userId, "suggested_tag");
        if (!"apply_suggested_tags".equals(payload.getAction())
                || payload.getAgentTaskId() == null
                || payload.getAgentTaskId().isBlank()) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "冻结的AI标签建议无效，请重新预览");
        }

        LinkedHashMap<String, AgentSuggestedTagPayload> normalized = new LinkedHashMap<>();
        if (payload.getSuggestedTags() != null) {
            for (AgentSuggestedTagPayload suggestion : payload.getSuggestedTags()) {
                if (suggestion == null
                        || suggestion.getFileId() == null
                        || suggestion.getFileId().isBlank()
                        || suggestion.getFileId().length() > 36
                        || suggestion.getTagName() == null
                        || suggestion.getTagName().isBlank()
                        || suggestion.getTagName().length() > 100
                        || suggestion.getImageType() == null
                        || suggestion.getImageType().isBlank()
                        || suggestion.getImageType().length() > 100) {
                    throw new BusinessException(
                            StatusCode.CONFLICT_ERROR,
                            "冻结的AI标签建议无效，请重新预览"
                    );
                }
                String key = suggestion.getFileId().trim()
                        + "\u0000"
                        + suggestion.getTagName().trim();
                normalized.putIfAbsent(key, suggestion);
                if (normalized.size() > 100) {
                    throw new BusinessException(
                            StatusCode.CONFLICT_ERROR,
                            "冻结的AI标签建议过多，请重新预览"
                    );
                }
            }
        }
        if (normalized.isEmpty()) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "冻结的AI标签建议为空");
        }

        List<String> fileIds = normalized.values().stream()
                .map(AgentSuggestedTagPayload::getFileId)
                .map(String::trim)
                .distinct()
                .toList();
        TagOwnership ownership = lockExclusiveTagOwnership(fileIds, userId);
        Set<String> writable = new HashSet<>(ownership.fileIds());
        Set<String> changedFiles = new HashSet<>();
        int affectedTags = 0;
        for (AgentSuggestedTagPayload suggestion : normalized.values()) {
            String fileId = suggestion.getFileId().trim();
            if (!writable.contains(fileId)) {
                continue;
            }
            int inserted = pictureTagMapper.insertIfAbsent(
                    fileId,
                    suggestion.getImageType().trim(),
                    suggestion.getTagName().trim()
            );
            affectedTags += inserted;
            if (inserted > 0) {
                changedFiles.add(fileId);
            }
        }

        AgentActionResultVO result = baseResult(payload);
        result.setAgentTaskId(payload.getAgentTaskId());
        result.setAffectedFileCount(changedFiles.size());
        result.setAffectedFiles(toFileReferences(
                fileIds.stream().filter(changedFiles::contains).toList(),
                userId
        ));
        result.setAffectedTagCount(affectedTags);
        result.setSkippedTagCount(Math.max(0, normalized.size() - affectedTags));
        result.setSkippedFileCount(Math.max(0, fileIds.size() - changedFiles.size()));
        addSkippedReason(result, ownership.unavailableCount(), "张照片已删除或不再属于当前用户");
        addSkippedReason(result, ownership.sharedCount(), "张照片由多个用户共享，已跳过标签修改");
        addSkippedReason(
                result,
                Math.max(0, normalized.size() - affectedTags),
                "条标签建议已存在或对应照片不可安全修改"
        );
        result.setSuccess(true);
        result.setMessage(affectedTags > 0
                ? "AI标签建议已应用"
                : "没有需要写入的AI标签建议");
        if (affectedTags > 0) {
            recordService.createRecordLog(
                    "智能体:应用AI标签建议",
                    affectedTags,
                    userId,
                    servletRequest
            );
        }
        pendingActionService.complete(payload.getPendingActionId(), userId, result);
        return result;
    }

    private void executeCreateAlbum(
            AgentPendingActionPayload payload,
            Long userId,
            HttpServletRequest request,
            AgentActionResultVO result
    ) {
        AlbumResolution resolution = payload.getAlbumId() == null
                ? findOrCreateAlbumAfterUserLock(payload.getAlbumName(), userId)
                : new AlbumResolution(requireFrozenAlbum(payload, userId), false);
        Album album = resolution.album();
        result.setAlbumId(album.getAlbumId());
        result.setAlbumName(album.getAlbumName());
        result.setCreatedAlbumCount(resolution.created() ? 1 : 0);
        result.setAffectedFileCount(0);
        result.setMessage(resolution.created() ? "相册创建成功" : "相册已存在，无需重复创建");
        if (resolution.created()) {
            recordService.createRecordLog(
                    "智能体:创建相册 " + album.getAlbumName(),
                    1,
                    userId,
                    request
            );
        }
    }

    private void executeCreateAlbumAndAdd(
            AgentPendingActionPayload payload,
            List<String> ownedFileIds,
            Long userId,
            HttpServletRequest request,
            AgentActionResultVO result
    ) {
        boolean expectedExisting = payload.getAlbumId() != null;
        AlbumResolution resolution = expectedExisting
                ? new AlbumResolution(requireFrozenAlbum(payload, userId), false)
                : findOrCreateAlbumAfterUserLock(payload.getAlbumName(), userId);
        Album album = resolution.album();
        boolean created = resolution.created();
        List<String> insertFileIds = filterAlbumMembership(
                ownedFileIds,
                album.getAlbumId(),
                userId,
                false
        );
        int affected = insertFileIds.isEmpty()
                ? 0
                : albumMapper.addPicturesToAlbum(album.getAlbumId(), userId, insertFileIds);
        result.setAlbumId(album.getAlbumId());
        result.setAlbumName(album.getAlbumName());
        result.setCreatedAlbumCount(created ? 1 : 0);
        result.setAffectedFileCount(affected);
        if (affected == insertFileIds.size()) {
            result.setAffectedFiles(toFileReferences(insertFileIds, userId));
        }
        result.setMessage(created
                ? (affected > 0
                        ? "相册创建并添加照片成功"
                        : "相册已创建，但预览中的照片当前均未加入")
                : (affected > 0 ? "照片已添加到已有相册" : "照片已在目标相册中"));
        if (created || affected > 0) {
            recordService.createRecordLog(
                    "智能体:" + (created ? "创建相册并加入照片 " : "添加照片到相册 ")
                            + album.getAlbumName(),
                    Math.max(affected, created ? 1 : 0),
                    userId,
                    request
            );
        }
    }

    private void executeAddToAlbum(
            AgentPendingActionPayload payload,
            List<String> ownedFileIds,
            Long userId,
            HttpServletRequest request,
            AgentActionResultVO result
    ) {
        AlbumVO album = requireFrozenAlbumVO(payload, userId);
        List<String> insertFileIds = filterAlbumMembership(
                ownedFileIds,
                album.getAlbumId(),
                userId,
                false
        );
        int affected = insertFileIds.isEmpty()
                ? 0
                : albumMapper.addPicturesToAlbum(album.getAlbumId(), userId, insertFileIds);
        result.setAlbumId(album.getAlbumId());
        result.setAlbumName(album.getAlbumName());
        result.setAffectedFileCount(affected);
        if (affected == insertFileIds.size()) {
            result.setAffectedFiles(toFileReferences(insertFileIds, userId));
        }
        result.setMessage(affected > 0 ? "照片已添加到相册" : "照片已在目标相册中");
        if (affected > 0) {
            recordService.createRecordLog(
                    "智能体:添加照片到相册 " + album.getAlbumName(),
                    affected,
                    userId,
                    request
            );
        }
    }

    private void executeRemoveFromAlbum(
            AgentPendingActionPayload payload,
            List<String> ownedFileIds,
            Long userId,
            HttpServletRequest request,
            AgentActionResultVO result
    ) {
        AlbumVO album = requireFrozenAlbumVO(payload, userId);
        List<String> removeFileIds = filterAlbumMembership(
                ownedFileIds,
                album.getAlbumId(),
                userId,
                true
        );
        int affected = removeFileIds.isEmpty()
                ? 0
                : albumMapper.removePictureFromAlbum(
                        List.of(album.getAlbumId()),
                        removeFileIds,
                        userId
                );
        result.setAlbumId(album.getAlbumId());
        result.setAlbumName(album.getAlbumName());
        result.setAffectedFileCount(affected);
        if (affected == removeFileIds.size()) {
            result.setAffectedFiles(toFileReferences(removeFileIds, userId));
        }
        result.setMessage(affected > 0 ? "照片已从相册移出" : "目标照片已不在该相册中");
        if (affected > 0) {
            recordService.createRecordLog(
                    "智能体:从相册移出照片 " + album.getAlbumName(),
                    affected,
                    userId,
                    request
            );
        }
    }

    private AgentActionResultVO baseResult(AgentPendingActionPayload payload) {
        AgentActionResultVO result = new AgentActionResultVO();
        result.setPendingActionId(payload.getPendingActionId());
        result.setAction(payload.getAction());
        result.setAlbumId(payload.getAlbumId());
        result.setAlbumName(payload.getAlbumName());
        result.setTagName(payload.getTagName());
        result.setAgentTaskId(payload.getAgentTaskId());
        return result;
    }

    private void requirePayload(AgentPendingActionPayload payload, Long userId, String family) {
        if (payload == null
                || payload.getPendingActionId() == null
                || !userId.equals(payload.getUserId())
                || !family.equals(payload.getFamily())) {
            throw new BusinessException(StatusCode.FORBIDDEN_ERROR, "待执行操作与当前请求不匹配");
        }
    }

    private List<String> normalizeFrozenFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String fileId : fileIds) {
            if (fileId == null || fileId.isBlank() || fileId.length() > 36) {
                throw new BusinessException(StatusCode.CONFLICT_ERROR, "冻结的照片范围无效，请重新预览");
            }
            unique.add(fileId);
            if (unique.size() > 100) {
                throw new BusinessException(StatusCode.CONFLICT_ERROR, "冻结的照片范围过大，请重新预览");
            }
        }
        return new ArrayList<>(unique);
    }

    private List<String> lockOwnedFileIds(List<String> fileIds, Long userId) {
        if (fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<UserFileEntity> rows = fileMapper.selectActiveOwnershipRowsForUpdate(fileIds);
        Set<String> ownedIds = new HashSet<>();
        for (UserFileEntity row : rows) {
            if (userId.equals(row.getUserId())) {
                ownedIds.add(row.getFileId());
            }
        }
        List<String> result = new ArrayList<>();
        for (String fileId : fileIds) {
            if (ownedIds.contains(fileId)) {
                result.add(fileId);
            }
        }
        return result;
    }

    private List<String> filterAlbumMembership(
            List<String> fileIds,
            Long albumId,
            Long userId,
            boolean keepExisting
    ) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> existing = new HashSet<>(
                albumMapper.selectExistingPictureFileIds(albumId, userId, fileIds)
        );
        List<String> result = new ArrayList<>();
        for (String fileId : fileIds) {
            if (existing.contains(fileId) == keepExisting) {
                result.add(fileId);
            }
        }
        return result;
    }

    private AlbumVO requireFrozenAlbumVO(AgentPendingActionPayload payload, Long userId) {
        if (payload.getAlbumId() == null || payload.getAlbumId() <= 0) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "预览未冻结有效的目标相册，请重新预览");
        }
        AlbumVO album = albumService.selectAlbumById(payload.getAlbumId(), userId);
        if (album == null || !sameAlbumName(payload.getAlbumName(), album.getAlbumName())) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "预览中的目标相册已变化，请重新预览");
        }
        return album;
    }

    private Album requireFrozenAlbum(AgentPendingActionPayload payload, Long userId) {
        if (payload.getAlbumId() == null || payload.getAlbumId() <= 0) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "预览未冻结有效的目标相册，请重新预览");
        }
        Album album = albumMapper.selectById(payload.getAlbumId());
        if (album == null
                || !userId.equals(album.getUserId())
                || !"normal".equals(album.getType())
                || !sameAlbumName(payload.getAlbumName(), album.getAlbumName())) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "预览中的目标相册已变化，请重新预览");
        }
        return album;
    }

    private AlbumResolution findOrCreateAlbumAfterUserLock(String albumName, Long userId) {
        if (userMapper.lockUserForAlbumCreation(userId) == null) {
            throw new BusinessException(StatusCode.FORBIDDEN_ERROR, "当前用户不存在");
        }
        Album existing = findAlbumByName(albumName, userId);
        if (existing != null) {
            return new AlbumResolution(existing, false);
        }
        return new AlbumResolution(createAlbum(albumName, userId), true);
    }

    private Album findAlbumByName(String albumName, Long userId) {
        if (albumName == null || albumName.isBlank()) {
            return null;
        }
        return albumMapper.selectOne(new QueryWrapper<Album>()
                .eq("user_id", userId)
                .eq("album_name", albumName.trim())
                .eq("type", "normal")
                .orderByAsc("album_id")
                .last("limit 1"));
    }

    private Album createAlbum(String albumName, Long userId) {
        Album album = new Album();
        album.setUserId(userId);
        album.setAlbumName(albumName);
        album.setType("normal");
        if (albumMapper.insert(album) != 1) {
            throw new BusinessException(StatusCode.OPERATION_ERROR, "相册创建失败");
        }
        return album;
    }

    private TagOwnership lockExclusiveTagOwnership(List<String> fileIds, Long userId) {
        if (fileIds.isEmpty()) {
            return new TagOwnership(new ArrayList<>(), 0, 0);
        }
        List<UserFileEntity> rows = fileMapper.selectActiveOwnershipRowsForUpdate(fileIds);
        Map<String, Set<Long>> ownersByFile = new LinkedHashMap<>();
        for (UserFileEntity row : rows) {
            ownersByFile.computeIfAbsent(row.getFileId(), ignored -> new HashSet<>())
                    .add(row.getUserId());
        }
        List<String> exclusive = new ArrayList<>();
        int unavailable = 0;
        int shared = 0;
        for (String fileId : fileIds) {
            Set<Long> owners = ownersByFile.get(fileId);
            if (owners == null || !owners.contains(userId)) {
                unavailable++;
            } else if (owners.size() != 1) {
                shared++;
            } else {
                exclusive.add(fileId);
            }
        }
        return new TagOwnership(exclusive, unavailable, shared);
    }

    private void addSkippedReason(AgentActionResultVO result, int count, String reason) {
        if (count > 0) {
            result.getSkippedReasons().add(count + " " + reason);
        }
    }

    private List<AgentFileReferenceVO> toFileReferences(List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> namesById = new LinkedHashMap<>();
        for (FileEntity file : fileMapper.selectFileByIds(fileIds, userId)) {
            namesById.put(file.getFileId(), file.getOriginFileName());
        }
        return fileIds.stream()
                .distinct()
                .map(fileId -> new AgentFileReferenceVO(fileId, namesById.get(fileId)))
                .toList();
    }

    private boolean sameAlbumName(String expected, String actual) {
        return expected != null && actual != null && expected.trim().equals(actual.trim());
    }

    private record TagOwnership(
            List<String> fileIds,
            int unavailableCount,
            int sharedCount
    ) {
    }

    private record AlbumResolution(Album album, boolean created) {
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
