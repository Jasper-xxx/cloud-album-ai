package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PictureTagMapper;
import com.memory.xzp.model.dto.agent.AgentAlbumActionRequest;
import com.memory.xzp.model.dto.agent.AgentAlbumQueryRequest;
import com.memory.xzp.model.dto.agent.AgentPersonQueryRequest;
import com.memory.xzp.model.dto.agent.AgentSearchFilesRequest;
import com.memory.xzp.model.dto.agent.AgentTagActionRequest;
import com.memory.xzp.model.entity.Album;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.model.vo.agent.AgentActionPreviewVO;
import com.memory.xzp.model.vo.agent.AgentActionResultVO;
import com.memory.xzp.model.vo.agent.AgentCapabilitiesVO;
import com.memory.xzp.model.vo.album.AlbumVO;
import com.memory.xzp.model.vo.album.LocationAlbumVO;
import com.memory.xzp.model.vo.album.ModelAlbumVO;
import com.memory.xzp.model.vo.album.PersonAlbumVO;
import com.memory.xzp.model.vo.entity.FileInfo;
import com.memory.xzp.model.vo.visual.FileTagVO;
import com.memory.xzp.service.AlbumService;
import com.memory.xzp.service.FileService;
import com.memory.xzp.service.PersonService;
import com.memory.xzp.service.RecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dify 智能体调用的后端门面。
 *
 * <p>第一阶段只暴露只读工具，写操作仍由能力清单声明为“需要确认后接入”。</p>
 */
@RestController
@RequestMapping("/agent")
@Tag(name = "智能体接口", description = "Dify 智能体聚合接口")
public class AgentController {

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
    private RecordService recordService;

    @Value("${agent.auth-enabled:true}")
    private boolean agentAuthEnabled;

    @Value("${agent.dev-user-id:1}")
    private Long agentDevUserId;

    @GetMapping("/capabilities")
    @Operation(summary = "查询智能体能力边界", description = "返回当前开放、需确认和禁用的智能体工具")
    public BaseResponse<AgentCapabilitiesVO> capabilities() {
        AgentCapabilitiesVO capabilities = new AgentCapabilitiesVO();
        capabilities.setName("云忆相册助手");
        capabilities.setMode("confirmed_write_phase_two");
        capabilities.setReadOnlyTools(List.of(
                "search_files",
                "list_albums",
                "list_location_albums",
                "list_model_albums",
                "list_tags",
                "list_people"
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
                "update_location",
                "build_feature"
        ));
        capabilities.setDisabledTools(List.of(
                "delete_files",
                "delete_albums",
                "empty_recycle_bin",
                "create_share_link",
                "create_download_token",
                "update_user_account"
        ));
        capabilities.setRiskRules(List.of(
                "默认只读，写操作必须先展示执行预览并等待用户明确确认。",
                "所有查询都基于当前登录用户，不能跨用户访问数据。",
                "不返回 API Key、数据库密码、MinIO 密钥、JWT 密钥、原始 feature_vector。",
                "不对人物照片做真实身份、年龄或敏感属性断言。"
        ));
        return ResultUtil.success(capabilities, "获取智能体能力成功");
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
    @Operation(summary = "预览智能体相册写操作", description = "生成相册写操作预览，不写入数据库")
    public BaseResponse<AgentActionPreviewVO> previewAlbumAction(@RequestBody AgentAlbumActionRequest request) {
        AgentActionPreviewVO preview = buildAlbumActionPreview(request, currentUserId());
        return ResultUtil.success(preview, "生成相册操作预览成功");
    }

    @PostMapping("/executeAlbumAction")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "执行智能体相册写操作", description = "用户确认后执行相册创建或照片移入移出")
    public BaseResponse<AgentActionResultVO> executeAlbumAction(
            HttpServletRequest servletRequest,
            @RequestBody AgentAlbumActionRequest request
    ) {
        requireConfirmed(request == null ? null : request.getConfirmed());
        Long userId = currentUserId();
        AgentActionPreviewVO preview = buildAlbumActionPreview(request, userId);
        if (!"create_album".equals(preview.getAction())) {
            requireFiles(preview.getFileIds());
        }
        AgentActionResultVO result = new AgentActionResultVO();
        result.setAction(preview.getAction());
        result.setAffectedFileCount(preview.getAffectedFileCount());
        result.setAlbumId(preview.getAlbumId());
        result.setAlbumName(preview.getAlbumName());

        switch (preview.getAction()) {
            case "create_album" -> {
                Album album = findOrCreateAgentAlbum(preview.getAlbumName(), userId);
                result.setAlbumId(album.getAlbumId());
                result.setAlbumName(album.getAlbumName());
                recordService.createRecordLog("智能体:创建相册 " + album.getAlbumName(), 1, userId, servletRequest);
                result.setMessage("相册创建成功");
            }
            case "create_album_and_add_files" -> {
                Album album = findOrCreateAgentAlbum(preview.getAlbumName(), userId);
                albumService.addPictureToAlbum(preview.getFileIds(), album.getAlbumId(), userId);
                result.setAlbumId(album.getAlbumId());
                result.setAlbumName(album.getAlbumName());
                recordService.createRecordLog("智能体:创建相册并加入照片 " + album.getAlbumName(), preview.getAffectedFileCount(), userId, servletRequest);
                result.setMessage("相册创建并添加照片成功");
            }
            case "add_files_to_album" -> {
                albumService.addPictureToAlbum(preview.getFileIds(), preview.getAlbumId(), userId);
                recordService.createRecordLog("智能体:添加照片到相册 " + preview.getAlbumName(), preview.getAffectedFileCount(), userId, servletRequest);
                result.setMessage("照片已添加到相册");
            }
            case "remove_files_from_album" -> {
                albumService.removePictureFromAlbum(preview.getFileIds(), preview.getAlbumId(), userId);
                recordService.createRecordLog("智能体:从相册移出照片 " + preview.getAlbumName(), preview.getAffectedFileCount(), userId, servletRequest);
                result.setMessage("照片已从相册移出");
            }
            default -> throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的相册操作");
        }

        result.setSuccess(true);
        return ResultUtil.success(result, "相册操作执行成功");
    }

