package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.config.UploadPolicy;
import com.memory.xzp.config.ratelimit.RateLimit;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.metrics.BusinessMetrics;
import com.memory.xzp.model.dto.DownLoadInfoDTO;
import com.memory.xzp.model.dto.FileChunkDTO;
import com.memory.xzp.model.dto.upload.MultipartUploadCompleteRequest;
import com.memory.xzp.model.dto.upload.MultipartUploadInitRequest;
import com.memory.xzp.model.dto.upload.MultipartUploadInitResponse;
import com.memory.xzp.model.dto.upload.MultipartUploadRefreshRequest;
import com.memory.xzp.model.dto.picture.BatchGetPictureTagRequest;
import com.memory.xzp.model.entity.PictureTag;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.model.vo.SimilarFileInfoListVO;
import com.memory.xzp.model.vo.entity.FileMetaDataVO;
import com.memory.xzp.model.vo.entity.ShareFileVO;
import com.memory.xzp.model.vo.picture.BatchGetPictureTagResponseVO;
import com.memory.xzp.model.vo.task.ImageTagTaskVO;
import com.memory.xzp.service.AlbumService;
import com.memory.xzp.service.FileService;
import com.memory.xzp.service.MultipartUploadService;
import com.memory.xzp.service.RecordService;
import com.memory.xzp.service.SimilarDetectService;
import com.memory.xzp.service.StorageQuotaService;
import com.memory.xzp.service.UploadSecurityValidator;
import com.memory.xzp.utils.file.FileUtil;
import com.memory.xzp.utils.auth.RedisUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;


@RestController
@RequestMapping("/file")
@Tag(name = "文件接口", description = "文件接口")
public class FileController {

    @Resource
    private FileUtil fileUtil;
    @Resource
    private FileService fileService;
    @Resource
    private MultipartUploadService multipartUploadService;
    @Resource
    private UploadPolicy uploadPolicy;
    @Resource
    private UploadSecurityValidator uploadSecurityValidator;
    @Resource
    private StorageQuotaService storageQuotaService;
    @Resource
    private BusinessMetrics businessMetrics;

    @Resource
    private RecordService recordService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private AlbumService albumService;

    @Resource
    private SimilarDetectService similarDetectService;

