package com.memory.xzp.config;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.utils.file.FileUtil;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Getter
@Component
public class UploadPolicy {

    private static final Map<String, Set<String>> MIME_TYPES = Map.of(
            "jpg", Set.of("image/jpeg"),
            "jpeg", Set.of("image/jpeg"),
            "png", Set.of("image/png"),
            "gif", Set.of("image/gif"),
            "bmp", Set.of("image/bmp", "image/x-ms-bmp"),
            "mp4", Set.of("video/mp4", "application/octet-stream"),
            "mkv", Set.of("video/x-matroska", "application/octet-stream"),
            "wmv", Set.of("video/x-ms-wmv", "application/octet-stream"),
            "flv", Set.of("video/x-flv", "application/octet-stream"),
            "rmvb", Set.of("application/vnd.rn-realmedia-vbr", "application/octet-stream")
    );

    private final FileUtil fileUtil;

    @Value("${upload.image-max-size}")
    private long imageMaxSize;

    @Value("${upload.video-max-size}")
    private long videoMaxSize;

    @Value("${upload.avatar-max-size}")
    private long avatarMaxSize;

    public UploadPolicy(FileUtil fileUtil) {
        this.fileUtil = fileUtil;
    }

    public ValidatedUpload validate(String filename, String contentType, long size) {
        if (filename == null || filename.isBlank() || size <= 0) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件名或文件大小无效");
        }
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件扩展名无效");
        }
        String suffix = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        String category = fileUtil.getFileType(suffix);
        if (category.isBlank()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "不支持的文件格式");
        }

        long maxSize = "image".equals(category) ? imageMaxSize : videoMaxSize;
        if (size > maxSize) {
            throw new BusinessException(
                    StatusCode.PARAMS_ERROR,
                    "文件超过允许大小，最大 " + maxSize + " 字节"
            );
        }

        String normalizedContentType = contentType == null
                ? "application/octet-stream"
                : contentType.toLowerCase(Locale.ROOT);
        Set<String> accepted = MIME_TYPES.get(suffix);
        if (accepted == null || !accepted.contains(normalizedContentType)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "文件类型与扩展名不匹配");
        }
        return new ValidatedUpload(suffix, category, normalizedContentType);
    }

    public void validateAvatar(String filename, String contentType, long size) {
        ValidatedUpload upload = validate(filename, contentType, size);
        if (!"image".equals(upload.category()) || size > avatarMaxSize) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "头像必须是小于 5MB 的图片");
        }
    }

    public record ValidatedUpload(String suffix, String category, String contentType) {
    }
}
