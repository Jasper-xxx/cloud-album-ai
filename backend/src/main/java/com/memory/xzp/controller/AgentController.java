package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.config.AgentAccessGuard;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PictureTagMapper;
import com.memory.xzp.model.dto.agent.AgentAlbumActionRequest;
import com.memory.xzp.model.dto.agent.AgentAlbumQueryRequest;
import com.memory.xzp.model.dto.agent.AgentAdvancedSearchRequest;
import com.memory.xzp.model.dto.agent.AgentExecuteActionRequest;
import com.memory.xzp.model.dto.agent.AgentExtendedActionRequest;
import com.memory.xzp.model.dto.agent.AgentImageTagTaskPreviewRequest;
import com.memory.xzp.model.dto.agent.AgentLibraryAnalysisRequest;
import com.memory.xzp.model.dto.agent.AgentPendingActionAccessRequest;
import com.memory.xzp.model.dto.agent.AgentPendingActionClaim;
import com.memory.xzp.model.dto.agent.AgentPendingActionPayload;
import com.memory.xzp.model.dto.agent.AgentPersonQueryRequest;
import com.memory.xzp.model.dto.agent.AgentSearchFilesRequest;
import com.memory.xzp.model.dto.agent.AgentSuggestedTagsPreviewRequest;
import com.memory.xzp.model.dto.agent.AgentTagActionRequest;
import com.memory.xzp.model.dto.agent.AgentTaskStatusRequest;
import com.memory.xzp.model.dto.picture.PictureTagMappingDTO;
import com.memory.xzp.model.entity.Album;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.model.vo.agent.AgentActionPreviewVO;
import com.memory.xzp.model.vo.agent.AgentActionResultVO;
import com.memory.xzp.model.vo.agent.AgentAttachmentUploadVO;
import com.memory.xzp.model.vo.agent.AgentAdvancedSearchResultVO;
import com.memory.xzp.model.vo.agent.AgentCapabilitiesVO;
import com.memory.xzp.model.vo.agent.AgentFileReferenceVO;
import com.memory.xzp.model.vo.agent.AgentLibraryAnalysisVO;
import com.memory.xzp.model.vo.agent.AgentImageTagTaskStatusVO;
import com.memory.xzp.model.vo.agent.AgentPendingActionStatusVO;
import com.memory.xzp.model.vo.album.AlbumVO;
import com.memory.xzp.model.vo.album.LocationAlbumVO;
import com.memory.xzp.model.vo.album.ModelAlbumVO;
import com.memory.xzp.model.vo.album.PersonAlbumVO;
import com.memory.xzp.model.vo.entity.FileInfo;
import com.memory.xzp.model.vo.entity.ImageSearchResultVO;
import com.memory.xzp.model.vo.SimilarFileInfoListVO;
import com.memory.xzp.model.vo.visual.FileTagVO;
import com.memory.xzp.service.AlbumService;
import com.memory.xzp.service.AgentLibraryService;
import com.memory.xzp.service.AgentImageTagService;
import com.memory.xzp.service.AgentAttachmentService;
import com.memory.xzp.service.AgentExtendedActionService;
import com.memory.xzp.service.AgentPendingActionService;
import com.memory.xzp.service.AgentWriteExecutionService;
import com.memory.xzp.service.FileService;
import com.memory.xzp.service.PersonService;
import com.memory.xzp.service.SimilarDetectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dify 智能体调用的后端门面。
 *
 * <p>统一暴露 P0～P4 工具；除明确的附件上传外，业务写操作都通过服务端预览确认链路执行。</p>
 */
@RestController
@RequestMapping("/agent")
@Tag(name = "智能体接口", description = "Dify 智能体聚合接口")
@Slf4j
public class AgentController {
    @Resource
    private AgentAccessGuard agentAccessGuard;

