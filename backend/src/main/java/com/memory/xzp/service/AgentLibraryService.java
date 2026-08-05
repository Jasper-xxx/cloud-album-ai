package com.memory.xzp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.AgentLibraryMapper;
import com.memory.xzp.model.dto.agent.AgentAdvancedSearchCriteria;
import com.memory.xzp.model.dto.agent.AgentAdvancedSearchRequest;
import com.memory.xzp.model.dto.agent.AgentLibraryAnalysisRequest;
import com.memory.xzp.model.vo.agent.AgentAdvancedSearchResultVO;
import com.memory.xzp.model.vo.agent.AgentFileSearchItemVO;
import com.memory.xzp.model.vo.agent.AgentLibraryAnalysisVO;
import com.memory.xzp.model.vo.agent.AgentLibraryHealthRawVO;
import com.memory.xzp.model.vo.agent.AgentLibrarySuggestionVO;
import com.memory.xzp.model.vo.agent.AgentSearchRelaxSuggestionVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * P1 只读组合检索与图库健康分析。
 */
@Service
public class AgentLibraryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_TAGS = 20;
    private static final int DEFAULT_STALE_MINUTES = 60;
    private static final int MAX_STALE_MINUTES = 10080;
    private static final Set<String> MEDIA_TYPES = Set.of("picture", "gif", "video");
    private static final Set<String> TAG_STATES = Set.of("all", "tagged", "untagged");
    private static final ObjectMapper LIST_VALUE_MAPPER = new ObjectMapper();

    private final AgentLibraryMapper agentLibraryMapper;

    public AgentLibraryService(AgentLibraryMapper agentLibraryMapper) {
        this.agentLibraryMapper = agentLibraryMapper;
    }

    @Transactional(readOnly = true)
    public AgentAdvancedSearchResultVO advancedSearch(
            AgentAdvancedSearchRequest request,
            Long userId
    ) {
        if (userId == null) {
            throw new BusinessException(StatusCode.NOT_LOGIN_ERROR, "请先登录");
        }
        AgentAdvancedSearchRequest safeRequest =
                request == null ? new AgentAdvancedSearchRequest() : request;
        if (!safeRequest.getUnexpectedFields().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "组合检索请求包含未声明字段");
        }

        long current = normalizePage(safeRequest.getCurrent());
        long size = normalizeSize(safeRequest.getSize());
        AgentAdvancedSearchCriteria criteria = normalizeCriteria(safeRequest);

        Page<AgentFileSearchItemVO> page = new Page<>(current, size);
        Page<AgentFileSearchItemVO> resultPage =
                agentLibraryMapper.selectAdvancedSearch(page, criteria, userId);

        List<AgentFileSearchItemVO> records = resultPage.getRecords() == null
                ? new ArrayList<>()
                : resultPage.getRecords();
        for (AgentFileSearchItemVO item : records) {
            item.setTags(splitTags(item.getTagNamesText()));
        }

        AgentAdvancedSearchResultVO result = new AgentAdvancedSearchResultVO();
        result.setCurrent(resultPage.getCurrent());
        result.setSize(resultPage.getSize());
        result.setTotal(resultPage.getTotal());
        result.setPages(resultPage.getPages());
        result.setHasNext(resultPage.getCurrent() < resultPage.getPages());
        result.setRecords(records);
        result.setConditionSummary(buildConditionSummary(criteria));
        if (resultPage.getTotal() == 0) {
            result.setRelaxSuggestions(buildRelaxSuggestions(criteria));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public AgentLibraryAnalysisVO analyzeLibrary(
            AgentLibraryAnalysisRequest request,
            Long userId
    ) {
        if (userId == null) {
            throw new BusinessException(StatusCode.NOT_LOGIN_ERROR, "请先登录");
        }
        AgentLibraryAnalysisRequest safeRequest =
                request == null ? new AgentLibraryAnalysisRequest() : request;
        if (!safeRequest.getUnexpectedFields().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "图库分析请求包含未声明字段");
        }
        int staleMinutes = safeRequest.getStaleMinutes() == null
                ? DEFAULT_STALE_MINUTES
                : safeRequest.getStaleMinutes();
        if (staleMinutes < 5 || staleMinutes > MAX_STALE_MINUTES) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "staleMinutes必须在5到10080之间"
            );
        }

        AgentLibraryHealthRawVO raw = agentLibraryMapper.analyzeLibrary(
                userId,
                LocalDateTime.now().minusMinutes(staleMinutes)
        );
        if (raw == null) {
            raw = new AgentLibraryHealthRawVO();
        }

        AgentLibraryAnalysisVO result = toAnalysis(raw, staleMinutes);
        result.setSuggestions(buildHealthSuggestions(result));
        return result;
    }

    private AgentAdvancedSearchCriteria normalizeCriteria(AgentAdvancedSearchRequest request) {
        AgentAdvancedSearchCriteria criteria = new AgentAdvancedSearchCriteria();
        criteria.setDateField(normalizeEnum(
                request.getDateField(),
                Set.of("taken", "uploaded"),
                "taken",
                "dateField"
        ));
        criteria.setDateFrom(parseBoundary(request.getDateFrom(), false, "dateFrom"));
        criteria.setDateTo(parseBoundary(request.getDateTo(), true, "dateTo"));
        if (criteria.getDateFrom() != null
                && criteria.getDateTo() != null
                && criteria.getDateFrom().isAfter(criteria.getDateTo())) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "dateFrom不能晚于dateTo");
        }

        criteria.setTags(normalizeTags(request.getTags()));
        criteria.setTagOperator(normalizeEnum(
                request.getTagOperator(),
                Set.of("AND", "OR"),
                "OR",
                "tagOperator"
        ).toUpperCase(Locale.ROOT));
        criteria.setCountry(normalizeText(request.getCountry(), 50, "country"));
        criteria.setProvince(normalizeText(request.getProvince(), 50, "province"));
        criteria.setCity(normalizeText(request.getCity(), 50, "city"));
        criteria.setDistrict(normalizeText(request.getDistrict(), 50, "district"));
        criteria.setMake(normalizeText(request.getMake(), 100, "make"));
        criteria.setModel(normalizeText(request.getModel(), 100, "model"));
        criteria.setAlbumId(normalizePositiveId(request.getAlbumId(), "albumId"));
        criteria.setAlbumName(normalizeText(request.getAlbumName(), 100, "albumName"));
        criteria.setPersonId(normalizePositiveId(request.getPersonId(), "personId"));
        criteria.setPersonName(normalizeText(request.getPersonName(), 100, "personName"));
        criteria.setWithoutNormalAlbum(resolveAlbumState(
                request.getNormalAlbumState(),
                request.getWithoutNormalAlbum()
        ));
        if ((criteria.getAlbumId() != null || criteria.getAlbumName() != null)
                && Boolean.TRUE.equals(criteria.getWithoutNormalAlbum())) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "普通相册条件与withoutNormalAlbum=true不能同时使用"
            );
        }
        criteria.setMediaTypes(normalizeMediaTypes(request.getMediaTypes()));
        criteria.setTagState(normalizeEnum(
                request.getTagState(),
                TAG_STATES,
                "all",
                "tagState"
        ));
        criteria.setMissingLocation(resolveCompletenessState(
                request.getLocationState(),
                request.getMissingLocation(),
                "locationState"
        ));
        criteria.setMissingFeature(resolveCompletenessState(
                request.getFeatureState(),
                request.getMissingFeature(),
                "featureState"
        ));
        criteria.setMissingAiAnalysis(resolveCompletenessState(
                request.getAiAnalysisState(),
                request.getMissingAiAnalysis(),
                "aiAnalysisState"
        ));
        criteria.setKeyword(normalizeText(request.getKeyword(), 200, "keyword"));
        criteria.setOrderBy(normalizeEnum(
                request.getOrderBy(),
                Set.of("takenAt", "uploadedAt"),
                "takenAt",
                "orderBy"
        ));
        criteria.setOrderType(normalizeEnum(
                request.getOrderType(),
                Set.of("asc", "desc"),
                "desc",
                "orderType"
        ));
        return criteria;
    }

    private LocalDateTime parseBoundary(String value, boolean endOfDay, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            if (normalized.length() == 10) {
                LocalDate date = LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE);
                return LocalDateTime.of(
                        date,
                        endOfDay ? LocalTime.MAX : LocalTime.MIN
                );
            }
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    fieldName + "必须是YYYY-MM-DD或ISO本地日期时间"
            );
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String tag : expandSerializedListValues(tags)) {
            String normalized = normalizeText(tag, 100, "tags");
            if (normalized != null) {
                result.add(normalized);
            }
            if (result.size() > MAX_TAGS) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "标签条件最多20个");
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> normalizeMediaTypes(List<String> mediaTypes) {
        if (mediaTypes == null || mediaTypes.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String mediaType : expandSerializedListValues(mediaTypes)) {
            String normalized = trimToNull(mediaType);
            if (normalized == null || !MEDIA_TYPES.contains(normalized)) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "mediaTypes取值无效");
            }
            result.add(normalized);
        }
        return new ArrayList<>(result);
    }

    /**
     * Dify 自定义 OpenAPI 工具会把工作流数组变量先转成 JSON 字符串，
     * 再按数组参数包装，例如空数组会到达后端为 ["[]"]。
     * 在业务校验前还原这类值，普通 HTTP 客户端直接提交的数组保持不变。
     */
    private List<String> expandSerializedListValues(List<String> values) {
        List<String> result = new ArrayList<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized == null) {
                continue;
            }
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                try {
                    List<?> decoded = LIST_VALUE_MAPPER.readValue(normalized, List.class);
                    for (Object item : decoded) {
                        if (item != null) {
                            result.add(String.valueOf(item));
                        }
                    }
                    continue;
                } catch (JsonProcessingException ignored) {
                    // 保留原值，由后续字段校验返回明确的参数错误。
                }
            }
            result.add(normalized);
        }
        return result;
    }

    private String buildConditionSummary(AgentAdvancedSearchCriteria criteria) {
        List<String> parts = new ArrayList<>();
        if (criteria.getDateFrom() != null || criteria.getDateTo() != null) {
            parts.add(("uploaded".equals(criteria.getDateField()) ? "上传" : "拍摄")
                    + "时间范围");
        }
        if (!criteria.getTags().isEmpty()) {
            parts.add("标签" + criteria.getTagOperator() + "："
                    + String.join("、", criteria.getTags()));
        }
        addSummary(parts, "国家", criteria.getCountry());
        addSummary(parts, "省", criteria.getProvince());
        addSummary(parts, "城市", criteria.getCity());
        addSummary(parts, "区县", criteria.getDistrict());
        addSummary(parts, "设备品牌", criteria.getMake());
        addSummary(parts, "设备型号", criteria.getModel());
        if (criteria.getAlbumName() != null) {
            parts.add("普通相册「" + criteria.getAlbumName() + "」");
        } else if (criteria.getAlbumId() != null) {
            parts.add("普通相册#" + criteria.getAlbumId());
        }
        if (criteria.getPersonName() != null) {
            parts.add("人物分组「" + criteria.getPersonName() + "」");
        } else if (criteria.getPersonId() != null) {
            parts.add("人物相册#" + criteria.getPersonId());
        }
        if (criteria.getWithoutNormalAlbum() != null) {
            parts.add(criteria.getWithoutNormalAlbum()
                    ? "未加入普通相册"
                    : "已加入普通相册");
        }
        if (!criteria.getMediaTypes().isEmpty()) {
            parts.add("媒体：" + String.join("、", criteria.getMediaTypes()));
        }
        if (!"all".equals(criteria.getTagState())) {
            parts.add("标签状态：" + criteria.getTagState());
        }
        addBooleanSummary(parts, "缺少地点", criteria.getMissingLocation());
        addBooleanSummary(parts, "缺少特征", criteria.getMissingFeature());
        addBooleanSummary(parts, "缺少AI分析", criteria.getMissingAiAnalysis());
        addSummary(parts, "关键词", criteria.getKeyword());
        return parts.isEmpty() ? "当前用户全部未删除文件" : String.join("；", parts);
    }

    private List<AgentSearchRelaxSuggestionVO> buildRelaxSuggestions(
            AgentAdvancedSearchCriteria criteria
    ) {
        List<AgentSearchRelaxSuggestionVO> suggestions = new ArrayList<>();
        if (criteria.getTags().size() > 1 && "AND".equals(criteria.getTagOperator())) {
            suggestions.add(new AgentSearchRelaxSuggestionVO(
                    "tags_and_to_or",
                    "将多标签关系从 AND 改为 OR。"
            ));
        }
        if (criteria.getDateFrom() != null || criteria.getDateTo() != null) {
            suggestions.add(new AgentSearchRelaxSuggestionVO(
                    "expand_date_range",
                    "扩大或暂时移除日期范围。"
            ));
        }
        if (criteria.getDistrict() != null || criteria.getCity() != null
                || criteria.getProvince() != null || criteria.getCountry() != null) {
            suggestions.add(new AgentSearchRelaxSuggestionVO(
                    "relax_location",
                    "从区县逐步放宽到城市、省或国家。"
            ));
        }
        if (criteria.getAlbumId() != null || criteria.getAlbumName() != null
                || criteria.getPersonId() != null || criteria.getPersonName() != null
                || criteria.getWithoutNormalAlbum() != null) {
            suggestions.add(new AgentSearchRelaxSuggestionVO(
                    "remove_album_scope",
                    "暂时移除普通相册或人物相册限制。"
            ));
        }
        if (criteria.getMissingLocation() != null
                || criteria.getMissingFeature() != null
                || criteria.getMissingAiAnalysis() != null) {
            suggestions.add(new AgentSearchRelaxSuggestionVO(
                    "remove_completeness_filter",
                    "暂时移除地点、特征或 AI 分析完整性限制。"
            ));
        }
        if (suggestions.isEmpty()) {
            suggestions.add(new AgentSearchRelaxSuggestionVO(
                    "remove_keyword",
                    "减少关键词或筛选条件后重新查询。"
            ));
        }
        return suggestions;
    }

    private AgentLibraryAnalysisVO toAnalysis(
            AgentLibraryHealthRawVO raw,
            int staleMinutes
    ) {
        AgentLibraryAnalysisVO result = new AgentLibraryAnalysisVO();
        result.setGeneratedAt(Instant.now());
        result.setStaleMinutes(staleMinutes);
        result.setTotalFileCount(zero(raw.getTotalFileCount()));
        result.setUntaggedFileCount(zero(raw.getUntaggedFileCount()));
        result.setMissingLocationFileCount(zero(raw.getMissingLocationFileCount()));
        result.setMissingFeatureFileCount(zero(raw.getMissingFeatureFileCount()));
        result.setMissingAiAnalysisFileCount(zero(raw.getMissingAiAnalysisFileCount()));
        result.setSimilarGroupCount(zero(raw.getSimilarGroupCount()));
        result.setSimilarFileCount(zero(raw.getSimilarFileCount()));
        result.setFailedAiTaskCount(zero(raw.getFailedAiTaskCount()));
        result.setStaleAiTaskCount(zero(raw.getStaleAiTaskCount()));
        result.setPendingAiTaskCount(zero(raw.getPendingAiTaskCount()));
        result.setUnalbumedFileCount(zero(raw.getUnalbumedFileCount()));

        int score = calculateHealthScore(result);
        result.setHealthScore(score);
        result.setHealthLevel(result.getTotalFileCount() == 0
                ? "EMPTY"
                : score >= 90 ? "EXCELLENT"
                : score >= 75 ? "GOOD"
                : score >= 50 ? "NEEDS_ATTENTION"
                : "CRITICAL");
        return result;
    }

    private int calculateHealthScore(AgentLibraryAnalysisVO analysis) {
        long total = analysis.getTotalFileCount();
        if (total <= 0) {
            return 100;
        }
        double penalty = ratio(analysis.getUntaggedFileCount(), total) * 20
                + ratio(analysis.getMissingLocationFileCount(), total) * 10
                + ratio(analysis.getMissingFeatureFileCount(), total) * 15
                + ratio(analysis.getMissingAiAnalysisFileCount(), total) * 15
                + ratio(analysis.getUnalbumedFileCount(), total) * 15
                + ratio(analysis.getSimilarFileCount(), total) * 10;
        penalty += Math.min(10, analysis.getFailedAiTaskCount() * 2.0);
        penalty += Math.min(5, analysis.getStaleAiTaskCount());
        return Math.max(0, Math.min(100, 100 - (int) Math.round(penalty)));
    }

    private List<AgentLibrarySuggestionVO> buildHealthSuggestions(
            AgentLibraryAnalysisVO analysis
    ) {
        List<AgentLibrarySuggestionVO> suggestions = new ArrayList<>();
        if (analysis.getTotalFileCount() == 0) {
            suggestions.add(suggestion(
                    "empty_library",
                    "LOW",
                    0,
                    "图库目前为空",
                    "上传照片后可再次执行健康检查。",
                    "upload_files",
                    Map.of()
            ));
            return suggestions;
        }
        addSuggestionIfPositive(
                suggestions,
                analysis.getFailedAiTaskCount(),
                "failed_ai_tasks",
                "HIGH",
                "处理失败的 AI 任务",
                "存在失败或已停止重试的 AI 任务，建议先查看失败原因。",
                "review_ai_tasks",
                Map.of("statuses", List.of("FAILED", "DEAD"))
        );
        addSuggestionIfPositive(
                suggestions,
                analysis.getStaleAiTaskCount(),
                "stale_ai_tasks",
                "HIGH",
                "检查长期未完成的 AI 任务",
                "部分任务长期处于等待或运行状态，建议检查工作器和任务队列。",
                "review_ai_tasks",
                Map.of("staleMinutes", analysis.getStaleMinutes())
        );
        addSuggestionIfPositive(
                suggestions,
                analysis.getUntaggedFileCount(),
                "untagged_files",
                "MEDIUM",
                "整理未打标签照片",
                "先查看未打标签照片，再选择是否进入 P0 标签预览。",
                "advanced_search",
                Map.of("tagState", "untagged")
        );
        addSuggestionIfPositive(
                suggestions,
                analysis.getMissingLocationFileCount(),
                "missing_location",
                "MEDIUM",
                "检查缺少地点的照片",
                "可先筛选缺少地点的照片；P1 不会自动修改地点。",
                "advanced_search",
                Map.of("missingLocation", true)
        );
        addSuggestionIfPositive(
                suggestions,
                analysis.getMissingFeatureFileCount(),
                "missing_feature",
                "MEDIUM",
                "补齐图片特征前先核对范围",
                "这些图片尚无可用特征；P1 只展示范围，不自动提交特征任务。",
                "advanced_search",
                Map.of("missingFeature", true)
        );
        addSuggestionIfPositive(
                suggestions,
                analysis.getMissingAiAnalysisFileCount(),
                "missing_ai_analysis",
                "MEDIUM",
                "检查缺少 AI 分析的图片",
                "可先筛选缺少 AI 分析结果的图片，再决定是否进入后续任务流程。",
                "advanced_search",
                Map.of("missingAiAnalysis", true)
        );
        addSuggestionIfPositive(
                suggestions,
                analysis.getSimilarFileCount(),
                "similar_files",
                "LOW",
                "查看疑似相似照片",
                "当前仅报告疑似相似组数量，不会删除或合并文件。",
                "review_similar_groups",
                Map.of("similarGroupCount", analysis.getSimilarGroupCount())
        );
        addSuggestionIfPositive(
                suggestions,
                analysis.getUnalbumedFileCount(),
                "unalbumed_files",
                "LOW",
                "整理尚未归入普通相册的照片",
                "先筛选未归相册照片，再由用户选择是否进入 P0 相册预览。",
                "advanced_search",
                Map.of("withoutNormalAlbum", true)
        );
        return suggestions;
    }

    private void addSuggestionIfPositive(
            List<AgentLibrarySuggestionVO> suggestions,
            Long count,
            String code,
            String priority,
            String title,
            String description,
            String recommendedAction,
            Map<String, Object> filters
    ) {
        if (count != null && count > 0) {
            suggestions.add(suggestion(
                    code,
                    priority,
                    count,
                    title,
                    description,
                    recommendedAction,
                    filters
            ));
        }
    }

    private AgentLibrarySuggestionVO suggestion(
            String code,
            String priority,
            long count,
            String title,
            String description,
            String recommendedAction,
            Map<String, Object> filters
    ) {
        AgentLibrarySuggestionVO suggestion = new AgentLibrarySuggestionVO();
        suggestion.setCode(code);
        suggestion.setPriority(priority);
        suggestion.setAffectedCount(count);
        suggestion.setTitle(title);
        suggestion.setDescription(description);
        suggestion.setRecommendedAction(recommendedAction);
        suggestion.setSuggestedFilters(new LinkedHashMap<>(filters));
        return suggestion;
    }

    private void addSummary(List<String> parts, String label, String value) {
        if (value != null) {
            parts.add(label + "：" + value);
        }
    }

    private void addBooleanSummary(List<String> parts, String label, Boolean value) {
        if (value != null) {
            parts.add((value ? "" : "不") + label);
        }
    }

    private String normalizeEnum(
            String value,
            Set<String> allowed,
            String defaultValue,
            String fieldName
    ) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return defaultValue;
        }
        if ("tagOperator".equals(fieldName)) {
            normalized = normalized.toUpperCase(Locale.ROOT);
        }
        if (!allowed.contains(normalized)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, fieldName + "取值无效");
        }
        return normalized;
    }

    private Boolean resolveAlbumState(String state, Boolean legacyValue) {
        if (trimToNull(state) == null) {
            return legacyValue;
        }
        String normalized = normalizeEnum(
                state,
                Set.of("any", "included", "excluded"),
                "any",
                "normalAlbumState"
        );
        return switch (normalized) {
            case "included" -> false;
            case "excluded" -> true;
            default -> null;
        };
    }

    private Boolean resolveCompletenessState(
            String state,
            Boolean legacyValue,
            String fieldName
    ) {
        if (trimToNull(state) == null) {
            return legacyValue;
        }
        String normalized = normalizeEnum(
                state,
                Set.of("any", "missing", "present"),
                "any",
                fieldName
        );
        return switch (normalized) {
            case "missing" -> true;
            case "present" -> false;
            default -> null;
        };
    }

    private Long normalizePositiveId(Long value, String fieldName) {
        if (value == null || value == -1L) {
            return null;
        }
        if (value <= 0) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, fieldName + "必须为正整数");
        }
        return value;
    }

    private String normalizeText(String value, int maxLength, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, fieldName + "长度超出限制");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private long normalizePage(Integer current) {
        return current == null || current < 1 ? 1 : current;
    }

    private long normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private List<String> splitTags(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(value.split("\\|"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    private long zero(Long value) {
        return value == null ? 0L : value;
    }

    private double ratio(long value, long total) {
        return total <= 0 ? 0 : Math.min(1.0, (double) value / total);
    }
}
