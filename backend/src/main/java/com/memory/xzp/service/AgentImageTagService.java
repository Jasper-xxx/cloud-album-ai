package com.memory.xzp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.AgentImageTagMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.model.dto.agent.AgentAdvancedSearchRequest;
import com.memory.xzp.model.dto.agent.AgentImageTagTaskPreviewRequest;
import com.memory.xzp.model.dto.agent.AgentPendingActionPayload;
import com.memory.xzp.model.dto.agent.AgentSuggestedTagPayload;
import com.memory.xzp.model.dto.agent.AgentSuggestedTagSelection;
import com.memory.xzp.model.dto.agent.AgentSuggestedTagsPreviewRequest;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.vo.agent.AgentActionPreviewVO;
import com.memory.xzp.model.vo.agent.AgentActionResultVO;
import com.memory.xzp.model.vo.agent.AgentAdvancedSearchResultVO;
import com.memory.xzp.model.vo.agent.AgentFileReferenceVO;
import com.memory.xzp.model.vo.agent.AgentFileSearchItemVO;
import com.memory.xzp.model.vo.agent.AgentImageTagBatchRawVO;
import com.memory.xzp.model.vo.agent.AgentImageTagTaskItemRawVO;
import com.memory.xzp.model.vo.agent.AgentImageTagTaskStatusVO;
import com.memory.xzp.model.vo.agent.AgentTagSuggestionVO;
import com.memory.xzp.model.vo.picture.TagResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * P2 AI 标签建议任务、聚合状态与候选写入预览。
 */
@Service
public class AgentImageTagService {

    private static final int MAX_BATCH_FILES = 50;
    private static final int MAX_APPLY_SUGGESTIONS = 100;
    private static final int MAX_SUGGESTIONS_PER_FILE = 5;
    private static final double DEFAULT_MIN_CONFIDENCE = 0.5D;
    private static final String ASYNC_TASK_SUCCESS = "SUCCESS";
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of(ASYNC_TASK_SUCCESS, "DEAD");

    private final FileMapper fileMapper;
    private final AgentLibraryService agentLibraryService;
    private final AsyncTaskService asyncTaskService;
    private final AgentImageTagMapper agentImageTagMapper;
    private final AgentPendingActionService pendingActionService;
    private final ObjectMapper objectMapper;