    @PostMapping("/upload")
    @RateLimit(permitsPerSecond = 0.5)
    @Operation(summary = "上传文件", description = "上传文件")
    public BaseResponse<?> upload(HttpServletRequest request, MultipartFile multipartFile, String lastModified, Long albumId) {

        if (albumId == null) {
            albumId = (long) -1;
        }
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件不能为空");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(lastModified);
        } catch (NumberFormatException e) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件时间格式错误");
        }
        Instant instant = Instant.ofEpochMilli(timestamp);
        LocalDateTime lastModifiedTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());

        String originalFilename = multipartFile.getOriginalFilename();
        UploadPolicy.ValidatedUpload validatedUpload = uploadPolicy.validate(
                originalFilename,
                multipartFile.getContentType(),
                multipartFile.getSize()
        );
        uploadSecurityValidator.validateMultipartFile(multipartFile, validatedUpload);
        int lastIndex = originalFilename.lastIndexOf('.');
        if (lastIndex == -1) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件格式错误!");
        }
        String fileSuffix = originalFilename.substring(lastIndex + 1);
        //获取文件类型，要么是图片，要么是视频
        String fileType = fileUtil.getFileType(fileSuffix);

        if (fileType.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件格式错误!");
        }
        businessMetrics.recordUploadLifecycle("accepted", "legacy", fileType, "success");
        businessMetrics.recordUploadBytes("legacy", fileType, multipartFile.getSize());
        String reservationId = UUID.randomUUID().toString().replace("-", "");
        boolean res;
        try {
            storageQuotaService.reserve(userId, multipartFile.getSize(), reservationId);
            if ("image".equals(fileType)) {
                res = fileService.uploadImageFile(userId, multipartFile, fileSuffix, lastModifiedTime, albumId);
            } else {
                res = fileService.uploadVideoFile(userId, multipartFile, fileSuffix, lastModifiedTime, albumId);
            }
        } catch (RuntimeException e) {
            storageQuotaService.release(userId, reservationId);
            businessMetrics.recordUploadLifecycle("completed", "legacy", fileType, "failed");
            throw e;
        }
        storageQuotaService.confirm(userId, reservationId);
        businessMetrics.recordUploadLifecycle("completed", "legacy", fileType, res ? "success" : "failed");
        //日志记录
        recordService.createRecordLog("上传照片:" + multipartFile.getOriginalFilename(), 1, userId, request);
        return res ? ResultUtil.success("上传成功!") : ResultUtil.error(StatusCode.PARAMS_ERROR, "上传失败!");
    }


    @PostMapping("/multipart/init")
    @RateLimit(permitsPerSecond = 5.0, timeoutMillis = 3000)
    public BaseResponse<MultipartUploadInitResponse> initializeMultipartUpload(
            @RequestBody MultipartUploadInitRequest request
    ) {
        Long userId = StpUtil.getLoginIdAsLong();
        MultipartUploadInitResponse response = multipartUploadService.initialize(userId, request);
        return ResultUtil.success(response, "分片上传已初始化");
    }

    @PostMapping("/multipart/complete")
    @RateLimit(permitsPerSecond = 5.0, timeoutMillis = 3000)
    public BaseResponse<String> completeMultipartUpload(
            @RequestBody MultipartUploadCompleteRequest request
    ) {
        Long userId = StpUtil.getLoginIdAsLong();
        String fileId = multipartUploadService.complete(
                userId,
                request == null ? null : request.getSessionId()
        );
        return ResultUtil.success(fileId, "分片上传完成");
    }

    @GetMapping("/multipart/status")
    @RateLimit(permitsPerSecond = 10.0, timeoutMillis = 3000)
    public BaseResponse<MultipartUploadInitResponse> getMultipartUploadStatus(
            @RequestParam String sessionId
    ) {
        Long userId = StpUtil.getLoginIdAsLong();
        MultipartUploadInitResponse response = multipartUploadService.status(userId, sessionId);
        return ResultUtil.success(response, "Multipart upload status loaded");
    }

    @PostMapping("/multipart/refresh")
    @RateLimit(permitsPerSecond = 10.0, timeoutMillis = 3000)
    public BaseResponse<MultipartUploadInitResponse> refreshMultipartUploadUrls(
            @RequestBody MultipartUploadRefreshRequest request
    ) {
        Long userId = StpUtil.getLoginIdAsLong();
        MultipartUploadInitResponse response = multipartUploadService.refreshUploadUrls(userId, request);
        return ResultUtil.success(response, "Multipart upload URLs refreshed");
    }

    @PostMapping("/multipart/abort")
    @RateLimit(permitsPerSecond = 5.0, timeoutMillis = 3000)
    public BaseResponse<?> abortMultipartUpload(
            @RequestBody MultipartUploadCompleteRequest request
    ) {
        Long userId = StpUtil.getLoginIdAsLong();
        multipartUploadService.abort(
                userId,
                request == null ? null : request.getSessionId()
        );
        return ResultUtil.success("分片上传已取消");
    }

    @GetMapping("/upload")
    public BaseResponse<?> CheckUpload(FileChunkDTO fileChunkDTO) {

        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        Long albumId = fileChunkDTO.getAlbumId();
        String MD5 = fileChunkDTO.getIdentifier();
        if (albumId == null || MD5 == null) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误");
        }
        Boolean res = fileService.checkIsUpload(userId, fileChunkDTO.getAlbumId(), fileChunkDTO.getIdentifier());

        return res ? ResultUtil.success("exist") : ResultUtil.success("noExist");
    }

    @PostMapping("/selectAllFileInfo")
    @Operation(summary = "获取文件信息", description = "分页获取文件信息")
    public BaseResponse<Page<FileInfoListVO>> SelectFileList(String current, String size, String orderType, String orderKeyword, String imageTypeText, String locationLevel, String locationValue, Long albumId, String tagFilter) {

        // 分组时间
        if ("upload_time".equals(orderKeyword)) {
            orderKeyword = "upload_time";
        } else {
            orderKeyword = "date_time_original";
        }
        // 排序方式
        if ("desc".equals(orderType)) {
            orderType = "desc";
        } else {
            orderType = "asc";
        }
        int c;
        int s;
        try {
            c = Integer.parseInt(current);
            s = Integer.parseInt(size);
        } catch (NumberFormatException e) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "分页错误!");
        }

        //图片类型
        Set<String> imageTypeTextSet = new HashSet<>(Arrays.asList("picture", "gif", "video"));
        imageTypeText = imageTypeTextSet.contains(imageTypeText) ? imageTypeText : "all";

        //地理位置
        Set<String> locationLevelSet = new HashSet<>(Arrays.asList("country", "province", "city", "district"));

        if (!locationLevelSet.contains(locationLevel)) {
            locationLevel = null;
        } else {
            if ((!(locationLevel.equals("country")) && (locationValue.contains(",")))) {
                String[] split = locationValue.split(",");
                locationValue = split[1];
            }
        }

        Set<String> tagFilterSet = new HashSet<>(Arrays.asList("all", "untagged"));
        tagFilter = tagFilterSet.contains(tagFilter) ? tagFilter : "all";

        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        if (albumId == -1) albumId = null;
        Page<FileInfoListVO> page = fileService.getFileInfoList(c, s, orderType, orderKeyword, imageTypeText, locationLevel, locationValue, tagFilter, userId, albumId, false);
        BaseResponse<Page<FileInfoListVO>> res = new BaseResponse<>();
        res.setData(page);
        res.setCode(200);
        res.setMessage("获取文件信息成功!");
        return res;
    }

    @PostMapping("/deleteFileByIds")
    public BaseResponse<?> deleteFileByIds(HttpServletRequest request, @RequestParam List<String> fileIds) {

        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        boolean res = fileService.setIsDeleted(fileIds, true, userId);
        //日志记录HttpServletRequest request
        if (res)
            recordService.createRecordLog("删除照片", fileIds.size(), userId, request);
        return res ? ResultUtil.success("删除成功!") : ResultUtil.error(StatusCode.PARAMS_ERROR, "删除失败!");
    }

    @PostMapping("/getDownloadToken")
    public BaseResponse<?> getDownloadToken(@RequestParam List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        String downloadToken = fileService.getDownloadToken(fileIds, userId);
        return ResultUtil.success(downloadToken, "获取下载token成功");
    }


    @PostMapping("/getDownloadSharedFileToken")
    @RateLimit(permitsPerSecond = 2.0, scope = RateLimit.Scope.IP)
    public BaseResponse<?> downloadSharedFile(String shareToken, @RequestParam List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        Long sharerId = fileService.validateShareAccess(shareToken, fileIds);
        String downloadToken = fileService.getDownloadToken(fileIds, sharerId);
        return ResultUtil.success(downloadToken, "获取下载token成功");
    }


    @GetMapping("/downloadFileByToken")
    public void downloadFileByToken(HttpServletRequest request, HttpServletResponse response, String downloadToken) {

        if (downloadToken == null || downloadToken.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "下载令牌不能为空!");
        }
        DownLoadInfoDTO downLoadInfoDTO = (DownLoadInfoDTO) redisUtil.get("tempDownload:" + downloadToken);
        if (downLoadInfoDTO == null || downLoadInfoDTO.getFileIds() == null
                || downLoadInfoDTO.getFileIds().isEmpty() || downLoadInfoDTO.getUserId() == null) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "下载令牌已过期或不存在");
        }

        List<String> fileIds = downLoadInfoDTO.getFileIds();
        Long userId = downLoadInfoDTO.getUserId();
        // 2. 动态生成文件名
        String downloadZipName = "Memory_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH时mm分")) +
                "_批量下载" + fileIds.size() + "项.zip";
        if (fileIds.size() > 1) {
            fileService.downloadZipFileByIds(response, fileIds, userId, downloadZipName);
        } else {
            fileService.downloadFileByIds(response, fileIds, userId);
        }
        redisUtil.delete("tempDownload:" + downloadToken);
        //日志记录
        recordService.createRecordLog("下载照片", fileIds.size(), userId, request);
    }


    @PostMapping("/selectMetaDataByFileId")
    @Operation(summary = "获取文件媒体信息", description = "获取文件媒体信息")
    public BaseResponse<?> selectMetaDataByFileId(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "fileId不能为空");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        FileMetaDataVO fileMetaDataVO = fileService.selectFileMetaDataById(fileId, userId);
        if (fileMetaDataVO == null) {
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "文件不存在或无权访问");
        }
        return ResultUtil.success(fileMetaDataVO, "获取数据成功");
    }

    @PostMapping("/selectSharedMetaDataByFileId")
    @Operation(summary = "获取分享文件媒体信息", description = "校验分享令牌后获取文件媒体信息")
    public BaseResponse<?> selectSharedMetaDataByFileId(String fileId, String shareToken) {
        FileMetaDataVO fileMetaDataVO = fileService.selectSharedFileMetaDataById(fileId, shareToken);
        return ResultUtil.success(fileMetaDataVO, "获取数据成功");
    }

    /**
     * 手动修正图片地理位置。
     * 用户可在照片详情页手动输入城市名称，用于覆盖 EXIF 解析结果，或补充无 GPS 图片的位置信息。
     */
    @PostMapping("/updateLocation")
    @Operation(summary = "手动修正图片位置", description = "覆盖图片的地理位置信息")
    public BaseResponse<?> updateLocation(@RequestBody Map<String, String> req) {
        Long userId = StpUtil.getLoginIdAsLong();
        String fileId = req.get("fileId");
        String locationValue = req.get("locationValue");
        if (fileId == null || fileId.isBlank() || locationValue == null || locationValue.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR);
        }
        fileService.updateLocation(userId, fileId, locationValue.trim());
        return ResultUtil.success("位置已更新");
    }

    /**
     * 手动设置 GPS 坐标并重新触发逆地理编码。
     * 适用于图片/视频没有 GPS 元数据，或 GPS 解析结果有误，需要人工纠正的场景。
     *
     * @param req 请求体：{ "fileId": "xxx", "latitude": 34.12, "longitude": 108.87 }
     */
    @PostMapping("/updateGpsCoordinate")
    @Operation(summary = "手动设置GPS坐标", description = "更新坐标后自动重新解析地理位置")
    public BaseResponse<?> updateGpsCoordinate(@RequestBody Map<String, Object> req) {
        Long userId = StpUtil.getLoginIdAsLong();
        String fileId = (String) req.get("fileId");
        if (fileId == null || fileId.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "fileId 不能为空");
        }
        Double latitude;
        Double longitude;
        try {
            latitude  = Double.parseDouble(String.valueOf(req.get("latitude")));
            longitude = Double.parseDouble(String.valueOf(req.get("longitude")));
        } catch (NullPointerException | NumberFormatException e) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "latitude/longitude 必须为有效数字");
        }
        if (latitude < -90 || latitude > 90) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "纬度须在 -90 ~ 90 之间");
        }
        if (longitude < -180 || longitude > 180) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "经度须在 -180 ~ 180 之间");
        }
        fileService.updateGpsCoordinate(userId, fileId, latitude, longitude);
        return ResultUtil.success("GPS 坐标已更新，正在解析地址...");
    }


    @PostMapping("/createShareUrl")
    @RateLimit(permitsPerSecond = 1.0)
    public BaseResponse<?> createShareUrl(HttpServletRequest request, Integer shareDay, @RequestParam List<String> fileIds) {

        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        if (shareDay == null || shareDay <= 0 || shareDay > 365) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "分享天数必须在 1 到 365 天之间");
        }
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());

        String shareKey = fileService.createShareUrl(fileIds, userId, shareDay);

        //日志记录
        recordService.createRecordLog("分享照片:" + shareDay + "天", fileIds.size(), userId, request);
        // 返回分享链接（这里返回短标识，前端自行拼接完整链接）
        return ResultUtil.success(shareKey, "分享成功");
    }


    @PostMapping("/saveSharePicture")
    @RateLimit(permitsPerSecond = 1.0)
    public BaseResponse<?> saveSharePicture(
            HttpServletRequest request,
            @RequestParam List<String> fileIds,
            Long albumId,
            String shareToken
    ) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "参数错误!");
        }
        if (albumId == null || albumId == (long) (-1)) {
            albumId = (long) -1;
        }
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());

        fileService.saveSharePicture(userId, albumId, fileIds, shareToken);
        //日志记录HttpServletRequest request
        recordService.createRecordLog("保存分享照片", fileIds.size(), userId, request);
        return ResultUtil.success("保存成功");
    }


    @PostMapping("/getShareFileInfo")
    public BaseResponse<ShareFileVO> getShareFileInfo(String shareToken) {
        ShareFileVO shareInfo = fileService.getShareInfo(shareToken);
        return ResultUtil.success(shareInfo, "获取照片成功");
    }

    @PostMapping("/addSomePictureTag")
    public BaseResponse<?> addSomePictureTag(HttpServletRequest request, @RequestBody ArrayList<PictureTag> pictureTags) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        for (PictureTag pictureTag : pictureTags) {
            String fileId = pictureTag.getFileId();
            List<String> fileIds = new ArrayList<>();
            fileIds.add(fileId);
           String imageType =  pictureTag.getImageType();
            String tagName = pictureTag.getTagName();
            //日志记录HttpServletRequest request
            if(imageType == null){
                imageType = "其他";
            }
          fileService.addTag(fileIds, imageType,tagName, userId);
            recordService.createRecordLog("添加照片标签:" + imageType+"-"+tagName, 1, userId, request);
        }

        return ResultUtil.success("添加成功");
    }
    @PostMapping("/addPictureTag")
    public BaseResponse<?> addPictureTag(HttpServletRequest request, @RequestParam("fileIds") List<String> fileIds,String imageType, String tagName) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        boolean res = fileService.addTag(fileIds, imageType,tagName, userId);
        //日志记录HttpServletRequest request
        if(imageType == null){
            imageType = "其他";
        }
        if(tagName == null){
           return ResultUtil.error(StatusCode.PARAMS_ERROR, "添加失败");
        }
        recordService.createRecordLog("添加照片标签:" + imageType+"-"+tagName, 1, userId, request);
        return res ? ResultUtil.success("添加成功") : ResultUtil.error(StatusCode.PARAMS_ERROR, "添加失败");
    }

    @PostMapping("/removePictureTag")
    public BaseResponse<?> removePictureTag(HttpServletRequest request, @RequestParam("fileIds") List<String> fileIds, String tag) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        boolean res = fileService.removeTag(fileIds, tag, userId);
        //日志记录HttpServletRequest request
        recordService.createRecordLog("删除照片标签:" + tag, 1, userId, request);
        return res ? ResultUtil.success("删除成功") : ResultUtil.error(StatusCode.PARAMS_ERROR, "删除失败");
    }


    @PostMapping("/selectTagByFileId")
    public BaseResponse<List<String>> selectTagByFileId(String fileId) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        List<String> strings = fileService.selectTagByFileId(fileId, userId);
        return ResultUtil.success(strings, "查询成功");
    }


    @PostMapping("/createSimilarFileList")
    public BaseResponse<?> createSimilarFileList(Double similarity, Integer size) {
        // 参数兜底（前端已有 min/max 限制，这里二次防御）
        if (similarity == null || similarity < 0.0) similarity = 0.80;
        if (size == null || size < 1) size = 30;

        Long userId = Long.parseLong(StpUtil.getLoginId().toString());

        // 实时计算余弦相似度，结果不写入数据库
        List<SimilarFileInfoListVO> result = similarDetectService.detectSimilarImages(similarity, size, userId);

        String msg = result.isEmpty()
                ? "未检测到相似图片，可尝试降低相似度阈值"
                : "检测完成，共找到" + result.size() + "组相似图片";
        return ResultUtil.success(result, msg);
    }

    /**
     * 获取相似图片列表（页面初始化 / 删除后刷新用）。
     *
     * <p>由于相似检测结果不做持久化存储，本接口始终返回空列表。
     * 前端需要重新点击“开始检测”获取最新结果。</p>
     */
    @PostMapping("/getSimilarFileList")
    public BaseResponse<?> getSimilarFileList(String imageTypeText) {
        // 相似图片不永久存库，此接口仅供初始化和删除后刷新调用，固定返回空列表
        return ResultUtil.success(Collections.emptyList(), "请点击开始检测获取相似图片");
    }


    @PostMapping("/getPictureTag")
    public BaseResponse<ImageTagTaskVO> getPictureTag(
            String fileId,
            String thumbnailObjectName,
            Boolean autoAddTag
    ) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        ImageTagTaskVO task = fileService.getPictureTag(
                fileId,
                thumbnailObjectName,
                autoAddTag,
                userId
        );
        return ResultUtil.success(task, "图像识别任务已提交");
    }

    @PostMapping("/batchGetPictureTag")
    public BaseResponse<BatchGetPictureTagResponseVO> batchGetPictureTag(@RequestBody BatchGetPictureTagRequest request) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        BatchGetPictureTagResponseVO response = fileService.batchGetPictureTag(
                request == null ? null : request.getFileIds(),
                request != null && Boolean.TRUE.equals(request.getAutoAddTag()),
                userId
        );
        return ResultUtil.success(response, "批量识别任务已提交");
    }
}