    private static final int DEFAULT_CURRENT = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int MAX_WRITE_FILE_COUNT = 100;
    private static final int MAX_TAG_SELECTOR_COUNT = 20;
    private static final int MAX_ALBUM_NAME_LENGTH = 30;
    private static final int MAX_TAG_NAME_LENGTH = 30;
    private static final Set<String> IMAGE_TYPES = new HashSet<>(Arrays.asList("picture", "gif", "video", "all"));
    private static final Set<String> LOCATION_LEVELS = new HashSet<>(Arrays.asList("country", "province", "city", "district"));
    private static final Set<String> TAG_FILTERS = new HashSet<>(Arrays.asList("all", "untagged"));
    private static final Set<String> ALBUM_WRITE_ACTIONS = new HashSet<>(Arrays.asList(
            "create_album",
            "add_files_to_album",
            "remove_files_from_album",
            "create_album_and_add_files"
    ));
    private static final Set<String> TAG_WRITE_ACTIONS = new HashSet<>(Arrays.asList("add_tags", "remove_tags"));
    private static final Set<String> WRITE_SEARCH_TYPES = new HashSet<>(Arrays.asList(
            "tag", "location", "model", "latest", "all"
    ));
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    );
    private static final Pattern OPAQUE_SECRET_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{32,128}$");

    @Resource
    private FileService fileService;

    @Resource
    private AlbumService albumService;

    @Resource
    private PersonService personService;

    @Resource
    private PictureTagMapper pictureTagMapper;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private AgentPendingActionService pendingActionService;

    @Resource
    private AgentWriteExecutionService writeExecutionService;

    @Resource
    private AgentLibraryService agentLibraryService;

    @Resource
    private AgentImageTagService agentImageTagService;

    @Resource
    private AgentExtendedActionService agentExtendedActionService;

    @Resource
    private AgentAttachmentService agentAttachmentService;

    @Resource
    private SimilarDetectService similarDetectService;
    @Resource
    private com.memory.xzp.service.AgentDiscoveryJobService discoveryJobService;


    @GetMapping("/capabilities")
    @Operation(summary = "查询智能体能力边界", description = "返回当前开放、需确认和禁用的智能体工具")
    public BaseResponse<AgentCapabilitiesVO> capabilities() {
        AgentCapabilitiesVO capabilities = new AgentCapabilitiesVO();
        capabilities.setName("云忆相册助手");
        capabilities.setMode("trusted_agent_full_p0_p4");
        capabilities.setReadOnlyTools(List.of(
                "search_files",
                "list_albums",
                "list_location_albums",
                "list_model_albums",
                "list_tags",
                "list_people",
                "advanced_search_files",
                "analyze_library",
                "discover_similar_files",
                "search_by_attachment",
                "get_agent_task_status",
                "get_pending_action_status"
        ));
        capabilities.setDirectMutationTools(List.of(
                "upload_attachment"
        ));
        capabilities.setConfirmationRequiredTools(List.of(
                "create_album",
                "add_files_to_album",
                "remove_files_from_album",
                "add_tags",
                "remove_tags",
                "preview_album_action",
                "execute_album_action",
                "preview_tag_action",
                "execute_tag_action",
                "preview_image_tag_task",
                "submit_image_tag_task",
                "preview_apply_suggested_tags",
                "execute_apply_suggested_tags",
                "preview_p3_action",
                "execute_p3_action",
                "preview_p4_action",
                "execute_p4_action",
                "cancel_pending_action"
        ));
        capabilities.setDisabledTools(List.of(
                "update_user_account"
        ));
        capabilities.setRiskRules(List.of(
                "默认只读，写操作必须先展示执行预览并等待用户明确确认。",
                "所有查询都基于当前登录用户，不能跨用户访问数据。",
                "不返回 API Key、数据库密码、MinIO 密钥、JWT 密钥、原始 feature_vector。",
                "不对人物照片做真实身份、年龄或敏感属性断言。",
                "分享、删除及下载凭证使用90秒高风险确认，永久删除单次最多10个文件。",
                "聊天附件必须先由上传接口返回真实fileId，不能用文件名或视觉描述伪造上传结果。"
        ));
        return ResultUtil.success(capabilities, "获取智能体能力成功");
    }

    @GetMapping("/activity")
    public BaseResponse<?> activity() {
        return ResultUtil.success(pendingActionService.recentActivity(currentUserId()),"操作记录查询成功");
    }

    @PostMapping("/searchFiles")
    @Operation(summary = "智能体照片检索", description = "按类型、地点、相册、标签或关键词检索当前用户照片")
    public BaseResponse<Page<FileInfoListVO>> searchFiles(@RequestBody(required = false) AgentSearchFilesRequest request) {
        if (request == null) {
            request = new AgentSearchFilesRequest();
        }
        Long userId = currentUserId();
        int current = normalizeCurrent(request.getCurrent());
        int size = normalizeSize(request.getSize());
        String orderType = normalizeOrderType(request.getOrderType());
        String orderKeyword = normalizeFileOrderKeyword(request.getOrderKeyword());
        String imageTypeText = normalizeImageType(request.getImageTypeText());

        String tagName = trimToNull(request.getTagName());
        if (tagName != null) {
            Page<FileInfoListVO> page = fileService.getTagFileInfo(
                    current,
                    size,
                    userId,
                    orderType,
                    orderKeyword,
                    imageTypeText,
                    tagName
            );
            return ResultUtil.success(page, "获取照片成功");
        }

        String searchType = trimToNull(request.getSearchType());
        String searchKeyword = trimToNull(request.getSearchKeyword());
        if (searchType != null && searchKeyword != null) {
            Page<FileInfoListVO> page = searchByKeyword(
                    current,
                    size,
                    userId,
                    orderType,
                    orderKeyword,
                    imageTypeText,
                    searchType,
                    searchKeyword
            );
            return ResultUtil.success(page, "获取照片成功");
        }

        String locationLevel = normalizeLocationLevel(request.getLocationLevel());
        String locationValue = trimToNull(request.getLocationValue());
        if (locationValue == null) {
            locationLevel = null;
        }
        String tagFilter = normalizeTagFilter(request.getTagFilter());
        Long albumId = request.getAlbumId();
        if (albumId != null && albumId == -1L) {
            albumId = null;
        }

        Page<FileInfoListVO> page = fileService.getFileInfoList(
                current,
                size,
                orderType,
                orderKeyword,
                imageTypeText,
                locationLevel,
                locationValue,
                tagFilter,
                userId,
                albumId,
                false
        );
        return ResultUtil.success(page, "获取照片成功");
    }

    @PostMapping("/advancedSearchFiles")
    @Operation(
            summary = "智能体组合检索照片",
            description = "按日期、多标签、地点层级、设备、相册、人物、媒体类型和数据完整性组合检索当前用户文件"
    )
    public BaseResponse<AgentAdvancedSearchResultVO> advancedSearchFiles(
            @RequestBody(required = false) AgentAdvancedSearchRequest request
    ) {
        AgentAdvancedSearchResultVO result =
                agentLibraryService.advancedSearch(request, currentUserId());
        return ResultUtil.success(result, "组合检索照片成功");
    }

    @PostMapping("/analyzeLibrary")
    @Operation(
            summary = "智能体分析图库健康度",
            description = "只读统计未标签、缺少元数据、相似文件和异常AI任务，并给出整理建议"
    )
    public BaseResponse<AgentLibraryAnalysisVO> analyzeLibrary(
            @RequestBody(required = false) AgentLibraryAnalysisRequest request
    ) {
        AgentLibraryAnalysisVO result =
                agentLibraryService.analyzeLibrary(request, currentUserId());
        return ResultUtil.success(result, "分析图库成功");
    }

    @PostMapping("/discoverSimilarFiles")
    @Operation(
            summary = "发现相似或重复照片",
            description = "只读、实时返回当前用户的候选相似照片组，不写入特征向量或删除数据"
    )
    public BaseResponse<?> discoverSimilarFiles(
            @RequestBody(required = false) AgentExtendedActionRequest request
    ) {
        if (request == null) {
            request = new AgentExtendedActionRequest();
        }
        if (!request.getUnexpectedFields().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求包含不支持的参数");
        }
        double similarity = request.getSimilarity() == null ? 0.90 : request.getSimilarity();
        int size = request.getSize() == null ? 20 : request.getSize();
        if (similarity < 0.50 || similarity > 1.0 || size < 1 || size > 50) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "similarity或size超出允许范围");
        }
        return ResultUtil.success(discoveryJobService.submit(currentUserId(), similarity, size),
                "相似发现任务已受理，请在操作记录查看进度；尚未确认完成");
    }

    @PostMapping("/previewP3Action")
    @Operation(summary = "预览P3整理操作", description = "冻结特征、地点、恢复或人物整理范围并签发确认凭证")
    public BaseResponse<AgentActionPreviewVO> previewP3Action(
            @RequestBody AgentExtendedActionRequest request
    ) {
        return ResultUtil.success(
                agentExtendedActionService.previewP3(request, currentUserId()),
                "生成P3操作预览成功"
        );
    }

    @PostMapping("/executeP3Action")
    @Operation(summary = "执行P3整理操作", description = "消费一次性凭证并执行服务端冻结的P3操作")
    public BaseResponse<AgentActionResultVO> executeP3Action(
            HttpServletRequest servletRequest,
            @RequestBody AgentExecuteActionRequest request
    ) {
        return executeExtendedAction(
                request,
                AgentExtendedActionService.FAMILY_P3,
                servletRequest,
                "P3操作执行成功"
        );
    }

    @PostMapping(value = "/uploadAttachment", consumes = "multipart/form-data")
    @Operation(
            summary = "上传聊天附件",
            description = "把已授权聊天附件上传到Cloud-Album，并返回可用于后续工具调用的真实fileId"
    )
    public BaseResponse<AgentAttachmentUploadVO> uploadAttachment(
            HttpServletRequest servletRequest,
            @RequestParam("attachment") MultipartFile attachment,
            @RequestParam(value = "albumId", required = false) Long albumId,
            @RequestParam(value = "lastModified", required = false) Long lastModified
    ) {
        AgentAttachmentUploadVO result = agentAttachmentService.upload(
                attachment,
                albumId,
                lastModified,
                agentAccessGuard.requireUserId(servletRequest),
                servletRequest
        );
        return ResultUtil.success(result, "聊天附件已上传");
    }

    @PostMapping(value = "/searchByAttachment", consumes = "multipart/form-data")
    @Operation(summary = "使用聊天附件以图搜图", description = "附件只作为查询图，不会自动写入Cloud-Album")
    public BaseResponse<List<ImageSearchResultVO>> searchByAttachment(
            HttpServletRequest servletRequest,
            @RequestParam("attachment") MultipartFile attachment,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestParam(value = "albumIds", required = false) List<Long> albumIds,
            @RequestParam(value = "tagNames", required = false) List<String> tagNames,
            @RequestParam(value = "sizeRange", required = false) String sizeRange
    ) {
        List<ImageSearchResultVO> result = agentAttachmentService.search(
                attachment,
                mode,
                albumIds,
                tagNames,
                sizeRange,
                agentAccessGuard.requireUserId(servletRequest)
        );
        return ResultUtil.success(result, "附件以图搜图完成");
    }

    @PostMapping("/previewP4Action")
    @Operation(
            summary = "预览P4高风险操作",
            description = "展示公开范围、有效期或不可逆影响，并签发90秒一次性确认凭证"
    )
    public BaseResponse<AgentActionPreviewVO> previewP4Action(
            @RequestBody AgentExtendedActionRequest request
    ) {
        return ResultUtil.success(
                agentExtendedActionService.previewP4(request, currentUserId()),
                "生成P4高风险操作预览成功"
        );
    }

    @PostMapping("/executeP4Action")
    @Operation(
            summary = "执行P4高风险操作",
            description = "消费短期一次性确认凭证并执行服务端冻结的分享、下载或删除操作"
    )
    public BaseResponse<AgentActionResultVO> executeP4Action(
            HttpServletRequest servletRequest,
            @RequestBody AgentExecuteActionRequest request
    ) {
        return executeExtendedAction(
                request,
                AgentExtendedActionService.FAMILY_P4,
                servletRequest,
                "P4操作执行成功"
        );
    }

    @PostMapping("/previewImageTagTask")
    @Operation(
            summary = "预览AI标签建议任务",
            description = "冻结最多50张当前用户图片并签发一次性确认凭证，不提交任务也不写入标签"
    )
    public BaseResponse<AgentActionPreviewVO> previewImageTagTask(
            @RequestBody AgentImageTagTaskPreviewRequest request
    ) {
        AgentActionPreviewVO preview =
                agentImageTagService.previewImageTagTask(request, currentUserId());
        return ResultUtil.success(preview, "生成AI标签任务预览成功");
    }

    @PostMapping("/submitImageTagTask")
    @Operation(
            summary = "提交AI标签建议任务",
            description = "消费一次性确认凭证，为冻结图片提交autoAdd=false的持久化标签任务"
    )
    public BaseResponse<AgentActionResultVO> submitImageTagTask(
            @RequestBody AgentExecuteActionRequest request
    ) {
        validateExecuteRequest(request);
        Long userId = currentUserId();
        AgentPendingActionClaim claim = pendingActionService.claim(
                request.getPendingActionId(),
                request.getConfirmationToken(),
                request.getIdempotencyKey(),
                userId,
                "ai_task"
        );
        if (claim.isIdempotentReplay()) {
            return ResultUtil.success(claim.getCachedResult(), "返回AI标签任务的幂等提交结果");
        }
        try {
            AgentActionResultVO result =
                    agentImageTagService.submitImageTagTask(claim.getPayload(), userId);
            return ResultUtil.success(result, "AI标签建议任务提交成功");
        } catch (RuntimeException exception) {
            markExecutionFailed(request.getPendingActionId(), userId, exception);
            throw exception;
        }
    }

    @PostMapping("/getAgentTaskStatus")
    @Operation(
            summary = "查询智能体AI任务状态",
            description = "聚合当前用户标签任务批次进度，并在成功后返回置信度达标的候选标签"
    )
    public BaseResponse<AgentImageTagTaskStatusVO> getAgentTaskStatus(
            @RequestBody AgentTaskStatusRequest request
    ) {
        if (request == null
                || !request.getUnexpectedFields().isEmpty()
                || request.getAgentTaskId() == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "agentTaskId参数无效");
        }
        AgentImageTagTaskStatusVO status = agentImageTagService.getAgentTaskStatus(
                request.getAgentTaskId(),
                currentUserId()
        );
        return ResultUtil.success(status, "获取AI标签任务状态成功");
    }

    @PostMapping("/previewApplySuggestedTags")
    @Operation(
            summary = "预览应用AI标签建议",
            description = "校验候选标签确实来自当前用户已完成任务，冻结选择并签发一次性确认凭证"
    )
    public BaseResponse<AgentActionPreviewVO> previewApplySuggestedTags(
            @RequestBody AgentSuggestedTagsPreviewRequest request
    ) {
        AgentActionPreviewVO preview =
                agentImageTagService.previewApplySuggestedTags(request, currentUserId());
        return ResultUtil.success(preview, "生成AI标签建议写入预览成功");
    }

    @PostMapping("/executeApplySuggestedTags")
    @Operation(
            summary = "执行应用AI标签建议",
            description = "消费一次性确认凭证，只使用服务端冻结且已校验的AI标签建议写入"
    )
    public BaseResponse<AgentActionResultVO> executeApplySuggestedTags(
            HttpServletRequest servletRequest,
            @RequestBody AgentExecuteActionRequest request
    ) {
        validateExecuteRequest(request);
        Long userId = currentUserId();
        AgentPendingActionClaim claim = pendingActionService.claim(
                request.getPendingActionId(),
                request.getConfirmationToken(),
                request.getIdempotencyKey(),
                userId,
                "suggested_tag"
        );
        if (claim.isIdempotentReplay()) {
            return ResultUtil.success(claim.getCachedResult(), "返回AI标签建议写入的幂等执行结果");
        }
        try {
            AgentActionResultVO result = writeExecutionService.executeSuggestedTags(
                    claim.getPayload(),
                    userId,
                    servletRequest
            );
            return ResultUtil.success(result, "AI标签建议应用成功");
        } catch (RuntimeException exception) {
            markExecutionFailed(request.getPendingActionId(), userId, exception);
            throw exception;
        }
    }

    @PostMapping("/listAlbums")
    @Operation(summary = "智能体查询普通相册", description = "分页查询当前用户普通相册")
    public BaseResponse<Page<AlbumVO>> listAlbums(@RequestBody(required = false) AgentAlbumQueryRequest request) {
        if (request == null) {
            request = new AgentAlbumQueryRequest();
        }
        Page<AlbumVO> page = albumService.selectAllAlbum(
                normalizeCurrent(request.getCurrent()),
                normalizeSize(request.getSize()),
                normalizeAlbumOrderKeyword(request.getOrderKeyword()),
                normalizeOrderType(request.getOrderType()),
                currentUserId()
        );
        return ResultUtil.success(page, "获取相册成功");
    }

    @PostMapping("/listLocationAlbums")
    @Operation(summary = "智能体查询地点相册", description = "分页查询当前用户地点相册")
    public BaseResponse<Page<LocationAlbumVO>> listLocationAlbums(@RequestBody(required = false) AgentAlbumQueryRequest request) {
        if (request == null) {
            request = new AgentAlbumQueryRequest();
        }
        Page<LocationAlbumVO> page = albumService.selectAllLocationAlbum(
                normalizeCurrent(request.getCurrent()),
                normalizeSize(request.getSize()),
                defaultLocationLevel(request.getLocationLevel()),
                currentUserId()
        );
        return ResultUtil.success(page, "获取地点相册成功");
    }

    @PostMapping("/listModelAlbums")
    @Operation(summary = "智能体查询设备相册", description = "分页查询当前用户设备/型号相册")
    public BaseResponse<Page<ModelAlbumVO>> listModelAlbums(@RequestBody(required = false) AgentAlbumQueryRequest request) {
        if (request == null) {
            request = new AgentAlbumQueryRequest();
        }
        Page<ModelAlbumVO> page = albumService.selectAllModelAlbum(
                normalizeCurrent(request.getCurrent()),
                normalizeSize(request.getSize()),
                currentUserId()
        );
        return ResultUtil.success(page, "获取设备相册成功");
    }

    @GetMapping("/listTags")
    @Operation(summary = "智能体查询标签", description = "查询当前用户已有标签及数量")
    public BaseResponse<List<FileTagVO>> listTags() {
        List<FileTagVO> tags = pictureTagMapper.selectAllTags(currentUserId());
        return ResultUtil.success(tags, "获取标签成功");
    }

    @PostMapping("/listPeople")
    @Operation(summary = "智能体查询人物相册", description = "分页查询当前用户人物相册")
    public BaseResponse<Page<PersonAlbumVO>> listPeople(@RequestBody(required = false) AgentPersonQueryRequest request) {
        if (request == null) {
            request = new AgentPersonQueryRequest();
        }
        Boolean display = request.getDisplay() == null ? true : request.getDisplay();
        Page<PersonAlbumVO> page = personService.selectAllPersonAlbum(
                currentUserId(),
                normalizeCurrent(request.getCurrent()),
                normalizeSize(request.getSize()),
                display
        );
        return ResultUtil.success(page, "获取人物相册成功");
    }

    @PostMapping("/previewAlbumAction")
    @Operation(
            summary = "预览智能体相册写操作",
            description = "冻结相册操作范围并签发一次性确认凭证，不修改相册或照片业务数据"
    )
    public BaseResponse<AgentActionPreviewVO> previewAlbumAction(@RequestBody AgentAlbumActionRequest request) {
        Long userId = currentUserId();
        AgentActionPreviewVO preview = buildAlbumActionPreview(request, userId);
        pendingActionService.register(preview, albumPendingPayload(preview), userId);
        return ResultUtil.success(preview, "生成相册操作预览成功");
    }

    @PostMapping("/executeAlbumAction")
    @Operation(
            summary = "执行智能体相册写操作",
            description = "消费预览签发的一次性凭证，并只使用服务端冻结范围执行"
    )
    public BaseResponse<AgentActionResultVO> executeAlbumAction(
            HttpServletRequest servletRequest,
            @RequestBody AgentExecuteActionRequest request
    ) {
        validateExecuteRequest(request);
        Long userId = currentUserId();
        AgentPendingActionClaim claim = pendingActionService.claim(
                request.getPendingActionId(),
                request.getConfirmationToken(),
                request.getIdempotencyKey(),
                userId,
                "album"
        );
        if (claim.isIdempotentReplay()) {
            return ResultUtil.success(claim.getCachedResult(), "返回相册操作的幂等执行结果");
        }
        try {
            AgentActionResultVO result = writeExecutionService.executeAlbum(
                    claim.getPayload(),
                    userId,
                    servletRequest
            );
            return ResultUtil.success(result, "相册操作执行成功");
        } catch (RuntimeException exception) {
            markExecutionFailed(request.getPendingActionId(), userId, exception);
            throw exception;
        }
    }

    @PostMapping("/previewTagAction")
    @Operation(
            summary = "预览智能体标签写操作",
            description = "冻结标签操作范围并签发一次性确认凭证，不修改照片标签业务数据"
    )
    public BaseResponse<AgentActionPreviewVO> previewTagAction(@RequestBody AgentTagActionRequest request) {
        Long userId = currentUserId();
        AgentActionPreviewVO preview = buildTagActionPreview(request, userId);
        pendingActionService.register(
                preview,
                tagPendingPayload(preview, normalizeImageTypeForTag(request == null ? null : request.getImageType())),
                userId
        );
        return ResultUtil.success(preview, "生成标签操作预览成功");
    }

    @PostMapping("/executeTagAction")
    @Operation(
            summary = "执行智能体标签写操作",
            description = "消费预览签发的一次性凭证，并只使用服务端冻结范围执行"
    )
    public BaseResponse<AgentActionResultVO> executeTagAction(
            HttpServletRequest servletRequest,
            @RequestBody AgentExecuteActionRequest request
    ) {
        validateExecuteRequest(request);
        Long userId = currentUserId();
        AgentPendingActionClaim claim = pendingActionService.claim(
                request.getPendingActionId(),
                request.getConfirmationToken(),
                request.getIdempotencyKey(),
                userId,
                "tag"
        );
        if (claim.isIdempotentReplay()) {
            return ResultUtil.success(claim.getCachedResult(), "返回标签操作的幂等执行结果");
        }
        try {
            AgentActionResultVO result = writeExecutionService.executeTag(
                    claim.getPayload(),
                    userId,
                    servletRequest
            );
            return ResultUtil.success(result, "标签操作执行成功");
        } catch (RuntimeException exception) {
            markExecutionFailed(request.getPendingActionId(), userId, exception);
            throw exception;
        }
    }

    @PostMapping("/getPendingActionStatus")
    @Operation(summary = "查询待确认操作状态", description = "查询当前用户待确认操作的生命周期状态")
    public BaseResponse<AgentPendingActionStatusVO> getPendingActionStatus(
            @RequestBody AgentPendingActionAccessRequest request
    ) {
        validateStatusRequest(request);
        AgentPendingActionStatusVO status = pendingActionService.getStatus(
                request.getPendingActionId(),
                currentUserId()
        );
        return ResultUtil.success(status, "获取待确认操作状态成功");
    }

    @PostMapping("/cancelPendingAction")
    @Operation(summary = "取消待确认操作", description = "使用预览凭证取消尚未执行的操作")
    public BaseResponse<AgentPendingActionStatusVO> cancelPendingAction(
            @RequestBody AgentPendingActionAccessRequest request
    ) {
        validateCancelRequest(request);
        AgentPendingActionStatusVO status = pendingActionService.cancel(
                request.getPendingActionId(),
                request.getConfirmationToken(),
                currentUserId()
        );
        return ResultUtil.success(status, "待确认操作已取消");
    }

    private BaseResponse<AgentActionResultVO> executeExtendedAction(
            AgentExecuteActionRequest request,
            String family,
            HttpServletRequest servletRequest,
            String successMessage
    ) {
        validateExecuteRequest(request);
        Long userId = currentUserId();
        AgentPendingActionClaim claim = pendingActionService.claim(
                request.getPendingActionId(),
                request.getConfirmationToken(),
                request.getIdempotencyKey(),
                userId,
                family
        );
        if (claim.isIdempotentReplay()) {
            return ResultUtil.success(claim.getCachedResult(), "返回扩展操作的幂等执行结果");
        }
        try {
            AgentActionResultVO result = agentExtendedActionService.execute(
                    claim.getPayload(),
                    userId,
                    family,
                    servletRequest
            );
            return ResultUtil.success(result, successMessage);
        } catch (RuntimeException exception) {
            markExecutionFailed(request.getPendingActionId(), userId, exception);
            throw exception;
        }
    }

    private Page<FileInfoListVO> searchByKeyword(
            int current,
            int size,
            Long userId,
            String orderType,
            String orderKeyword,
            String imageTypeText,
            String searchType,
            String searchKeyword
    ) {
        return switch (searchType) {
            case "tag" -> fileService.getTagFileInfo(current, size, userId, orderType, orderKeyword, imageTypeText, searchKeyword);
            case "model" -> albumService.getModelFileInfo(current, size, userId, orderType, orderKeyword, imageTypeText, null, searchKeyword);
            case "location" -> fileService.getFileInfoList(current, size, orderType, orderKeyword, imageTypeText, "city", searchKeyword, "all", userId, null, false);
            default -> new Page<>(current, size);
        };
    }

    private AgentActionPreviewVO buildAlbumActionPreview(AgentAlbumActionRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求不能为空");
        }
        validateAlbumPreviewRequest(request);
        String action = normalizeAction(request.getAction(), ALBUM_WRITE_ACTIONS);
        List<String> resolvedFileIds = "create_album".equals(action)
                ? new ArrayList<>()
                : resolveAlbumActionFileIds(request, userId);
        List<String> fileIds = new ArrayList<>(resolvedFileIds);
        AgentActionPreviewVO preview = new AgentActionPreviewVO();
        preview.setAction(action);
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());

        switch (action) {
            case "create_album" -> {
                setPreviewFileIds(preview, new ArrayList<>());
                String albumName = normalizeAlbumName(request.getAlbumName());
                Album existingAlbum = findAlbumByName(albumName, userId);
                if (existingAlbum != null) {
                    preview.setAlbumId(existingAlbum.getAlbumId());
                    preview.setCreatedAlbumCount(0);
                    preview.setTitle("相册已存在");
                    preview.setSummary("相册「" + albumName + "」已经存在，不会重复创建。");
                } else {
                    preview.setCreatedAlbumCount(1);
                    preview.setTitle("创建相册");
                    preview.setSummary("将创建相册「" + albumName + "」。");
                }
                preview.setAlbumName(albumName);
            }
            case "create_album_and_add_files" -> {
                String albumName = normalizeAlbumName(request.getAlbumName());
                Album existingAlbum = findAlbumByName(albumName, userId);
                if (existingAlbum != null) {
                    fileIds = filterAlbumFileIds(fileIds, existingAlbum.getAlbumId(), userId, false);
                    setPreviewFileIds(preview, fileIds);
                    preview.setAlbumId(existingAlbum.getAlbumId());
                    preview.setCreatedAlbumCount(0);
                    preview.setTitle("添加照片到已有相册");
                    preview.setSummary("找到已有相册「" + albumName + "」，将把 " + fileIds.size() + " 张匹配照片加入其中。");
                } else {
                    preview.setCreatedAlbumCount(fileIds.isEmpty() ? 0 : 1);
                    preview.setTitle("创建相册并加入照片");
                    preview.setSummary("将创建相册「" + albumName + "」，并加入 " + fileIds.size() + " 张匹配照片。");
                }
                preview.setAlbumName(albumName);
            }
            case "add_files_to_album" -> {
                AlbumVO album = requireAlbum(request.getAlbumId(), request.getAlbumName(), userId);
                fileIds = filterAlbumFileIds(fileIds, album.getAlbumId(), userId, false);
                setPreviewFileIds(preview, fileIds);
                preview.setAlbumId(album.getAlbumId());
                preview.setAlbumName(album.getAlbumName());
                preview.setTitle("添加照片到相册");
                preview.setSummary("将 " + fileIds.size() + " 张匹配照片加入已有相册「" + album.getAlbumName() + "」。");
            }
            case "remove_files_from_album" -> {
                AlbumVO album = requireAlbum(request.getAlbumId(), request.getAlbumName(), userId);
                fileIds = filterAlbumFileIds(fileIds, album.getAlbumId(), userId, true);
                setPreviewFileIds(preview, fileIds);
                preview.setAlbumId(album.getAlbumId());
                preview.setAlbumName(album.getAlbumName());
                preview.setTitle("从相册移出照片");
                preview.setSummary("将 " + fileIds.size() + " 张照片从相册「" + album.getAlbumName() + "」移出，不会删除照片文件。");
            }
            default -> throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的相册操作");
        }

        addSkippedFileWarning(preview, request.getFileIds(), resolvedFileIds);
        addNoChangeWarning(preview, resolvedFileIds.size() - fileIds.size(), action);
        preview.setAffectedFiles(toFileReferences(preview.getFileIds(), userId));
        finishPreview(preview, request.getSearchType(), request.getSearchKeyword());
        return preview;
    }

    private AgentActionPreviewVO buildTagActionPreview(AgentTagActionRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求不能为空");
        }
        validateTagPreviewRequest(request);
        String action = normalizeAction(request.getAction(), TAG_WRITE_ACTIONS);
        String tagName = normalizeTagName(request.getTagName());
        List<String> resolvedFileIds = resolveTagActionFileIds(request, userId);
        List<String> exclusivelyOwnedFileIds = resolvedFileIds.isEmpty()
                ? new ArrayList<>()
                : retainInOriginalOrder(
                        resolvedFileIds,
                        fileMapper.selectExclusivelyOwnedActiveFileIds(resolvedFileIds, userId)
                );
        List<String> fileIds = filterTagDeltaFileIds(
                exclusivelyOwnedFileIds,
                tagName,
                userId,
                "remove_tags".equals(action)
        );

        AgentActionPreviewVO preview = new AgentActionPreviewVO();
        preview.setAction(action);
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());
        preview.setTagName(tagName);

        if ("add_tags".equals(action)) {
            preview.setTitle("添加照片标签");
            preview.setSummary("将为 " + fileIds.size() + " 张照片添加标签「" + tagName + "」。");
        } else if ("remove_tags".equals(action)) {
            preview.setTitle("移除照片标签");
            preview.setSummary("将从 " + fileIds.size() + " 张照片移除标签「" + tagName + "」。");
        } else {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的标签操作");
        }

        addSkippedFileWarning(preview, request.getFileIds(), resolvedFileIds);
        int sharedCount = Math.max(0, resolvedFileIds.size() - exclusivelyOwnedFileIds.size());
        if (sharedCount > 0) {
            preview.getWarnings().add(
                    "已忽略 " + sharedCount + " 张由多个用户共享的照片，避免修改其他用户看到的标签。"
            );
        }
        int unchangedCount = Math.max(0, exclusivelyOwnedFileIds.size() - fileIds.size());
        if (unchangedCount > 0) {
            preview.getWarnings().add(
                    "已忽略 " + unchangedCount + " 张标签状态无需变化的照片。"
            );
        }
        preview.setAffectedFiles(toFileReferences(preview.getFileIds(), userId));
        finishPreview(preview, request.getSearchType(), request.getSearchKeyword());
        return preview;
    }

    private String normalizeAction(String action, Set<String> allowedActions) {
        String value = trimToNull(action);
        if (value == null || !allowedActions.contains(value)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的智能体操作");
        }
        return value;
    }

    private String normalizeAlbumName(String albumName) {
        String value = trimToNull(albumName);
        if (value == null || value.length() > MAX_ALBUM_NAME_LENGTH) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "相册名称不能为空且不能超过30个字符");
        }
        return value;
    }

    private String normalizeTagName(String tagName) {
        String value = trimToNull(tagName);
        if (value == null || value.length() > MAX_TAG_NAME_LENGTH) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "标签名称不能为空且不能超过30个字符");
        }
        return value;
    }

    private String normalizeImageTypeForTag(String imageType) {
        String value = trimToNull(imageType);
        return value == null || "other".equalsIgnoreCase(value) ? "其他" : value;
    }

    private AgentPendingActionPayload albumPendingPayload(AgentActionPreviewVO preview) {
        AgentPendingActionPayload payload = new AgentPendingActionPayload();
        payload.setFamily("album");
        payload.setAction(preview.getAction());
        payload.setAlbumId(preview.getAlbumId());
        payload.setAlbumName(preview.getAlbumName());
        payload.setFileIds(new ArrayList<>(preview.getFileIds()));
        return payload;
    }

    private AgentPendingActionPayload tagPendingPayload(
            AgentActionPreviewVO preview,
            String imageType
    ) {
        AgentPendingActionPayload payload = new AgentPendingActionPayload();
        payload.setFamily("tag");
        payload.setAction(preview.getAction());
        payload.setTagName(preview.getTagName());
        payload.setImageType(imageType);
        payload.setFileIds(new ArrayList<>(preview.getFileIds()));
        return payload;
    }

    private void validateExecuteRequest(AgentExecuteActionRequest request) {
        if (request == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求不能为空");
        }
        if (!Boolean.TRUE.equals(request.getConfirmed())) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "写操作必须由用户明确确认");
        }
        if (!request.getUnexpectedFields().isEmpty()) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "执行请求不能包含相册、标签、照片或筛选参数，请重新预览"
            );
        }
        validatePendingActionId(request.getPendingActionId());
        validateOpaqueSecret(request.getConfirmationToken(), "confirmationToken");
        validateOpaqueSecret(request.getIdempotencyKey(), "idempotencyKey");
    }

    private void validateStatusRequest(AgentPendingActionAccessRequest request) {
        if (request == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求不能为空");
        }
        if (!request.getUnexpectedFields().isEmpty() || request.getConfirmationToken() != null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "状态查询只允许pendingActionId");
        }
        validatePendingActionId(request.getPendingActionId());
    }

    private void validateCancelRequest(AgentPendingActionAccessRequest request) {
        if (request == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求不能为空");
        }
        if (!request.getUnexpectedFields().isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "取消请求包含未声明字段");
        }
        validatePendingActionId(request.getPendingActionId());
        validateOpaqueSecret(request.getConfirmationToken(), "confirmationToken");
    }

    private void validateAlbumPreviewRequest(AgentAlbumActionRequest request) {
        validatePreviewFields(
                request.getUnexpectedFields(),
                request.getFileIds(),
                request.getSearchType(),
                request.getImageTypeText(),
                request.getLocationLevel(),
                request.getSourceAlbumId()
        );
        validateOptionalAlbumId(request.getAlbumId(), "albumId");
        validateOptionalLength(request.getAlbumName(), MAX_ALBUM_NAME_LENGTH, "albumName");
        validateOptionalLength(request.getTagName(), MAX_TAG_NAME_LENGTH, "tagName");
        validateOptionalLength(request.getSearchKeyword(), 200, "searchKeyword");
        validateSelectionLimit(request.getSelectionLimit());
        validateOptionalLength(request.getLocationValue(), 200, "locationValue");
    }

    private void validateTagPreviewRequest(AgentTagActionRequest request) {
        validatePreviewFields(
                request.getUnexpectedFields(),
                request.getFileIds(),
                request.getSearchType(),
                request.getImageTypeText(),
                request.getLocationLevel(),
                request.getSourceAlbumId()
        );
        validateOptionalLength(request.getImageType(), 30, "imageType");
        validateOptionalLength(request.getTagName(), MAX_TAG_NAME_LENGTH, "tagName");
        validateOptionalLength(request.getSourceTagName(), MAX_TAG_NAME_LENGTH, "sourceTagName");
        validateOptionalLength(request.getSearchKeyword(), 200, "searchKeyword");
        validateSelectionLimit(request.getSelectionLimit());
        validateOptionalLength(request.getLocationValue(), 200, "locationValue");
    }

    private void validatePreviewFields(
            Map<String, Object> unexpectedFields,
            List<String> fileIds,
            String searchType,
            String imageTypeText,
            String locationLevel,
            Long sourceAlbumId
    ) {
        if (!unexpectedFields.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "预览请求包含未声明字段");
        }
        validateFileIds(fileIds);
        validateOptionalEnum(searchType, WRITE_SEARCH_TYPES, "searchType");
        validateOptionalEnum(imageTypeText, IMAGE_TYPES, "imageTypeText");
        validateOptionalEnum(locationLevel, LOCATION_LEVELS, "locationLevel");
        validateOptionalAlbumId(sourceAlbumId, "sourceAlbumId");
    }

    private void validateFileIds(List<String> fileIds) {
        if (fileIds == null) {
            return;
        }
        if (fileIds.size() > MAX_WRITE_FILE_COUNT
                || new LinkedHashSet<>(fileIds).size() != fileIds.size()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "fileIds必须唯一且最多100项");
        }
        for (String fileId : fileIds) {
            if (fileId == null || fileId.isBlank() || fileId.length() > 36) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "fileId长度必须为1到36");
            }
        }
    }

    private void validateOptionalEnum(String value, Set<String> allowed, String fieldName) {
        String normalized = trimToNull(value);
        if (normalized != null && !allowed.contains(normalized)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, fieldName + "取值无效");
        }
    }

    private void validateOptionalAlbumId(Long value, String fieldName) {
        if (value != null && value != -1L && value <= 0) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, fieldName + "必须为正整数或-1");
        }
    }

    private void validateOptionalLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, fieldName + "长度超出限制");
        }
    }

    private void validateSelectionLimit(Integer value) {
        if (value != null && (value < 1 || value > MAX_WRITE_FILE_COUNT)) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "selectionLimit必须在1到" + MAX_WRITE_FILE_COUNT + "之间"
            );
        }
    }

    private void validatePendingActionId(String pendingActionId) {
        if (pendingActionId == null || !UUID_PATTERN.matcher(pendingActionId).matches()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "pendingActionId必须是标准UUID");
        }
    }

    private void validateOpaqueSecret(String value, String fieldName) {
        if (value == null || !OPAQUE_SECRET_PATTERN.matcher(value).matches()) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    fieldName + "必须是32到128位不透明凭证"
            );
        }
    }

    private void markExecutionFailed(
            String pendingActionId,
            Long userId,
            RuntimeException exception
    ) {
        log.error(
                "Agent action execution failed: pendingActionId={}, userId={}",
                pendingActionId,
                userId,
                exception
        );
        try {
            pendingActionService.markFailed(
                    pendingActionId,
                    userId,
                    "执行未完成，请重新预览"
            );
        } catch (RuntimeException statusException) {
            log.error(
                    "Unable to persist failed agent action state: pendingActionId={}",
                    pendingActionId,
                    statusException
            );
        }
    }

    private List<String> resolveAlbumActionFileIds(AgentAlbumActionRequest request, Long userId) {
        List<String> explicitFileIds = normalizeOwnedFileIds(request.getFileIds(), userId);
        if (!explicitFileIds.isEmpty()) {
            return explicitFileIds;
        }
        return resolveSelectorFileIds(
                request.getSearchType(),
                request.getSearchKeyword(),
                request.getTagName(),
                request.getImageTypeText(),
                request.getLocationLevel(),
                request.getLocationValue(),
                request.getSourceAlbumId(),
                request.getSelectionLimit(),
                userId
        );
    }

    private List<String> resolveTagActionFileIds(AgentTagActionRequest request, Long userId) {
        List<String> explicitFileIds = normalizeOwnedFileIds(request.getFileIds(), userId);
        if (!explicitFileIds.isEmpty()) {
            return explicitFileIds;
        }
        String sourceTagName = request.getSourceTagName();
        if ("remove_tags".equals(trimToNull(request.getAction()))) {
            sourceTagName = firstText(sourceTagName, request.getTagName());
        }
        return resolveSelectorFileIds(
                request.getSearchType(),
                request.getSearchKeyword(),
                sourceTagName,
                request.getImageTypeText(),
                request.getLocationLevel(),
                request.getLocationValue(),
                request.getSourceAlbumId(),
                request.getSelectionLimit(),
                userId
        );
    }

    private List<String> resolveSelectorFileIds(
            String searchType,
            String searchKeyword,
            String sourceTagName,
            String imageTypeText,
            String locationLevel,
            String locationValue,
            Long sourceAlbumId,
            Integer selectionLimit,
            Long userId
    ) {
        if (sourceAlbumId != null && sourceAlbumId != -1L) {
            return limitFileIds(fileMapper.selectFileIdByAlbumId(sourceAlbumId, userId));
        }

        String imageType = normalizeImageType(imageTypeText);
        String tagName = trimToNull(sourceTagName);
        if (tagName != null) {
            return resolveTagSelectorFileIds(tagName, imageType, userId);
        }

        String type = trimToNull(searchType);
        String keyword = trimToNull(searchKeyword);
        if (type == null && keyword != null) {
            type = "tag";
        }
        if (type == null) {
            return new ArrayList<>();
        }

        return switch (type) {
            case "tag" -> keyword == null
                    ? new ArrayList<>()
                    : resolveTagSelectorFileIds(keyword, imageType, userId);
            case "location" -> {
                String value = firstText(locationValue, keyword);
                String level = normalizeLocationLevel(locationLevel);
                if (level == null) {
                    level = "city";
                }
                yield value == null
                        ? new ArrayList<>()
                        : fileIdsFromPage(fileService.getFileInfoList(
                                DEFAULT_CURRENT,
                                MAX_WRITE_FILE_COUNT,
                                "desc",
                                "date_time_original",
                                imageType,
                                level,
                                value,
                                "all",
                                userId,
                                null,
                                false
                        ));
            }
            case "model" -> keyword == null
                    ? new ArrayList<>()
                    : fileIdsFromPage(albumService.getModelFileInfo(
                            DEFAULT_CURRENT,
                            MAX_WRITE_FILE_COUNT,
                            userId,
                            "desc",
                            "date_time_original",
                            imageType,
                            null,
                            keyword
                    ));
            case "latest" -> latestOwnedFileIds(
                    imageType,
                    selectionLimit == null ? 1 : selectionLimit,
                    userId
            );
            case "all" -> fileIdsFromPage(fileService.getFileInfoList(
                    DEFAULT_CURRENT,
                    MAX_WRITE_FILE_COUNT,
                    "desc",
                    "date_time_original",
                    imageType,
                    null,
                    null,
                    "all",
                    userId,
                    null,
                    false
            ));
            default -> new ArrayList<>();
        };
    }

    private List<String> latestOwnedFileIds(String imageType, int selectionLimit, Long userId) {
        Page<FileInfoListVO> page = fileService.getFileInfoList(
                DEFAULT_CURRENT,
                selectionLimit,
                "desc",
                "upload_time",
                imageType,
                null,
                null,
                "all",
                userId,
                null,
                false
        );
        return fileIdsFromPage(page);
    }

    private List<String> resolveTagSelectorFileIds(String selector, String imageType, Long userId) {
        List<String> tagNames = splitTagSelector(selector);
        if (tagNames.isEmpty()) {
            return new ArrayList<>();
        }

        LinkedHashSet<String> expandedTagNames = new LinkedHashSet<>();
        for (String tagName : tagNames) {
            expandedTagNames.add(tagName);
            String imageTypeCategory = normalizeSemanticImageType(tagName);
            if (imageTypeCategory != null) {
                expandedTagNames.addAll(pictureTagMapper.selectTagNamesByImageType(userId, imageTypeCategory));
            }
        }

        LinkedHashSet<String> fileIds = new LinkedHashSet<>();
        for (String tagName : expandedTagNames) {
            List<String> matchedFileIds = fileIdsFromPage(fileService.getTagFileInfo(
                    DEFAULT_CURRENT,
                    MAX_WRITE_FILE_COUNT,
                    userId,
                    "desc",
                    "date_time_original",
                    imageType,
                    tagName
            ));
            for (String fileId : matchedFileIds) {
                fileIds.add(fileId);
                if (fileIds.size() >= MAX_WRITE_FILE_COUNT) {
                    return new ArrayList<>(fileIds);
                }
            }
        }
        return new ArrayList<>(fileIds);
    }

    private String normalizeSemanticImageType(String selector) {
        String value = trimToNull(selector);
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase()) {
            case "动物", "宠物", "animal", "animals", "pet", "pets" -> "动物";
            case "植物", "花草", "plant", "plants" -> "植物";
            case "食物", "美食", "food" -> "食物";
            case "风景", "景色", "landscape", "scenery" -> "风景";
            default -> null;
        };
    }

    private List<String> splitTagSelector(String selector) {
        String value = trimToNull(selector);
        if (value == null) {
            return new ArrayList<>();
        }

        LinkedHashSet<String> tagNames = new LinkedHashSet<>();
        for (String part : value.split("[|,，、;；\\r\\n]+")) {
            String tagName = trimToNull(part);
            if (tagName == null) {
                continue;
            }
            tagName = tagName.replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "").trim();
            if (!tagName.isEmpty()) {
                tagNames.add(tagName);
            }
            if (tagNames.size() >= MAX_TAG_SELECTOR_COUNT) {
                break;
            }
        }
        return new ArrayList<>(tagNames);
    }

    private List<String> fileIdsFromPage(Page<FileInfoListVO> page) {
        if (page == null || page.getRecords() == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> fileIds = new LinkedHashSet<>();
        for (FileInfoListVO group : page.getRecords()) {
            if (group == null || group.getFileList() == null) {
                continue;
            }
            for (FileInfo file : group.getFileList()) {
                if (file == null) {
                    continue;
                }
                String fileId = trimToNull(file.getFileId());
                if (fileId != null) {
                    fileIds.add(fileId);
                }
                if (fileIds.size() >= MAX_WRITE_FILE_COUNT) {
                    return new ArrayList<>(fileIds);
                }
            }
        }
        return new ArrayList<>(fileIds);
    }

    private List<String> limitFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> limited = new LinkedHashSet<>();
        for (String fileId : fileIds) {
            String value = trimToNull(fileId);
            if (value != null) {
                limited.add(value);
            }
            if (limited.size() >= MAX_WRITE_FILE_COUNT) {
                break;
            }
        }
        return new ArrayList<>(limited);
    }

    private List<String> filterAlbumFileIds(
            List<String> candidateFileIds,
            Long albumId,
            Long userId,
            boolean keepExisting
    ) {
        Set<String> existingFileIds = new HashSet<>(fileMapper.selectFileIdByAlbumId(albumId, userId));
        List<String> result = new ArrayList<>();
        for (String fileId : candidateFileIds) {
            if (existingFileIds.contains(fileId) == keepExisting) {
                result.add(fileId);
            }
        }
        return result;
    }

    private List<String> filterTagDeltaFileIds(
            List<String> candidateFileIds,
            String tagName,
            Long userId,
            boolean keepExisting
    ) {
        if (candidateFileIds == null || candidateFileIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> existingTagFileIds = new HashSet<>();
        List<PictureTagMappingDTO> mappings = pictureTagMapper.selectTagsByFileIds(
                candidateFileIds,
                userId
        );
        for (PictureTagMappingDTO mapping : mappings) {
            if (mapping != null
                    && tagName.equals(mapping.getTagName())
                    && mapping.getFileId() != null) {
                existingTagFileIds.add(mapping.getFileId());
            }
        }
        List<String> result = new ArrayList<>();
        for (String fileId : candidateFileIds) {
            if (existingTagFileIds.contains(fileId) == keepExisting) {
                result.add(fileId);
            }
        }
        return result;
    }

    private List<String> retainInOriginalOrder(
            List<String> original,
            List<String> retained
    ) {
        if (original == null || original.isEmpty() || retained == null || retained.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> retainedSet = new HashSet<>(retained);
        List<String> result = new ArrayList<>();
        for (String fileId : original) {
            if (retainedSet.contains(fileId)) {
                result.add(fileId);
            }
        }
        return result;
    }

    private void addNoChangeWarning(
            AgentActionPreviewVO preview,
            int unchangedCount,
            String action
    ) {
        if (unchangedCount <= 0) {
            return;
        }
        if ("remove_files_from_album".equals(action)) {
            preview.getWarnings().add(
                    "已忽略 " + unchangedCount + " 张原本不在目标相册中的照片。"
            );
        } else {
            preview.getWarnings().add(
                    "已忽略 " + unchangedCount + " 张已经在目标相册中的照片。"
            );
        }
    }

    private void setPreviewFileIds(AgentActionPreviewVO preview, List<String> fileIds) {
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());
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

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private List<String> normalizeOwnedFileIds(List<String> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> candidateIds = new LinkedHashSet<>();
        for (String fileId : fileIds) {
            String value = trimToNull(fileId);
            if (value == null) {
                continue;
            }
            if (candidateIds.size() >= MAX_WRITE_FILE_COUNT) {
                break;
            }
            candidateIds.add(value);
        }
        if (candidateIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<FileEntity> ownedFiles = fileMapper.selectFileByIds(new ArrayList<>(candidateIds), userId);
        Set<String> ownedFileIds = new HashSet<>();
        for (FileEntity ownedFile : ownedFiles) {
            ownedFileIds.add(ownedFile.getFileId());
        }
        List<String> result = new ArrayList<>();
        for (String candidateId : candidateIds) {
            if (ownedFileIds.contains(candidateId)) {
                result.add(candidateId);
            }
        }
        return result;
    }

    private void finishPreview(AgentActionPreviewVO preview, String searchType, String searchKeyword) {
        if ("create_album".equals(preview.getAction())) {
            boolean willCreate = preview.getCreatedAlbumCount() != null
                    && preview.getCreatedAlbumCount() > 0;
            preview.setRequiresConfirmation(willCreate);
            preview.setConfirmationPrompt(
                    willCreate ? preview.getSummary() + "确认后我就开始处理。" : null
            );
            return;
        }
        if (preview.getAffectedFileCount() == null || preview.getAffectedFileCount() == 0) {
            preview.setRequiresConfirmation(false);
            if ("add_files_to_album".equals(preview.getAction())
                    || "create_album_and_add_files".equals(preview.getAction())) {
                preview.setSummary("没有需要新增到目标相册的照片。");
                preview.getWarnings().add("匹配到的照片可能已经在这个相册里，或者当前筛选条件没有找到照片。");
            } else if ("remove_files_from_album".equals(preview.getAction())) {
                preview.setSummary("目标相册中没有符合条件、可以移出的照片。");
            } else {
                preview.setSummary("暂时没有找到符合条件、且可以操作的照片。");
            }
            String selector = trimToNull(searchKeyword);
            if ("tag".equals(trimToNull(searchType)) && selector != null) {
                preview.getWarnings().add("本次按标签「" + selector.replace("|", "、") + "」查找，但没有匹配到照片。");
                preview.getWarnings().add("可以换成相册中已经存在的标签，或告诉我一个更具体的对象，例如“小猫”“仓鼠”。");
            } else if ("latest".equals(trimToNull(searchType))) {
                preview.getWarnings().add("没有找到当前用户最近上传且未删除的照片。");
            } else {
                preview.getWarnings().add("请换一个更具体的筛选条件后再试。");
            }
            preview.setConfirmationPrompt(null);
            return;
        }
        preview.setRequiresConfirmation(true);
        preview.setConfirmationPrompt(preview.getSummary() + "确认后我就开始处理。");
    }

    private AlbumVO requireAlbum(Long albumId, Long userId) {
        if (albumId == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "albumId不能为空");
        }
        AlbumVO album = albumService.selectAlbumById(albumId, userId);
        if (album == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "相册不存在或无权限");
        }
        return album;
    }

    private AlbumVO requireAlbum(Long albumId, String albumName, Long userId) {
        if (albumId != null && albumId != -1L) {
            return requireAlbum(albumId, userId);
        }
        String normalizedAlbumName = trimToNull(albumName);
        if (normalizedAlbumName == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "albumId or albumName is required");
        }
        Album album = findAlbumByName(normalizedAlbumName, userId);
        if (album == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Album not found: " + normalizedAlbumName);
        }
        return requireAlbum(album.getAlbumId(), userId);
    }

    private Album findAlbumByName(String albumName, Long userId) {
        String normalizedAlbumName = trimToNull(albumName);
        if (normalizedAlbumName == null) {
            return null;
        }
        return albumService.getBaseMapper().selectOne(new QueryWrapper<Album>()
                .eq("user_id", userId)
                .eq("album_name", normalizedAlbumName)
                .eq("type", "normal")
                .orderByAsc("album_id")
                .last("limit 1"));
    }

    private void addSkippedFileWarning(AgentActionPreviewVO preview, List<String> requestedFileIds, List<String> ownedFileIds) {
        int requestedCount = requestedFileIds == null ? 0 : new LinkedHashSet<>(requestedFileIds).size();
        int skippedCount = Math.max(0, requestedCount - ownedFileIds.size());
        if (skippedCount > 0) {
            preview.getWarnings().add("已忽略 " + skippedCount + " 个无效、重复或无权限的 fileId。");
        }
        if (requestedCount > MAX_WRITE_FILE_COUNT) {
            preview.getWarnings().add("单次最多处理 " + MAX_WRITE_FILE_COUNT + " 张照片，超出部分已忽略。");
        }
    }

    private int normalizeCurrent(Integer current) {
        return current == null || current < 1 ? DEFAULT_CURRENT : current;
    }

    private int normalizeSize(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private String normalizeOrderType(String orderType) {
        String value = trimToNull(orderType);
        return "asc".equals(value) ? "asc" : "desc";
    }

    private String normalizeFileOrderKeyword(String orderKeyword) {
        String value = trimToNull(orderKeyword);
        return "upload_time".equals(value) ? "upload_time" : "date_time_original";
    }

    private String normalizeAlbumOrderKeyword(String orderKeyword) {
        String value = trimToNull(orderKeyword);
        return "update_time".equals(value) ? "update_time" : "create_time";
    }

    private String normalizeImageType(String imageTypeText) {
        String value = trimToNull(imageTypeText);
        return IMAGE_TYPES.contains(value) ? value : "all";
    }

    private String normalizeLocationLevel(String locationLevel) {
        String value = trimToNull(locationLevel);
        return LOCATION_LEVELS.contains(value) ? value : null;
    }

    private String defaultLocationLevel(String locationLevel) {
        String value = trimToNull(locationLevel);
        return LOCATION_LEVELS.contains(value) ? value : "city";
    }

    private String normalizeTagFilter(String tagFilter) {
        String value = trimToNull(tagFilter);
        return TAG_FILTERS.contains(value) ? value : "all";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || isBlankPlaceholder(trimmed)) {
            return null;
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return trimToNull(value) != null;
    }

    private boolean isBlankPlaceholder(String value) {
        String normalized = value.toLowerCase();
        return "none".equals(normalized)
                || "null".equals(normalized)
                || "undefined".equals(normalized)
                || "n/a".equals(normalized)
                || "na".equals(normalized)
                || "无".equals(value)
                || "空".equals(value);
    }

    private Long currentUserId() {
        return com.memory.xzp.config.AgentAccessGuard.currentUserId();
    }
}