    public AgentImageTagService(
            FileMapper fileMapper,
            AgentLibraryService agentLibraryService,
            AsyncTaskService asyncTaskService,
            AgentImageTagMapper agentImageTagMapper,
            AgentPendingActionService pendingActionService,
            ObjectMapper objectMapper
    ) {
        this.fileMapper = fileMapper;
        this.agentLibraryService = agentLibraryService;
        this.asyncTaskService = asyncTaskService;
        this.agentImageTagMapper = agentImageTagMapper;
        this.pendingActionService = pendingActionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentActionPreviewVO previewImageTagTask(
            AgentImageTagTaskPreviewRequest request,
            Long userId
    ) {
        requireUser(userId);
        if (request == null || !request.getUnexpectedFields().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "AI标签任务预览参数无效");
        }
        double minConfidence = normalizeConfidence(request.getMinConfidence());
        List<String> fileIds = resolveImageFileIds(request, userId);

        AgentActionPreviewVO preview = new AgentActionPreviewVO();
        preview.setAction("submit_image_tag_task");
        preview.setTitle("生成 AI 标签建议");
        preview.setAffectedFileCount(fileIds.size());
        preview.setSummary("将为 " + fileIds.size() + " 张图片生成 AI 标签建议，不会自动写入标签");
        preview.setWarnings(List.of(
                "AI 结果仅作为建议，可能不准确。",
                "只有置信度不低于 " + formatConfidence(minConfidence) + " 的建议会进入候选列表。",
                "任务提交后可查询进度；候选标签写入仍需再次预览和确认。"
        ));
        preview.setConfirmationPrompt(
                "将提交 " + fileIds.size() + " 个 AI 标签识别任务，不会自动修改照片。确认提交吗？"
        );
        preview.setFileIds(fileIds);
        preview.setAffectedFiles(toFileReferences(fileIds, userId));

        AgentPendingActionPayload payload = new AgentPendingActionPayload();
        payload.setFamily("ai_task");
        payload.setAction("submit_image_tag_task");
        payload.setFileIds(new ArrayList<>(fileIds));
        payload.setMinConfidence(minConfidence);
        pendingActionService.register(preview, payload, userId);
        return preview;
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentActionResultVO submitImageTagTask(
            AgentPendingActionPayload payload,
            Long userId
    ) {
        requirePayload(payload, userId, "ai_task", "submit_image_tag_task");
        List<String> fileIds = normalizeFileIds(payload.getFileIds(), MAX_BATCH_FILES);
        requireOwnedImages(fileIds, userId);

        String batchId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        double minConfidence = normalizeConfidence(payload.getMinConfidence());
        if (agentImageTagMapper.insertBatch(
                batchId,
                userId,
                payload.getPendingActionId(),
                minConfidence,
                fileIds.size(),
                now
        ) != 1) {
            throw new BusinessException(StatusCode.OPERATION_ERROR, "创建AI标签任务批次失败");
        }

        for (String fileId : fileIds) {
            Long taskId = asyncTaskService.enqueueImageTag(fileId, userId, false, batchId);
            if (taskId == null || agentImageTagMapper.insertItem(
                    batchId,
                    fileId,
                    taskId,
                    now
            ) != 1) {
                throw new BusinessException(StatusCode.OPERATION_ERROR, "创建AI标签子任务失败");
            }
        }

        AgentActionResultVO result = new AgentActionResultVO();
        result.setPendingActionId(payload.getPendingActionId());
        result.setAction(payload.getAction());
        result.setAgentTaskId(batchId);
        result.setSuccess(true);
        result.setAffectedFileCount(fileIds.size());
        result.setAffectedFiles(toFileReferences(fileIds, userId));
        result.setMessage("AI标签建议任务已提交；当前只分析照片并生成候选标签，不会修改照片");
        pendingActionService.complete(payload.getPendingActionId(), userId, result);
        return result;
    }

    @Transactional(readOnly = true)
    public AgentImageTagTaskStatusVO getAgentTaskStatus(String agentTaskId, Long userId) {
        requireUser(userId);
        String batchId = normalizeBatchId(agentTaskId);
        AgentImageTagBatchRawVO batch = agentImageTagMapper.selectOwnedBatch(batchId, userId);
        if (batch == null) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "AI标签任务不存在");
        }
        List<AgentImageTagTaskItemRawVO> items =
                agentImageTagMapper.selectOwnedItems(batchId, userId);

        AgentImageTagTaskStatusVO status = new AgentImageTagTaskStatusVO();
        status.setAgentTaskId(batchId);
        status.setTotalCount(batch.getTotalCount());
        status.setMinConfidence(batch.getMinConfidence());
        status.setCreatedAt(batch.getCreateTime());
        status.setPendingCount(count(items, "PENDING"));
        status.setRunningCount(count(items, "RUNNING"));
        status.setSucceededCount(count(items, ASYNC_TASK_SUCCESS));
        status.setFailedCount(count(items, "FAILED"));
        status.setDeadCount(count(items, "DEAD"));

        int terminal = status.getSucceededCount() + status.getDeadCount();
        status.setProgressPercent(status.getTotalCount() == null || status.getTotalCount() == 0
                ? 100
                : Math.min(100, (int) Math.round(terminal * 100.0 / status.getTotalCount())));
        status.setStatus(resolveBatchStatus(status));
        status.setFiles(toFileReferences(
                items.stream().map(AgentImageTagTaskItemRawVO::getFileId).toList(),
                userId
        ));
        status.setCompletedAt(items.stream()
                .filter(item -> TERMINAL_TASK_STATUSES.contains(item.getStatus()))
                .map(AgentImageTagTaskItemRawVO::getCompletedAt)
                .filter(value -> value != null)
                .max(LocalDateTime::compareTo)
                .orElse(null));

