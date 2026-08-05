package com.memory.xzp.service;

import com.memory.xzp.config.UploadPolicy;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.model.dto.imageSearch.ImageSearchRequestDTO;
import com.memory.xzp.model.vo.agent.AgentAttachmentUploadVO;
import com.memory.xzp.model.vo.entity.ImageSearchResultVO;
import com.memory.xzp.utils.file.FileUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Dify 聊天附件与 Cloud-Album 文件标识之间的可信桥接。
 */
@Service
public class AgentAttachmentService {

    private final UploadPolicy uploadPolicy;
    private final UploadSecurityValidator uploadSecurityValidator;
    private final FileUtil fileUtil;
    private final FileService fileService;
    private final FileMapper fileMapper;
    private final FileFeatureService fileFeatureService;
    private final RecordService recordService;

    public AgentAttachmentService(
            UploadPolicy uploadPolicy,
            UploadSecurityValidator uploadSecurityValidator,
            FileUtil fileUtil,
            FileService fileService,
            FileMapper fileMapper,
            FileFeatureService fileFeatureService,
            RecordService recordService
    ) {
        this.uploadPolicy = uploadPolicy;
        this.uploadSecurityValidator = uploadSecurityValidator;
        this.fileUtil = fileUtil;
        this.fileService = fileService;
        this.fileMapper = fileMapper;
        this.fileFeatureService = fileFeatureService;
        this.recordService = recordService;
    }

    public AgentAttachmentUploadVO upload(
            MultipartFile attachment,
            Long albumId,
            Long lastModified,
            Long userId,
            HttpServletRequest servletRequest
    ) {
        if (attachment == null || attachment.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "attachment不能为空");
        }
        Long targetAlbumId = albumId == null ? -1L : albumId;
        String originalFilename = attachment.getOriginalFilename();
        UploadPolicy.ValidatedUpload validated = uploadPolicy.validate(
                originalFilename,
                attachment.getContentType(),
                attachment.getSize()
        );
        uploadSecurityValidator.validateMultipartFile(attachment, validated);
        String md5;
        try {
            md5 = fileUtil.getMD5(attachment.getInputStream());
        } catch (IOException exception) {
            throw new BusinessException(StatusCode.OPERATION_ERROR, "读取附件失败");
        }

        LocalDateTime modifiedAt = lastModified == null
                ? LocalDateTime.now()
                : Instant.ofEpochMilli(lastModified)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime();
        int dot = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "附件扩展名无效");
        }
        String suffix = originalFilename.substring(dot + 1);
        boolean uploaded = "image".equals(validated.category())
                ? Boolean.TRUE.equals(fileService.uploadImageFile(
                        userId, attachment, suffix, modifiedAt, targetAlbumId
                ))
                : Boolean.TRUE.equals(fileService.uploadVideoFile(
                        userId, attachment, suffix, modifiedAt, targetAlbumId
                ));
        if (!uploaded) {
            throw new BusinessException(StatusCode.OPERATION_ERROR, "附件上传失败");
        }
        String fileId = fileMapper.selectOwnedFileIdByMd5(userId, md5);
        if (fileId == null) {
            throw new BusinessException(StatusCode.OPERATION_ERROR, "附件已上传但未能解析fileId");
        }

        recordService.createRecordLog(
                "智能体:上传聊天附件 " + originalFilename,
                1,
                userId,
                servletRequest
        );
        AgentAttachmentUploadVO result = new AgentAttachmentUploadVO();
        result.setFileId(fileId);
        result.setOriginalFilename(originalFilename);
        result.setCategory(validated.category());
        result.setSize(attachment.getSize());
        result.setAlbumId(targetAlbumId > 0 ? targetAlbumId : null);
        result.setUploaded(true);
        return result;
    }

    public List<ImageSearchResultVO> search(
            MultipartFile attachment,
            String mode,
            List<Long> albumIds,
            List<String> tagNames,
            String sizeRange,
            Long userId
    ) {
        if (attachment == null || attachment.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "attachment不能为空");
        }
        UploadPolicy.ValidatedUpload validated = uploadPolicy.validate(
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                attachment.getSize()
        );
        if (!"image".equals(validated.category())) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "以图搜图附件必须是图片");
        }
        uploadSecurityValidator.validateMultipartFile(attachment, validated);
        ImageSearchRequestDTO request = new ImageSearchRequestDTO();
        request.setMode(mode);
        request.setAlbumIds(albumIds);
        request.setTagNames(tagNames);
        request.setSizeRange(sizeRange);
        return fileFeatureService.searchSimilarImages(attachment, userId, request);
    }
}
