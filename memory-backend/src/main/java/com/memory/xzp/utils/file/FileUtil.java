package com.memory.xzp.utils.file;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

@Component
public class FileUtil {

    private static final Set<String> ACCEPT_IMAGE_TYPES = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".bmp"
    );
    private static final Set<String> ACCEPT_VIDEO_TYPES = Set.of(
            ".mp4", ".rmvb", ".mkv", ".wmv", ".flv"
    );

    public String getFileType(String suffix) {
        String normalizedSuffix = "." + suffix.toLowerCase();
        if (ACCEPT_IMAGE_TYPES.contains(normalizedSuffix)) {
            return "image";
        }
        if (ACCEPT_VIDEO_TYPES.contains(normalizedSuffix)) {
            return "video";
        }
        return "";
    }

    public String getMD5(InputStream inputStream) {
        try (inputStream) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                md5.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(md5.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate MD5: " + e.getMessage(), e);
        }
    }

    public ByteArrayOutputStream outputQuality(byte[] source) {
        ByteArrayOutputStream thumbnailOutput = new ByteArrayOutputStream();
        try {
            Thumbnails.of(new ByteArrayInputStream(source))
                    .scale(0.15)
                    .outputQuality(0.65)
                    .toOutputStream(thumbnailOutput);
            return thumbnailOutput;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compress image: " + e.getMessage(), e);
        }
    }
}