        List<AgentTagSuggestionVO> suggestions =
                collectSuggestions(items, batch.getMinConfidence());
        status.setSuggestions(suggestions);
        status.setSuggestionCount(suggestions.size());
        status.setFailureSummaries(items.stream()
                .filter(item -> "DEAD".equals(item.getStatus()))
                .map(item -> safeFailure(item.getFileId(), item.getLastError()))
                .limit(10)
                .toList());
        return status;
    }

    @Transactional
    public AgentActionPreviewVO previewApplySuggestedTags(
            AgentSuggestedTagsPreviewRequest request,
            Long userId
    ) {
        requireUser(userId);
        if (request == null || !request.getUnexpectedFields().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "候选标签预览参数无效");
        }
        if (request.getSelections() != null && request.getSelections().stream()
                .anyMatch(item -> item == null || !item.getUnexpectedFields().isEmpty())) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "候选标签选择包含未声明字段");
        }

        AgentImageTagTaskStatusVO taskStatus =
                getAgentTaskStatus(request.getAgentTaskId(), userId);
        if (!Set.of("SUCCEEDED", "PARTIAL_SUCCEEDED", "FAILED").contains(taskStatus.getStatus())) {
            throw new BusinessException(StatusCode.CONFLICT_ERROR, "AI标签任务尚未完成");
        }
        if (taskStatus.getSuggestions().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "当前任务没有可应用的标签建议");
        }

        boolean applyAll = Boolean.TRUE.equals(request.getApplyAll());
        List<AgentSuggestedTagSelection> requestedSelections =
                request.getSelections() == null ? List.of() : request.getSelections();
        List<String> selectedTagNames = normalizeTagNames(request.getSelectedTagNames());
        if (requestedSelections.size() > MAX_APPLY_SUGGESTIONS) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "单次最多选择100条标签建议");
        }
        if (applyAll && (!requestedSelections.isEmpty() || !selectedTagNames.isEmpty())) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "applyAll不能与selections或selectedTagNames同时使用"
            );
        }
        if (!requestedSelections.isEmpty() && !selectedTagNames.isEmpty()) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "selections与selectedTagNames只能使用一种"
            );
        }
        if (!applyAll && requestedSelections.isEmpty() && selectedTagNames.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请选择要应用的标签建议");
        }

        Map<String, AgentTagSuggestionVO> available = new HashMap<>();
        for (AgentTagSuggestionVO suggestion : taskStatus.getSuggestions()) {
            available.put(suggestionKey(suggestion.getFileId(), suggestion.getTagName()), suggestion);
        }

        LinkedHashMap<String, AgentSuggestedTagPayload> selected = new LinkedHashMap<>();
        if (applyAll) {
            for (AgentTagSuggestionVO suggestion : taskStatus.getSuggestions()) {
                selected.put(
                        suggestionKey(suggestion.getFileId(), suggestion.getTagName()),
                        toPayload(suggestion)
                );
            }
        } else if (!requestedSelections.isEmpty()) {
            for (AgentSuggestedTagSelection selection : requestedSelections) {
                String fileId = normalizeText(selection.getFileId(), 36, "fileId");
                String tagName = normalizeText(selection.getTagName(), 100, "tagName");
                AgentTagSuggestionVO suggestion =
                        available.get(suggestionKey(fileId, tagName));
                if (suggestion == null) {
                    throw new BusinessException(
                            StatusCode.PARAMS_ERROR,
                            "选择中包含不属于该任务或低于阈值的标签建议"
                    );
                }
                selected.put(suggestionKey(fileId, tagName), toPayload(suggestion));
            }
        } else {
            Set<String> names = selectedTagNames.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            for (AgentTagSuggestionVO suggestion : taskStatus.getSuggestions()) {
                if (names.contains(suggestion.getTagName().toLowerCase(Locale.ROOT))) {
                    selected.put(
                            suggestionKey(suggestion.getFileId(), suggestion.getTagName()),
                            toPayload(suggestion)
                    );
                }
            }
            if (selected.isEmpty()) {
                throw new BusinessException(
                        StatusCode.PARAMS_ERROR,
                        "所选标签名称不属于该任务的可用建议"
                );
            }
        }
        if (selected.size() > MAX_APPLY_SUGGESTIONS) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "单次最多应用100条标签建议，请缩小选择范围"
            );
        }

        List<AgentSuggestedTagPayload> suggestions = new ArrayList<>(selected.values());
        int fileCount = (int) suggestions.stream()
                .map(AgentSuggestedTagPayload::getFileId)
                .distinct()
                .count();
        AgentActionPreviewVO preview = new AgentActionPreviewVO();
        preview.setAction("apply_suggested_tags");
        preview.setTitle("应用 AI 标签建议");
        preview.setAgentTaskId(taskStatus.getAgentTaskId());
        preview.setAffectedFileCount(fileCount);
        preview.setSuggestedTagCount(suggestions.size());
        preview.setSummary(
                "将把 " + suggestions.size() + " 条已选择的 AI 标签建议应用到 "
                        + fileCount + " 张照片"
        );
        preview.setWarnings(List.of(
                "AI 建议可能不准确，请确认标签与照片范围。",
                "共享给其他活跃用户的物理文件会被安全跳过。"
        ));
        preview.setConfirmationPrompt("确认将这些 AI 标签建议写入照片吗？");
        preview.setFileIds(suggestions.stream()
                .map(AgentSuggestedTagPayload::getFileId)
                .distinct()
                .toList());
        preview.setAffectedFiles(toFileReferences(preview.getFileIds(), userId));

        AgentPendingActionPayload payload = new AgentPendingActionPayload();
        payload.setFamily("suggested_tag");
        payload.setAction("apply_suggested_tags");
        payload.setAgentTaskId(taskStatus.getAgentTaskId());
        payload.setFileIds(new ArrayList<>(preview.getFileIds()));
        payload.setSuggestedTags(suggestions);
        pendingActionService.register(preview, payload, userId);
        return preview;
    }

    private List<String> resolveImageFileIds(
            AgentImageTagTaskPreviewRequest request,
            Long userId
    ) {
        List<String> explicit = request.getFileIds() == null
                ? new ArrayList<>()
                : normalizeFileIds(request.getFileIds(), MAX_BATCH_FILES);
        AgentAdvancedSearchRequest simpleFilters = simpleFilters(request);
        if (request.getFilters() != null && simpleFilters != null) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "filters不能与简化筛选字段同时使用"
            );
        }
        AgentAdvancedSearchRequest filters = request.getFilters() == null
                ? simpleFilters
                : request.getFilters();
        String simpleSearchType = request.getSearchType() == null
                ? ""
                : request.getSearchType().trim();
        int selectionLimit = normalizeSelectionLimit(request.getSelectionLimit());
        if (!explicit.isEmpty() && filters != null) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "fileIds与筛选条件只能使用一种"
            );
        }
        if (!explicit.isEmpty()) {
            requireOwnedImages(explicit, userId);
            return explicit;
        }
        if (filters == null) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "请提供fileIds、组合检索filters或简化筛选条件"
            );
        }

        List<String> mediaTypes = filters.getMediaTypes();
        if (mediaTypes != null && mediaTypes.contains("video")) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "AI标签建议任务只支持图片和GIF");
        }
        filters.setCurrent(1);
        filters.setSize("latest".equals(simpleSearchType)
                ? selectionLimit
                : MAX_BATCH_FILES);
        if (mediaTypes == null || mediaTypes.isEmpty()) {
            filters.setMediaTypes(List.of("picture", "gif"));
        }
        AgentAdvancedSearchResultVO search =
                agentLibraryService.advancedSearch(filters, userId);
        if (!"latest".equals(simpleSearchType)
                && search.getTotal() != null
                && search.getTotal() > MAX_BATCH_FILES) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "匹配图片超过50张，请增加筛选条件或分批提交"
            );
        }
        List<String> fileIds = search.getRecords().stream()
                .filter(item -> "image".equals(item.getCategory()))
                .map(AgentFileSearchItemVO::getFileId)
                .toList();
        if (fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "没有匹配到可分析的图片");
        }
        return fileIds;
    }

    private AgentAdvancedSearchRequest simpleFilters(
            AgentImageTagTaskPreviewRequest request
    ) {
        boolean hasSimple = (request.getAlbumId() != null && request.getAlbumId() != -1L)
                || (request.getTagName() != null && !request.getTagName().isBlank())
                || (request.getLocationValue() != null && !request.getLocationValue().isBlank())
                || (request.getModel() != null && !request.getModel().isBlank())
                || (request.getKeyword() != null && !request.getKeyword().isBlank())
                || (request.getSearchType() != null && !request.getSearchType().isBlank());
        if (!hasSimple) {
            return null;
        }
        AgentAdvancedSearchRequest filters = new AgentAdvancedSearchRequest();
        filters.setAlbumId(request.getAlbumId());
        if (request.getTagName() != null && !request.getTagName().isBlank()) {
            filters.setTags(List.of(request.getTagName().trim()));
        }
        String level = request.getLocationLevel();
        String locationValue = request.getLocationValue();
        if (locationValue != null && !locationValue.isBlank()) {
            String normalizedLevel = level == null || level.isBlank()
                    ? "city"
                    : level.trim();
            switch (normalizedLevel) {
                case "country" -> filters.setCountry(locationValue.trim());
                case "province" -> filters.setProvince(locationValue.trim());
                case "city" -> filters.setCity(locationValue.trim());
                case "district" -> filters.setDistrict(locationValue.trim());
                default -> throw new BusinessException(
                        StatusCode.PARAMS_ERROR,
                        "locationLevel取值无效"
                );
            }
        }
        filters.setModel(request.getModel());
        filters.setKeyword(request.getKeyword());
        String searchType = request.getSearchType();
        String searchKeyword = request.getSearchKeyword();
        if (searchType != null && !searchType.isBlank()) {
            String type = searchType.trim();
            String filterValue = searchKeyword == null ? "" : searchKeyword.trim();
            switch (type) {
                case "tag" -> {
                    if (filterValue.isEmpty()) {
                        throw new BusinessException(StatusCode.PARAMS_ERROR, "标签筛选值不能为空");
                    }
                    filters.setTags(List.of(filterValue));
                }
                case "location" -> {
                    if (filterValue.isEmpty()) {
                        throw new BusinessException(StatusCode.PARAMS_ERROR, "地点筛选值不能为空");
                    }
                    String normalizedLevel = request.getLocationLevel() == null
                            || request.getLocationLevel().isBlank()
                            ? "city"
                            : request.getLocationLevel().trim();
                    switch (normalizedLevel) {
                        case "country" -> filters.setCountry(filterValue);
                        case "province" -> filters.setProvince(filterValue);
                        case "city" -> filters.setCity(filterValue);
                        case "district" -> filters.setDistrict(filterValue);
                        default -> throw new BusinessException(
                                StatusCode.PARAMS_ERROR,
                                "locationLevel取值无效"
                        );
                    }
                }
                case "model" -> {
                    if (filterValue.isEmpty()) {
                        throw new BusinessException(StatusCode.PARAMS_ERROR, "设备筛选值不能为空");
                    }
                    filters.setModel(filterValue);
                }
                case "all" -> {
                    // 媒体类型会在后续统一限制为图片和 GIF。
                }
                case "latest" -> {
                    filters.setOrderBy("uploadedAt");
                    filters.setOrderType("desc");
                }
                default -> throw new BusinessException(
                        StatusCode.PARAMS_ERROR,
                        "searchType取值无效"
                );
            }
        }
        return filters;
    }

    private List<String> normalizeTagNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        if (values.size() > 20) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "selectedTagNames最多20个");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(normalizeText(value, 100, "selectedTagNames"));
        }
        return new ArrayList<>(normalized);
    }

    private void requireOwnedImages(List<String> fileIds, Long userId) {
        if (fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "图片范围不能为空");
        }
        List<FileEntity> files = fileMapper.selectFileByIds(fileIds, userId);
        Map<String, FileEntity> byId = new HashMap<>();
        for (FileEntity file : files) {
            byId.put(file.getFileId(), file);
        }
        for (String fileId : fileIds) {
            FileEntity file = byId.get(fileId);
            if (file == null || !"image".equals(file.getCategory())) {
                throw new BusinessException(StatusCode.NO_AUTH_ERROR, "图片不存在或无权访问");
            }
        }
    }

    private List<AgentTagSuggestionVO> collectSuggestions(
            List<AgentImageTagTaskItemRawVO> items,
            Double minConfidence
    ) {
        double threshold = minConfidence == null ? DEFAULT_MIN_CONFIDENCE : minConfidence;
        List<AgentTagSuggestionVO> suggestions = new ArrayList<>();
        for (AgentImageTagTaskItemRawVO item : items) {
            if (!ASYNC_TASK_SUCCESS.equals(item.getStatus())
                    || item.getResultJson() == null
                    || item.getResultJson().isBlank()) {
                continue;
            }
            List<TagResult> results;
            try {
                results = objectMapper.readValue(
                        item.getResultJson(),
                        new TypeReference<List<TagResult>>() {
                        }
                );
            } catch (JsonProcessingException exception) {
                continue;
            }
            results.stream()
                    .filter(value -> value != null
                            && value.getTagName() != null
                            && !value.getTagName().isBlank()
                            && value.getImageType() != null
                            && !value.getImageType().isBlank()
                            && value.getConfidence() != null
                            && Double.isFinite(value.getConfidence())
                            && value.getConfidence() <= 1
                            && value.getConfidence() >= threshold)
                    .sorted(Comparator.comparing(
                            TagResult::getConfidence,
                            Comparator.reverseOrder()
                    ))
                    .limit(MAX_SUGGESTIONS_PER_FILE)
                    .forEach(value -> suggestions.add(new AgentTagSuggestionVO(
                            item.getFileId(),
                            value.getImageType().trim(),
                            value.getTagName().trim(),
                            value.getConfidence()
                    )));
        }
        LinkedHashMap<String, AgentTagSuggestionVO> unique = new LinkedHashMap<>();
        for (AgentTagSuggestionVO suggestion : suggestions) {
            unique.putIfAbsent(
                    suggestionKey(suggestion.getFileId(), suggestion.getTagName()),
                    suggestion
            );
        }
        return new ArrayList<>(unique.values());
    }

    private String resolveBatchStatus(AgentImageTagTaskStatusVO status) {
        int total = status.getTotalCount() == null ? 0 : status.getTotalCount();
        int terminal = status.getSucceededCount() + status.getDeadCount();
        if (terminal >= total) {
            if (status.getSucceededCount() == total) {
                return "SUCCEEDED";
            }
            if (status.getSucceededCount() == 0) {
                return "FAILED";
            }
            return "PARTIAL_SUCCEEDED";
        }
        if (status.getRunningCount() > 0
                || status.getFailedCount() > 0
                || status.getSucceededCount() > 0) {
            return "RUNNING";
        }
        return "SUBMITTED";
    }

    private int count(List<AgentImageTagTaskItemRawVO> items, String status) {
        return (int) items.stream().filter(item -> status.equals(item.getStatus())).count();
    }

    private List<AgentFileReferenceVO> toFileReferences(List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> namesById = new HashMap<>();
        for (FileEntity file : fileMapper.selectFileByIds(fileIds, userId)) {
            namesById.put(file.getFileId(), file.getOriginFileName());
        }
        return fileIds.stream()
                .distinct()
                .map(fileId -> new AgentFileReferenceVO(fileId, namesById.get(fileId)))
                .toList();
    }

    private List<String> normalizeFileIds(List<String> fileIds, int max) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (fileIds != null && fileIds.size() > max) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "单批次最多处理" + max + "张图片"
            );
        }
        if (fileIds != null) {
            for (String fileId : fileIds) {
                String value = normalizeText(fileId, 36, "fileIds");
                if (value != null) {
                    normalized.add(value);
                }
                if (normalized.size() > max) {
                    throw new BusinessException(
                            StatusCode.PARAMS_ERROR,
                            "单批次最多处理" + max + "张图片"
                    );
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    private double normalizeConfidence(Double value) {
        double normalized = value == null ? DEFAULT_MIN_CONFIDENCE : value;
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 1) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "minConfidence必须在0到1之间");
        }
        return normalized;
    }

    private int normalizeSelectionLimit(Integer value) {
        int normalized = value == null ? 1 : value;
        if (normalized < 1 || normalized > MAX_BATCH_FILES) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "selectionLimit必须在1到50之间"
            );
        }
        return normalized;
    }

    private String normalizeBatchId(String value) {
        String normalized = normalizeText(value, 36, "agentTaskId");
        try {
            return UUID.fromString(normalized).toString();
        } catch (RuntimeException exception) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "agentTaskId格式无效");
        }
    }

    private String normalizeText(String value, int maxLength, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, fieldName + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, fieldName + "长度超出限制");
        }
        return normalized;
    }

    private void requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(StatusCode.NOT_LOGIN_ERROR, "请先登录");
        }
    }

    private void requirePayload(
            AgentPendingActionPayload payload,
            Long userId,
            String family,
            String action
    ) {
        requireUser(userId);
        if (payload == null
                || payload.getPendingActionId() == null
                || !userId.equals(payload.getUserId())
                || !family.equals(payload.getFamily())
                || !action.equals(payload.getAction())) {
            throw new BusinessException(StatusCode.FORBIDDEN_ERROR, "待执行任务与当前请求不匹配");
        }
    }

    private AgentSuggestedTagPayload toPayload(AgentTagSuggestionVO suggestion) {
        return new AgentSuggestedTagPayload(
                suggestion.getFileId(),
                suggestion.getImageType(),
                suggestion.getTagName(),
                suggestion.getConfidence()
        );
    }

    private String suggestionKey(String fileId, String tagName) {
        return fileId + "\u0000" + tagName.toLowerCase(Locale.ROOT);
    }

    private String formatConfidence(double confidence) {
        return String.format(Locale.ROOT, "%.2f", confidence);
    }

    private String safeFailure(String fileId, String error) {
        String message = error == null || error.isBlank() ? "任务失败" : error.trim();
        if (message.length() > 160) {
            message = message.substring(0, 160);
        }
        return "文件 " + fileId + "：" + message;
    }
}