    @PostMapping("/previewTagAction")
    @Operation(summary = "预览智能体标签写操作", description = "生成标签写操作预览，不写入数据库")
    public BaseResponse<AgentActionPreviewVO> previewTagAction(@RequestBody AgentTagActionRequest request) {
        AgentActionPreviewVO preview = buildTagActionPreview(request, currentUserId());
        return ResultUtil.success(preview, "生成标签操作预览成功");
    }

    @PostMapping("/executeTagAction")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "执行智能体标签写操作", description = "用户确认后添加或移除照片标签")
    public BaseResponse<AgentActionResultVO> executeTagAction(
            HttpServletRequest servletRequest,
            @RequestBody AgentTagActionRequest request
    ) {
        requireConfirmed(request == null ? null : request.getConfirmed());
        Long userId = currentUserId();
        AgentActionPreviewVO preview = buildTagActionPreview(request, userId);
        requireFiles(preview.getFileIds());
        AgentActionResultVO result = new AgentActionResultVO();
        result.setAction(preview.getAction());
        result.setAffectedFileCount(preview.getAffectedFileCount());
        result.setTagName(preview.getTagName());

        if ("add_tags".equals(preview.getAction())) {
            fileService.addTag(preview.getFileIds(), normalizeImageTypeForTag(request.getImageType()), preview.getTagName(), userId);
            recordService.createRecordLog("智能体:添加照片标签 " + preview.getTagName(), preview.getAffectedFileCount(), userId, servletRequest);
            result.setMessage("标签添加成功");
        } else if ("remove_tags".equals(preview.getAction())) {
            fileService.removeTag(preview.getFileIds(), preview.getTagName(), userId);
            recordService.createRecordLog("智能体:移除照片标签 " + preview.getTagName(), preview.getAffectedFileCount(), userId, servletRequest);
            result.setMessage("标签移除成功");
        } else {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的标签操作");
        }

        result.setSuccess(true);
        return ResultUtil.success(result, "标签操作执行成功");
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
        String action = normalizeAction(request.getAction(), ALBUM_WRITE_ACTIONS);
        List<String> fileIds = resolveAlbumActionFileIds(request, userId);
        AgentActionPreviewVO preview = new AgentActionPreviewVO();
        preview.setAction(action);
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());

        switch (action) {
            case "create_album" -> {
                String albumName = normalizeAlbumName(request.getAlbumName());
                Album existingAlbum = findAlbumByName(albumName, userId);
                if (existingAlbum != null) {
                    preview.setAlbumId(existingAlbum.getAlbumId());
                }
                preview.setAlbumName(albumName);
                preview.setTitle("创建相册");
                preview.setSummary("将创建相册「" + albumName + "」。");
            }
            case "create_album_and_add_files" -> {
                String albumName = normalizeAlbumName(request.getAlbumName());
                Album existingAlbum = findAlbumByName(albumName, userId);
                if (existingAlbum != null) {
                    fileIds = filterAlbumFileIds(fileIds, existingAlbum.getAlbumId(), userId, false);
                    setPreviewFileIds(preview, fileIds);
                    preview.setAlbumId(existingAlbum.getAlbumId());
                    preview.setTitle("添加照片到已有相册");
                    preview.setSummary("找到已有相册「" + albumName + "」，将把 " + fileIds.size() + " 张匹配照片加入其中。");
                } else {
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

        addSkippedFileWarning(preview, request.getFileIds(), fileIds);
        finishPreview(preview, request.getSearchType(), request.getSearchKeyword());
        return preview;
    }

    private AgentActionPreviewVO buildTagActionPreview(AgentTagActionRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "请求不能为空");
        }
        String action = normalizeAction(request.getAction(), TAG_WRITE_ACTIONS);
        String tagName = normalizeTagName(request.getTagName());
        List<String> fileIds = resolveTagActionFileIds(request, userId);

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

        addSkippedFileWarning(preview, request.getFileIds(), fileIds);
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
        return value == null ? "其他" : value;
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
            case "latest" -> latestOwnedFileId(imageType, userId);
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

    private List<String> latestOwnedFileId(String imageType, Long userId) {
        Page<FileInfoListVO> page = fileService.getFileInfoList(
                DEFAULT_CURRENT,
                1,
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
        List<String> fileIds = fileIdsFromPage(page);
        return fileIds.isEmpty() ? new ArrayList<>() : List.of(fileIds.get(0));
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

    private void setPreviewFileIds(AgentActionPreviewVO preview, List<String> fileIds) {
        preview.setFileIds(fileIds);
        preview.setAffectedFileCount(fileIds.size());
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

    private void requireFiles(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "没有可操作的照片");
        }
    }

    private void finishPreview(AgentActionPreviewVO preview, String searchType, String searchKeyword) {
        if ("create_album".equals(preview.getAction())) {
            preview.setRequiresConfirmation(true);
            preview.setConfirmationPrompt(preview.getSummary() + "确认后我就开始处理。");
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

    private Album findOrCreateAgentAlbum(String albumName, Long userId) {
        Album existingAlbum = findAlbumByName(albumName, userId);
        if (existingAlbum != null) {
            return existingAlbum;
        }
        return createAgentAlbum(albumName, userId);
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
                .last("limit 1"));
    }

    private Album createAgentAlbum(String albumName, Long userId) {
        Album album = new Album();
        album.setUserId(userId);
        album.setAlbumName(albumName);
        album.setType("normal");
        albumService.getBaseMapper().insert(album);
        return album;
    }

    private void requireConfirmed(Boolean confirmed) {
        if (!Boolean.TRUE.equals(confirmed)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "写操作必须先由用户明确确认");
        }
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
        if (StpUtil.isLogin()) {
            return StpUtil.getLoginIdAsLong();
        }
        if (!agentAuthEnabled) {
            return agentDevUserId;
        }
        return StpUtil.getLoginIdAsLong();
    }
}
