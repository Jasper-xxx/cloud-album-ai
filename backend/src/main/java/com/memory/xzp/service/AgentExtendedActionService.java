package com.memory.xzp.service;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.AgentExtendedActionMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PersonMapper;
import com.memory.xzp.model.dto.PersonDTO;
import com.memory.xzp.model.dto.agent.AgentExtendedActionRequest;
import com.memory.xzp.model.dto.agent.AgentPendingActionPayload;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.vo.agent.AgentActionPreviewVO;
import com.memory.xzp.model.vo.agent.AgentActionResultVO;
import com.memory.xzp.model.vo.agent.AgentFileReferenceVO;
import com.memory.xzp.model.vo.album.PersonAlbumVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P3/P4 能力的统一安全封装。
 *
 * <p>预览阶段冻结真实目标；执行阶段只消费冻结载荷，并重新校验当前用户权限。</p>
 */
@Service
public class AgentExtendedActionService {
    @jakarta.annotation.Resource
    private AgentResourceGrantService resourceGrantService;
    @org.springframework.beans.factory.annotation.Value("${agent.public-web-url:http://localhost:8080}")
    private String publicWebUrl;
    @org.springframework.beans.factory.annotation.Value("${agent.public-api-url:http://localhost:8080/devApi}")
    private String publicApiUrl;

    public static final String FAMILY_P3 = "p3_action";
    public static final String FAMILY_P4 = "p4_action";
    private static final long HIGH_RISK_TTL_SECONDS = 90L;
    private static final int MAX_FEATURE_FILES = 50;
    private static final int MAX_NORMAL_FILES = 50;
    private static final int MAX_DELETE_FILES = 20;
    private static final int MAX_PERMANENT_DELETE_FILES = 10;
    private static final int MAX_ALBUMS = 5;
    private static final int MAX_ALBUM_CONTENT_FILES = 100;
    private static final int MAX_PERSONS = 10;

    private static final Set<String> P3_ACTIONS = Set.of(
            "build_image_features",
            "update_location",
            "restore_files",
            "rename_person",
            "hide_people",
            "show_people",
            "move_person_files",
            "merge_people"
    );
    private static final Set<String> P4_ACTIONS = Set.of(
            "create_file_share_link",
            "create_album_share_link",
            "create_file_download_token",
            "create_album_download_token",
            "move_files_to_recycle_bin",
            "delete_albums",
            "permanently_delete_files",
            "empty_recycle_bin"
    );

    private final AgentPendingActionService pendingActionService;
    private final AgentExtendedActionMapper extendedMapper;
    private final FileMapper fileMapper;
    private final FileService fileService;
    private final AsyncTaskService asyncTaskService;
    private final RecycleService recycleService;
    private final PersonService personService;
    private final PersonMapper personMapper;
    private final AlbumService albumService;
    private final RecordService recordService;

    public AgentExtendedActionService(
            AgentPendingActionService pendingActionService,
            AgentExtendedActionMapper extendedMapper,
            FileMapper fileMapper,
            FileService fileService,
            AsyncTaskService asyncTaskService,
            RecycleService recycleService,
            PersonService personService,
            PersonMapper personMapper,
            AlbumService albumService,
            RecordService recordService
    ) {
        this.pendingActionService = pendingActionService;
        this.extendedMapper = extendedMapper;
        this.fileMapper = fileMapper;
        this.fileService = fileService;
        this.asyncTaskService = asyncTaskService;
        this.recycleService = recycleService;
        this.personService = personService;
        this.personMapper = personMapper;
        this.albumService = albumService;
        this.recordService = recordService;
    }

    public AgentActionPreviewVO previewP3(AgentExtendedActionRequest request, Long userId) {
        AgentActionPreviewVO preview = buildPreview(request, userId, FAMILY_P3);
        pendingActionService.register(preview, toPayload(preview, request, FAMILY_P3), userId);
        return preview;
    }

    public AgentActionPreviewVO previewP4(AgentExtendedActionRequest request, Long userId) {
        AgentActionPreviewVO preview = buildPreview(request, userId, FAMILY_P4);
        pendingActionService.register(
                preview,
                toPayload(preview, request, FAMILY_P4),
                userId,
                HIGH_RISK_TTL_SECONDS
        );
        return preview;
    }

