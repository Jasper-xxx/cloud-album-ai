package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.config.UploadPolicy;
import com.memory.xzp.config.ratelimit.RateLimit;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.model.dto.imageSearch.ImageSearchRequestDTO;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.vo.entity.ImageSearchResultVO;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.service.AsyncTaskService;
import com.memory.xzp.service.FileFeatureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 以图搜图 Controller
 *
 * <p>提供图片相似度搜索接口，用户上传查询图片，系统在其个人相册中搜索相似图片并返回</p>
 * <p>所有接口均需要登录（Sa-Token 鉴权），严格实现用户数据隔离</p>
 *
 * <p>接口列表：</p>
 * <ul>
 *   <li>POST /imageSearch/search   - 以图搜图（核心接口）</li>
 *   <li>POST /imageSearch/buildFeature - 手动触发特征提取（调试/补充建库用）</li>
 * </ul>
 *
 * @author xzp
 * @date 2026/03/20
 */
@RestController
@RequestMapping("/imageSearch")
@Tag(name = "以图搜图接口", description = "基于阿里云多模态向量的图片相似度搜索")
public class ImageSearchController {

    private static final Logger log = LoggerFactory.getLogger(ImageSearchController.class);

    @Resource
    private FileFeatureService fileFeatureService;
    @Resource
    private AsyncTaskService asyncTaskService;
    @Resource
    private FileMapper fileMapper;
    @Resource
    private UploadPolicy uploadPolicy;

    /**
     * 以图搜图（核心接口）
     *
     * <p>流程：上传查询图片 → FastAPI 提取特征 → 计算余弦相似度 → 按搜索模式返回相似图片</p>
     * <p>只在当前登录用户的图片库中搜索，严格数据隔离</p>
     *
     * <p>Postman 测试方式：</p>
     * <pre>
     * POST http://localhost:8088/imageSearch/search
     * Headers: Authorization: &lt;token&gt;
     * Body: form-data
     *   Key: image  Type: File  Value: 选择本地图片
     * </pre>
     *
     * @param image 查询图片（multipart/form-data，字段名 image）
     * @return 相似图片列表（模糊/精确匹配按相似度阈值返回全部命中，局部匹配最多 20 条；若无结果返回空列表）
     */
    @PostMapping("/search")
    @RateLimit(permitsPerSecond = 0.2)
    @Operation(summary = "以图搜图", description = "上传图片，在当前用户相册中搜索相似图片；模糊匹配返回相似度 ≥ 60% 的全部结果，精确匹配返回相似度 ≥ 85% 的全部结果")
    public BaseResponse<?> search(@RequestParam("image") MultipartFile image,
                                  @RequestParam(value = "mode", required = false) String mode,
                                  @RequestParam(value = "albumIds", required = false) List<Long> albumIds,
                                  @RequestParam(value = "tagNames", required = false) List<String> tagNames,
                                  @RequestParam(value = "sizeRange", required = false) String sizeRange) {
        // ── 登录校验（Sa-Token） ───────────────────────────────────────────────
        StpUtil.checkLogin();
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());

        log.info("以图搜图请求: userId={}, fileName={}, size={} bytes",
                userId,
                image != null ? image.getOriginalFilename() : "null",
                image != null ? image.getSize() : 0);

        // ── 基础参数校验 ───────────────────────────────────────────────────────
        if (image == null || image.isEmpty()) {
            return ResultUtil.error(StatusCode.PARAMS_ERROR, "查询图片不能为空");
        }

        // ── 调用 Service 执行搜索 ──────────────────────────────────────────────
        UploadPolicy.ValidatedUpload validated = uploadPolicy.validate(
                image.getOriginalFilename(),
                image.getContentType(),
                image.getSize()
        );
        if (!"image".equals(validated.category())) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "查询文件必须是图片");
        }

        ImageSearchRequestDTO request = new ImageSearchRequestDTO();
        request.setMode(mode);
        request.setAlbumIds(albumIds);
        request.setTagNames(tagNames);
        request.setSizeRange(sizeRange);

        List<ImageSearchResultVO> results = fileFeatureService.searchSimilarImages(image, userId, request);

        if (results.isEmpty()) {
            log.info("以图搜图未找到相似图片: userId={}", userId);
            return ResultUtil.success(results, "暂未找到相似图片，建议先确认图片库已完成特征建库");
        }

        log.info("以图搜图完成: userId={}, 命中数={}", userId, results.size());
        return ResultUtil.success(results, "搜索成功，共找到 " + results.size() + " 张相似图片");
    }

    /**
     * 手动触发特征提取（调试/补充建库）
     *
     * <p>当图片已存在于 file 表但 file_feature 表中无特征记录时（如历史图片），
     * 可通过此接口手动触发特征提取，将其加入以图搜图索引库</p>
     *
     * <p>Postman 测试方式：</p>
     * <pre>
     * POST http://localhost:8088/imageSearch/buildFeature
     * Headers: Authorization: &lt;token&gt;, Content-Type: application/x-www-form-urlencoded
     * Body: fileId=xxx
     * </pre>
     *
     * @param fileId         文件ID（UUID，file.file_id）
     * @return 操作结果
     */
    @PostMapping("/buildFeature")
    @RateLimit(permitsPerSecond = 0.1)
    @Operation(summary = "手动触发特征提取", description = "为指定文件提交可靠的特征提取任务")
    public BaseResponse<?> buildFeature(@RequestParam("fileId") String fileId) {
        StpUtil.checkLogin();
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());

        if (fileId == null || fileId.isBlank()) {
            return ResultUtil.error(StatusCode.PARAMS_ERROR, "fileId 不能为空");
        }
        List<FileEntity> files = fileMapper.selectFileByIds(List.of(fileId), userId);
        if (files.size() != 1 || !"image".equals(files.get(0).getCategory())) {
            throw new BusinessException(StatusCode.NO_AUTH_ERROR, "文件不存在或无权访问");
        }
        String trustedObjectName = files.get(0).getFileObjectName();
        log.info("手动特征提取: userId={}, fileId={}, objectName={}", userId, fileId, trustedObjectName);

        try {
            Long taskId = asyncTaskService.enqueueImageFeature(fileId, userId, trustedObjectName);
            return ResultUtil.success(Map.of("taskId", taskId), "特征提取任务已提交");
        } catch (BusinessException e) {
            log.error("手动特征任务提交失败: fileId={}, error={}", fileId, e.getMessage());
            return ResultUtil.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("手动特征任务提交异常: fileId={}", fileId, e);
            return ResultUtil.error(StatusCode.SYSTEM_ERROR, "特征任务提交失败");
        }
    }
}