    private AgentActionPreviewVO buildPreview(
            AgentExtendedActionRequest request,
            Long userId,
            String family
    ) {
        requireRequest(request);
        String action = text(request.getAction());
        if (Boolean.TRUE.equals(request.getAllRecycleImages()) && !"restore_files".equals(action)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "回收站图片范围仅支持恢复操作");
        }
        if (text(request.getAlbumName()) != null && !"move_files_to_recycle_bin".equals(action)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "相册名称范围仅支持图片移入回收站");
        }
        Set<String> allowed = FAMILY_P3.equals(family) ? P3_ACTIONS : P4_ACTIONS;
        if (!allowed.contains(action)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的扩展操作");
        }

        AgentActionPreviewVO preview = new AgentActionPreviewVO();
        preview.setAction(action);
        preview.setRiskLevel(FAMILY_P4.equals(family) ? "high" : "normal");
        preview.setIrreversible(false);

        switch (action) {
            case "build_image_features" -> previewFeatureTask(preview, request, userId);
            case "update_location" -> previewLocation(preview, request, userId);
            case "restore_files" -> previewRestore(preview, request, userId);
            case "rename_person" -> previewRenamePerson(preview, request, userId);
            case "hide_people", "show_people" -> previewPeopleVisibility(preview, request, userId);
            case "move_person_files" -> previewMovePersonFiles(preview, request, userId);
            case "merge_people" -> previewMergePeople(preview, request, userId);
            case "create_file_share_link" -> previewFileToken(preview, request, userId, true);
            case "create_album_share_link" -> previewAlbumToken(preview, request, userId, true);
            case "create_file_download_token" -> previewFileToken(preview, request, userId, false);
            case "create_album_download_token" -> previewAlbumToken(preview, request, userId, false);
            case "move_files_to_recycle_bin" -> previewRecycle(preview, request, userId);
            case "delete_albums" -> previewDeleteAlbums(preview, request, userId);
            case "permanently_delete_files" -> previewPermanentDelete(preview, request, userId);
            case "empty_recycle_bin" -> previewEmptyRecycle(preview, userId);
            default -> throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的扩展操作");
        }

        preview.setAffectedFiles(toFileReferences(preview.getFileIds(), userId));
        preview.setConfirmationPrompt(confirmationPrompt(preview));
        return preview;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentActionResultVO execute(
            AgentPendingActionPayload payload,
            Long userId,
            String expectedFamily,
            HttpServletRequest servletRequest
    ) {
        requirePayload(payload, userId, expectedFamily);
        List<AgentFileReferenceVO> frozenFileReferences = toFileReferences(payload.getFileIds(), userId);
        AgentActionResultVO result = baseResult(payload);
        switch (payload.getAction()) {
            case "build_image_features" -> executeFeatureTasks(payload, userId, result);
            case "update_location" -> executeLocation(payload, userId, result);
            case "restore_files" -> executeRestore(payload, userId, result);
            case "rename_person" -> executeRenamePerson(payload, userId, result);
            case "hide_people" -> executePeopleVisibility(payload, userId, result, false);
            case "show_people" -> executePeopleVisibility(payload, userId, result, true);
            case "move_person_files" -> executeMovePersonFiles(payload, userId, result);
            case "merge_people" -> executeMergePeople(payload, userId, result);
            case "create_file_share_link" -> executeFileShare(payload, userId, result);
            case "create_album_share_link" -> executeAlbumShare(payload, userId, result);
            case "create_file_download_token" -> executeFileDownload(payload, userId, result);
            case "create_album_download_token" -> executeAlbumDownload(payload, userId, result);
            case "move_files_to_recycle_bin" -> executeMoveToRecycle(payload, userId, result);
            case "delete_albums" -> executeDeleteAlbums(payload, userId, result);
            case "permanently_delete_files", "empty_recycle_bin" ->
                    executePermanentDelete(payload, userId, result);
            default -> throw new BusinessException(StatusCode.CONFLICT_ERROR, "冻结操作类型无效，请重新预览");
        }
        int affectedFileCount = result.getAffectedFileCount() == null ? 0 : result.getAffectedFileCount();
        if (affectedFileCount > 0 && affectedFileCount == frozenFileReferences.size()) {
            result.setAffectedFiles(frozenFileReferences);
        }
        result.setSuccess(true);
        if (mutationCount(result) > 0) {
            recordService.createRecordLog(
                    "智能体:" + payload.getAction(),
                    mutationCount(result),
                    userId,
                    servletRequest
            );
        }
        pendingActionService.complete(payload.getPendingActionId(), userId, result);
        return result;
    }

    private void previewFeatureTask(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        List<String> fileIds = requireActiveImages(request.getFileIds(), userId, MAX_FEATURE_FILES);
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());
        preview.setTitle("提交图片特征提取任务");
        preview.setSummary("将为 " + fileIds.size() + " 张图片提交特征提取任务。");
    }

    private void previewLocation(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        List<String> fileIds = requireExclusiveActiveFiles(request.getFileIds(), userId, 1);
        if (fileIds.size() != 1) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "地点修正每次只能处理一张照片");
        }
        String location = text(request.getLocationValue());
        if (location == null || location.length() > 255) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "locationValue长度必须为1到255");
        }
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(1);
        preview.setTitle("修正照片地点");
        preview.setSummary("将照片地点修正为「" + location + "」。");
    }

    private void previewRestore(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        List<String> requested = request.getFileIds();
        if (Boolean.TRUE.equals(request.getAllRecycleImages())) {
            if ((requested != null && !requested.isEmpty())
                    || (request.getAlbumIds() != null && !request.getAlbumIds().isEmpty())) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "恢复全部回收站图片不能混用其他照片范围");
            }
            requested = extendedMapper.selectOwnedRecycleImageIds(userId, MAX_NORMAL_FILES + 1);
            if (requested.size() > MAX_NORMAL_FILES) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "回收站图片超过50张，请分批选择要恢复的照片");
            }
            if (requested.isEmpty()) {
                preview.setRequiresConfirmation(false);
                preview.setTitle("恢复回收站照片");
                preview.setSummary("回收站中没有可恢复的图片，本次无需执行恢复。");
                return;
            }
        }
        List<String> fileIds = requireDeletedFiles(requested, userId, MAX_NORMAL_FILES);
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());
        preview.setTitle("恢复回收站照片");
        preview.setSummary("将从回收站恢复 " + fileIds.size() + " 个文件。");
    }

    private void previewRenamePerson(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        List<Long> people = requirePeople(request.getPersonIds(), userId, 1, 1);
        String name = text(request.getPersonName());
        if (name == null || name.length() > 50) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "人物名称长度必须为1到50");
        }
        PersonAlbumVO person = personService.selectPersonById(userId, people.get(0));
        preview.setAffectedPersonCount(1);
        preview.setAffectedFileCount(safeTotal(person));
        preview.setTitle("重命名人物");
        preview.setSummary("将人物「" + safePersonName(person) + "」重命名为「" + name + "」，影响 "
                + safeTotal(person) + " 张照片。");
    }

    private void previewPeopleVisibility(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        List<Long> people = requirePeople(request.getPersonIds(), userId, 1, MAX_PERSONS);
        int photos = people.stream()
                .map(id -> personService.selectPersonById(userId, id))
                .mapToInt(this::safeTotal)
                .sum();
        preview.setAffectedPersonCount(people.size());
        preview.setAffectedFileCount(photos);
        boolean show = "show_people".equals(preview.getAction());
        preview.setTitle(show ? "恢复显示人物" : "隐藏人物");
        preview.setSummary((show ? "将恢复显示 " : "将隐藏 ") + people.size()
                + " 个人物分组，涉及 " + photos + " 张照片。");
    }

    private void previewMovePersonFiles(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        Long source = requirePerson(request.getSourcePersonId(), userId);
        Long target = requirePerson(request.getTargetPersonId(), userId);
        if (source.equals(target)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "来源人物和目标人物不能相同");
        }
        List<String> requested = normalizeFileIds(request.getFileIds(), MAX_NORMAL_FILES);
        List<String> fileIds = extendedMapper.selectPersonFileIds(userId, source, requested);
        if (fileIds.isEmpty() || fileIds.size() != requested.size()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "部分照片不属于来源人物");
        }
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());
        preview.setAffectedPersonCount(2);
        preview.setTitle("移动人物误归类照片");
        preview.setSummary("将 " + fileIds.size() + " 张照片从人物 " + source + " 移动到人物 " + target + "。");
    }

    private void previewMergePeople(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        List<Long> people = requirePeople(request.getPersonIds(), userId, 2, MAX_PERSONS);
        int photos = people.stream()
                .map(id -> personService.selectPersonById(userId, id))
                .mapToInt(this::safeTotal)
                .sum();
        preview.setAffectedPersonCount(people.size());
        preview.setAffectedFileCount(photos);
        preview.setTitle("合并人物");
        preview.setSummary("将 " + people.size() + " 个人物分组合并到人物 " + people.get(0)
                + "，涉及约 " + photos + " 张照片。");
    }

    private void previewFileToken(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId,
            boolean share
    ) {
        List<String> fileIds = requireActiveFiles(request.getFileIds(), userId, MAX_DELETE_FILES);
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());
        if (share) {
            int days = normalizeShareDays(request.getShareDays());
            preview.setShareDays(days);
            preview.setPublicScope("持有链接者可访问");
            preview.setTitle("创建照片分享链接");
            preview.setSummary("将创建包含 " + fileIds.size() + " 个文件、有效期 " + days + " 天的分享链接。");
        } else {
            preview.setPublicScope("仅用于一次下载");
            preview.setTitle("创建照片下载凭证");
            preview.setSummary("将为 " + fileIds.size() + " 个文件创建短期下载凭证。");
        }
    }

    private void previewAlbumToken(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId,
            boolean share
    ) {
        List<Long> albums = requireAlbums(request.getAlbumIds(), userId, share ? MAX_ALBUMS : 1);
        List<String> fileIds = albumFileIds(albums, userId);
        if (fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "目标相册中没有可处理的文件");
        }
        if (fileIds.size() > MAX_ALBUM_CONTENT_FILES) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "相册文件超过100个，请缩小范围");
        }
        int photos = fileIds.size();
        preview.setFileIds(fileIds);
        preview.setAffectedAlbumCount(albums.size());
        preview.setAffectedFileCount(photos);
        if (share) {
            int days = normalizeShareDays(request.getShareDays());
            preview.setShareDays(days);
            preview.setPublicScope("持有链接者可访问");
            preview.setTitle("创建相册分享链接");
            preview.setSummary("将分享 " + albums.size() + " 个相册（约 " + photos
                    + " 个文件），有效期 " + days + " 天。");
        } else {
            preview.setPublicScope("仅用于一次下载");
            preview.setTitle("创建相册下载凭证");
            preview.setSummary("将为相册 " + albums.get(0) + " 创建短期下载凭证。");
        }
    }

    private void previewRecycle(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        String albumName = text(request.getAlbumName());
        List<String> requested = request.getFileIds();
        if (albumName != null) {
            if (albumName.length() > 100 || (requested != null && !requested.isEmpty())
                    || (request.getAlbumIds() != null && !request.getAlbumIds().isEmpty())) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "请只指定一个相册名称，不要混用文件或相册 ID 范围");
            }
            List<Long> albums = extendedMapper.selectOwnedNormalAlbumsByName(userId, albumName);
            if (albums.size() != 1) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, albums.isEmpty()
                        ? "未找到该名称的普通相册，请核对相册名称"
                        : "存在同名相册，请先查询并明确要处理的照片范围");
            }
            requested = extendedMapper.selectOwnedAlbumImageIds(userId, albums.get(0), MAX_DELETE_FILES + 1);
            if (requested.isEmpty() || requested.size() > MAX_DELETE_FILES) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, requested.isEmpty()
                        ? "该相册没有可移入回收站的图片，未创建待确认操作"
                        : "该相册图片超过20张，请明确分批照片范围；未创建待确认操作");
            }
        }
        List<String> fileIds = requireActiveFiles(requested, userId, MAX_DELETE_FILES);
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());
        preview.setTitle("移入回收站");
        preview.setSummary((albumName == null ? "将 " : "将相册「" + albumName + "」中的 ")
                + fileIds.size() + " 个" + (albumName == null ? "文件" : "图片")
                + "移入回收站，可在自动清理前恢复。" + (albumName == null ? "" : "相册保留。"));
        preview.getWarnings().add("这是删除操作，但仍可从回收站恢复。");
    }

    private void previewDeleteAlbums(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        List<Long> albums = requireAlbums(request.getAlbumIds(), userId, MAX_ALBUMS);
        boolean includePictures = Boolean.TRUE.equals(request.getIncludePictures());
        List<String> fileIds = includePictures ? albumFileIds(albums, userId) : new ArrayList<>();
        if (fileIds.size() > MAX_ALBUM_CONTENT_FILES) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "相册内文件超过100个；请先仅删除相册，或分批把照片移入回收站"
            );
        }
        int photos = fileIds.size();
        preview.setFileIds(fileIds);
        preview.setAffectedAlbumCount(albums.size());
        preview.setAffectedFileCount(includePictures ? photos : 0);
        preview.setTitle("删除相册");
        preview.setSummary("将删除 " + albums.size() + " 个相册"
                + (includePictures ? "，并把其中约 " + photos + " 个文件移入回收站。" : "，保留其中照片。"));
        preview.getWarnings().add(includePictures
                ? "相册本身会删除，照片将进入回收站。"
                : "只删除相册组织结构，不删除照片。");
    }

    private void previewPermanentDelete(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            Long userId
    ) {
        List<String> files = requireDeletedFiles(request.getFileIds(), userId, MAX_PERMANENT_DELETE_FILES);
        preview.setFileIds(files);
        preview.setAffectedFileCount(files.size());
        preview.setIrreversible(true);
        preview.setRiskLevel("critical");
        preview.setTitle("永久删除回收站文件");
        preview.setSummary("将永久删除 " + files.size() + " 个回收站文件，无法恢复。");
        preview.getWarnings().add("该操作不可逆。");
    }

    private void previewEmptyRecycle(AgentActionPreviewVO preview, Long userId) {
        List<String> files = extendedMapper.selectRecycleFileIds(userId, MAX_PERMANENT_DELETE_FILES + 1);
        if (files.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "回收站为空");
        }
        if (files.size() > MAX_PERMANENT_DELETE_FILES) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "回收站文件超过10个，请分批永久删除");
        }
        preview.setFileIds(files);
        preview.setAffectedFileCount(files.size());
        preview.setIrreversible(true);
        preview.setRiskLevel("critical");
        preview.setTitle("清空回收站");
        preview.setSummary("将永久删除回收站中的全部 " + files.size() + " 个文件，无法恢复。");
        preview.getWarnings().add("该操作不可逆，确认凭证仅短期有效。");
    }

    private AgentPendingActionPayload toPayload(
            AgentActionPreviewVO preview,
            AgentExtendedActionRequest request,
            String family
    ) {
        AgentPendingActionPayload payload = new AgentPendingActionPayload();
        payload.setFamily(family);
        payload.setAction(preview.getAction());
        payload.setFileIds(new ArrayList<>(preview.getFileIds()));
        payload.setAlbumIds(normalizeLongIds(request.getAlbumIds(), 20));
        payload.setPersonIds(normalizeLongIds(request.getPersonIds(), 20));
        payload.setSourcePersonId(request.getSourcePersonId());
        payload.setTargetPersonId(request.getTargetPersonId());
        payload.setPersonName(text(request.getPersonName()));
        payload.setPersonRelation(text(request.getPersonRelation()));
        payload.setLocationValue(text(request.getLocationValue()));
        payload.setShareDays(preview.getShareDays());
        payload.setIncludePictures(Boolean.TRUE.equals(request.getIncludePictures()));
        payload.setRiskLevel(preview.getRiskLevel());
        return payload;
    }

    private void executeFeatureTasks(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<String> files = activeFiles(payload.getFileIds(), userId);
        for (FileEntity file : fileMapper.selectFileByIds(files, userId)) {
            if ("image".equals(file.getCategory())) {
                result.getTaskIds().add(asyncTaskService.enqueueImageFeature(
                        file.getFileId(), userId, file.getFileObjectName()
                ));
            }
        }
        result.setAffectedFileCount(result.getTaskIds().size());
        result.setSkippedFileCount(Math.max(0, payload.getFileIds().size() - result.getTaskIds().size()));
        result.setMessage("特征提取任务已提交");
    }

    private void executeLocation(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<String> files = exclusiveActiveFiles(payload.getFileIds(), userId);
        if (files.size() != 1 || text(payload.getLocationValue()) == null) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "照片状态已变化，请重新预览");
        }
        fileService.updateLocation(userId, files.get(0), payload.getLocationValue());
        result.setAffectedFileCount(1);
        result.setMessage("照片地点已修正");
    }

    private void executeRestore(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<String> files = deletedFiles(payload.getFileIds(), userId);
        recycleService.recoverPicture(files, userId);
        result.setAffectedFileCount(files.size());
        result.setSkippedFileCount(payload.getFileIds().size() - files.size());
        result.setMessage(files.isEmpty() ? "没有需要恢复的文件" : "回收站文件已恢复");
    }

    private void executeRenamePerson(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        Long personId = requireFrozenPerson(payload.getPersonIds(), userId);
        PersonAlbumVO current = personService.selectPersonById(userId, personId);
        PersonDTO update = new PersonDTO();
        update.setPersonId(personId);
        update.setPersonName(payload.getPersonName());
        update.setPersonRelation(payload.getPersonRelation() == null
                ? current.getPersonRelation()
                : payload.getPersonRelation());
        personService.updatePerson(update, userId);
        result.setAffectedPersonCount(1);
        result.setAffectedFileCount(safeTotal(current));
        result.setMessage("人物名称已更新");
    }

    private void executePeopleVisibility(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result,
            boolean show
    ) {
        List<Long> people = requireFrozenPeople(payload.getPersonIds(), userId);
        if (show) {
            personMapper.restorePerson(userId, people);
        } else {
            personMapper.hiddenPerson(userId, people);
        }
        result.setAffectedPersonCount(people.size());
        result.setAffectedFileCount(people.stream()
                .map(id -> personService.selectPersonById(userId, id))
                .mapToInt(this::safeTotal)
                .sum());
        result.setMessage(show ? "人物已恢复显示" : "人物已隐藏");
    }

    private void executeMovePersonFiles(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        Long source = requirePerson(payload.getSourcePersonId(), userId);
        Long target = requirePerson(payload.getTargetPersonId(), userId);
        List<String> files = extendedMapper.selectPersonFileIds(userId, source, payload.getFileIds());
        if (files.size() != payload.getFileIds().size()) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "人物照片范围已变化，请重新预览");
        }
        personService.movePersonPicture(userId, source, target, files);
        result.setAffectedPersonCount(2);
        result.setAffectedFileCount(files.size());
        result.setMessage("人物照片已移动");
    }

    private void executeMergePeople(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<Long> people = requireFrozenPeople(payload.getPersonIds(), userId);
        if (people.size() < 2) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "人物范围已变化，请重新预览");
        }
        int affectedFiles = people.stream()
                .map(id -> personService.selectPersonById(userId, id))
                .mapToInt(this::safeTotal)
                .sum();
        personService.mergePersons(userId, people.get(0), people.subList(1, people.size()));
        result.setAffectedPersonCount(people.size());
        result.setAffectedFileCount(affectedFiles);
        result.setMessage("人物分组已合并");
    }

    private void executeFileShare(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<String> files = requireUnchangedActiveFiles(payload.getFileIds(), userId);
        setResource(result, resourceGrantService.issue(userId, files, "share", payload.getShareDays() * 1440), true);
        result.setTokenType("file_share");
        result.setExpiresInDays(payload.getShareDays());
        result.setAffectedFileCount(files.size());
        result.setMessage("照片分享链接已创建");
    }

    private void executeAlbumShare(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<Long> albums = requireUnchangedAlbums(payload.getAlbumIds(), userId);
        List<String> files = requireUnchangedAlbumSnapshot(payload, albums, userId);
        setResource(result, resourceGrantService.issue(userId, files, "share", payload.getShareDays() * 1440), true);
        result.setTokenType("album_share");
        result.setExpiresInDays(payload.getShareDays());
        result.setAffectedAlbumCount(albums.size());
        result.setAffectedFileCount(files.size());
        result.setMessage("相册分享链接已创建");
    }

    private void executeFileDownload(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<String> files = requireUnchangedActiveFiles(payload.getFileIds(), userId);
        setResource(result, resourceGrantService.issue(userId, files, "download", 60), false);
        result.setTokenType("file_download");
        result.setAffectedFileCount(files.size());
        result.setMessage("照片下载凭证已创建");
    }

    private void executeAlbumDownload(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<Long> albums = requireUnchangedAlbums(payload.getAlbumIds(), userId);
        if (albums.size() != 1) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "相册范围无效，请重新预览");
        }
        List<String> files = requireUnchangedAlbumSnapshot(payload, albums, userId);
        setResource(result, resourceGrantService.issue(userId, files, "download", 60), false);
        result.setTokenType("album_snapshot_download");
        result.setAffectedAlbumCount(1);
        result.setAffectedFileCount(files.size());
        result.setMessage("相册下载凭证已创建");
    }

    private void setResource(AgentActionResultVO result, String token, boolean share) {
        String base = share ? publicWebUrl : publicApiUrl;
        java.net.URI uri = java.net.URI.create(base);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("Configure an absolute agent public URL");
        }
        result.setResourceToken(token);
        result.setResourceUrl(base.replaceAll("/+$", "") + (share ? "/share/" : "/file/downloadFileByToken?downloadToken=") + token);
    }

    private void executeMoveToRecycle(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<String> files = activeFiles(payload.getFileIds(), userId);
        boolean changed = !files.isEmpty() && Boolean.TRUE.equals(fileService.setIsDeleted(files, true, userId));
        result.setAffectedFileCount(changed ? files.size() : 0);
        result.setSkippedFileCount(payload.getFileIds().size() - result.getAffectedFileCount());
        result.setMessage(changed ? "文件已移入回收站" : "没有文件被移入回收站");
    }

    private void executeDeleteAlbums(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<Long> albums = requireUnchangedAlbums(payload.getAlbumIds(), userId);
        int affectedFiles = 0;
        if (Boolean.TRUE.equals(payload.getIncludePictures())) {
            Set<String> currentAlbumFiles = new LinkedHashSet<>(albumFileIds(albums, userId));
            List<String> frozenStillInAlbums = payload.getFileIds().stream()
                    .filter(currentAlbumFiles::contains)
                    .toList();
            List<String> activeFiles = activeFiles(frozenStillInAlbums, userId);
            if (!activeFiles.isEmpty()) {
                boolean moved = Boolean.TRUE.equals(fileService.setIsDeleted(activeFiles, true, userId));
                affectedFiles = moved ? activeFiles.size() : 0;
            }
        }
        boolean deleted = Boolean.TRUE.equals(albumService.deleteAlbum(albums, false, userId));
        result.setAffectedAlbumCount(deleted ? albums.size() : 0);
        result.setAffectedFileCount(deleted ? affectedFiles : 0);
        result.setMessage(deleted ? "相册已删除" : "没有相册被删除");
    }

    private void executePermanentDelete(
            AgentPendingActionPayload payload,
            Long userId,
            AgentActionResultVO result
    ) {
        List<String> files = deletedFiles(payload.getFileIds(), userId);
        recycleService.dropPicture(files, userId);
        result.setAffectedFileCount(files.size());
        result.setSkippedFileCount(payload.getFileIds().size() - files.size());
        result.setMessage(files.isEmpty() ? "没有文件被永久删除" : "回收站文件已永久删除");
    }

    private void requireRequest(AgentExtendedActionRequest request) {
        if (request == null || !request.getUnexpectedFields().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求包含不支持的参数");
        }
    }

    private void requirePayload(AgentPendingActionPayload payload, Long userId, String family) {
        if (payload == null
                || payload.getPendingActionId() == null
                || !userId.equals(payload.getUserId())
                || !family.equals(payload.getFamily())) {
            throw new BusinessException(StatusCode.FORBIDDEN_ERROR, "待执行操作与当前请求不匹配");
        }
        Set<String> actions = FAMILY_P3.equals(family) ? P3_ACTIONS : P4_ACTIONS;
        if (!actions.contains(payload.getAction())) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "冻结操作类型无效，请重新预览");
        }
    }

    private List<String> requireActiveFiles(List<String> requested, Long userId, int max) {
        List<String> normalized = normalizeFileIds(requested, max);
        List<String> owned = activeFiles(normalized, userId);
        if (owned.size() != normalized.size()) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "部分文件不存在、已删除或无权访问");
        }
        return owned;
    }

    private List<String> requireActiveImages(List<String> requested, Long userId, int max) {
        List<String> files = requireActiveFiles(requested, userId, max);
        List<FileEntity> entities = fileMapper.selectFileByIds(files, userId);
        long imageCount = entities.stream().filter(file -> "image".equals(file.getCategory())).count();
        if (imageCount != files.size()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "特征提取只支持图片");
        }
        return files;
    }

    private List<String> requireExclusiveActiveFiles(
            List<String> requested,
            Long userId,
            int max
    ) {
        List<String> normalized = normalizeFileIds(requested, max);
        List<String> exclusive = exclusiveActiveFiles(normalized, userId);
        if (exclusive.size() != normalized.size()) {
            throw new BusinessException(
                    StatusCode.CONFLICT_ERROR,
                    "部分文件由多个用户共享，不能安全修改全局元数据"
            );
        }
        return exclusive;
    }

    private List<String> requireDeletedFiles(List<String> requested, Long userId, int max) {
        List<String> normalized = normalizeFileIds(requested, max);
        List<String> deleted = deletedFiles(normalized, userId);
        if (deleted.size() != normalized.size()) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "部分文件不在当前用户回收站");
        }
        return deleted;
    }

    private List<String> activeFiles(List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> owned = new LinkedHashSet<>(fileMapper.selectOwnedActiveFileIds(fileIds, userId));
        return fileIds.stream().filter(owned::contains).toList();
    }

    private List<String> exclusiveActiveFiles(List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> owned = new LinkedHashSet<>(
                fileMapper.selectExclusivelyOwnedActiveFileIds(fileIds, userId)
        );
        return fileIds.stream().filter(owned::contains).toList();
    }

    private List<String> deletedFiles(List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> owned = new LinkedHashSet<>(extendedMapper.selectOwnedDeletedFileIds(userId, fileIds));
        return fileIds.stream().filter(owned::contains).toList();
    }

    private List<String> requireUnchangedActiveFiles(List<String> fileIds, Long userId) {
        List<String> current = activeFiles(fileIds, userId);
        if (current.size() != fileIds.size()) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "文件范围已变化，请重新预览");
        }
        return current;
    }

    private List<Long> requireAlbums(List<Long> requested, Long userId, int max) {
        List<Long> normalized = normalizeLongIds(requested, max);
        if (normalized.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "albumIds不能为空");
        }
        List<Long> owned = extendedMapper.selectOwnedAlbumIds(userId, normalized);
        if (owned.size() != normalized.size()) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "部分相册不存在或无权访问");
        }
        return normalized.stream().filter(new LinkedHashSet<>(owned)::contains).toList();
    }

    private List<Long> requireUnchangedAlbums(List<Long> albums, Long userId) {
        List<Long> current = requireAlbums(albums, userId, MAX_ALBUMS);
        if (current.size() != albums.size()) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "相册范围已变化，请重新预览");
        }
        return current;
    }

    private List<String> requireUnchangedAlbumSnapshot(
            AgentPendingActionPayload payload,
            List<Long> albums,
            Long userId
    ) {
        List<String> frozen = payload.getFileIds() == null
                ? new ArrayList<>()
                : new ArrayList<>(payload.getFileIds());
        List<String> current = albumFileIds(albums, userId);
        if (frozen.isEmpty()
                || !new LinkedHashSet<>(frozen).equals(new LinkedHashSet<>(current))) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "相册内容已变化，请重新预览");
        }
        return requireUnchangedActiveFiles(frozen, userId);
    }

    private List<String> albumFileIds(List<Long> albums, Long userId) {
        LinkedHashSet<String> fileIds = new LinkedHashSet<>();
        for (Long albumId : albums) {
            fileIds.addAll(fileMapper.selectFileIdByAlbumId(albumId, userId));
            if (fileIds.size() > MAX_ALBUM_CONTENT_FILES) {
                break;
            }
        }
        return new ArrayList<>(fileIds);
    }

    private List<Long> requirePeople(
            List<Long> requested,
            Long userId,
            int min,
            int max
    ) {
        List<Long> people = normalizeLongIds(requested, max);
        if (people.size() < min) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "人物数量不足");
        }
        for (Long personId : people) {
            requirePerson(personId, userId);
        }
        return people;
    }

    private Long requirePerson(Long personId, Long userId) {
        if (personId == null || personId <= 0
                || personService.selectPersonById(userId, personId) == null) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "人物不存在或无权访问");
        }
        return personId;
    }

    private Long requireFrozenPerson(List<Long> people, Long userId) {
        List<Long> current = requireFrozenPeople(people, userId);
        if (current.size() != 1) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "人物范围无效，请重新预览");
        }
        return current.get(0);
    }

    private List<Long> requireFrozenPeople(List<Long> people, Long userId) {
        if (people == null || people.isEmpty() || people.size() > MAX_PERSONS) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "人物范围无效，请重新预览");
        }
        for (Long personId : people) {
            requirePerson(personId, userId);
        }
        return people;
    }

    private List<String> normalizeFileIds(List<String> values, int max) {
        if (values == null || values.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "fileIds不能为空");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String id = text(value);
            if (id == null || id.length() > 36) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "fileId格式无效");
            }
            unique.add(id);
            if (unique.size() > max) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "单次文件数量超过限制");
            }
        }
        return new ArrayList<>(unique);
    }

    private List<Long> normalizeLongIds(List<Long> values, int max) {
        if (values == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long value : values) {
            if (value == null || value <= 0) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "ID格式无效");
            }
            unique.add(value);
            if (unique.size() > max) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "单次目标数量超过限制");
            }
        }
        return new ArrayList<>(unique);
    }

    private int normalizeShareDays(Integer shareDays) {
        if (shareDays == null || shareDays < 1 || shareDays > 30) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "shareDays必须在1到30之间");
        }
        return shareDays;
    }

    private String confirmationPrompt(AgentActionPreviewVO preview) {
        String prefix = Boolean.TRUE.equals(preview.getIrreversible())
                ? "这是不可逆操作。"
                : "";
        return prefix + "请明确确认是否执行：" + preview.getSummary();
    }

    private AgentActionResultVO baseResult(AgentPendingActionPayload payload) {
        AgentActionResultVO result = new AgentActionResultVO();
        result.setPendingActionId(payload.getPendingActionId());
        result.setAction(payload.getAction());
        return result;
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

    private int mutationCount(AgentActionResultVO result) {
        return Math.max(
                Math.max(result.getAffectedFileCount(), result.getAffectedAlbumCount()),
                Math.max(result.getAffectedPersonCount(), result.getTaskIds().size())
        );
    }

    private int safeTotal(PersonAlbumVO person) {
        return person == null || person.getTotal() == null
                ? 0
                : Math.toIntExact(Math.min(Integer.MAX_VALUE, person.getTotal()));
    }

    private String safePersonName(PersonAlbumVO person) {
        String value = person == null ? null : text(person.getPersonName());
        return value == null ? "未命名人物" : value;
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
