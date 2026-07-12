package com.memory.xzp.service;

import com.memory.xzp.config.UploadPolicy;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.utils.file.MinioOSSUtil;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

@Slf4j
@Service
public class UploadSecurityValidator {

    private static final int PROBE_BYTES = 64;

    private final MinioOSSUtil minioOSSUtil;

    @Value("${upload.security.max-image-pixels:50000000}")
    private long maxImagePixels;

    @Value("${upload.security.max-image-ratio:100}")
    private double maxImageRatio;

    @Value("${upload.security.video-temp-dir:${java.io.tmpdir}}")
    private String videoTempDir;

    public UploadSecurityValidator(MinioOSSUtil minioOSSUtil) {
        this.minioOSSUtil = minioOSSUtil;
        ImageIO.setUseCache(false);
    }

    public void validateMultipartFile(MultipartFile file, UploadPolicy.ValidatedUpload upload) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Upload file is empty");
        }
        try {
            if ("image".equals(upload.category())) {
                byte[] bytes = file.getBytes();
                validateMagic(upload.suffix(), bytes);
                validateImage(bytes);
                return;
            }
            Path tempFile = createTempVideoFile(upload.suffix());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                validateVideoFile(tempFile, upload.suffix(), file.getSize());
            } finally {
                deleteTempFile(tempFile);
            }
        } catch (IOException e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "Failed to validate upload content");
        }
    }

    public void validateObject(String objectName, UploadPolicy.ValidatedUpload upload, long expectedSize) {
        if ("image".equals(upload.category())) {
            byte[] bytes = minioOSSUtil.getFileBytes(objectName);
            if (bytes.length != expectedSize) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Object size validation failed");
            }
            validateMagic(upload.suffix(), bytes);
            validateImage(bytes);
            return;
        }

        Path tempFile = createTempVideoFile(upload.suffix());
        try {
            minioOSSUtil.downloadToFile(objectName, tempFile);
            validateVideoFile(tempFile, upload.suffix(), expectedSize);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private void validateImage(byte[] bytes) {
        int width;
        int height;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Image content is invalid");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Image content cannot be decoded");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Image content cannot be decoded");
        }

        validateImageDimensions(width, height);
        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Image content cannot be decoded");
            }
            validateImageDimensions(decoded.getWidth(), decoded.getHeight());
        } catch (IOException e) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Image content cannot be decoded");
        }
    }

    private void validateImageDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Image dimensions are invalid");
        }
        long pixels = (long) width * height;
        if (pixels > maxImagePixels) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Image pixel count exceeds limit");
        }
        double ratio = Math.max((double) width / height, (double) height / width);
        if (ratio > maxImageRatio) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Image aspect ratio is abnormal");
        }
    }

    private void validateVideoFile(Path file, String suffix, long expectedSize) {
        try {
            if (Files.size(file) != expectedSize) {
                throw new BusinessException(StatusCode.PARAMS_ERROR, "Object size validation failed");
            }
            byte[] probe = readProbeBytes(file);
            validateMagic(suffix, probe);
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile())) {
                grabber.start();
                int width = grabber.getImageWidth();
                int height = grabber.getImageHeight();
                long duration = grabber.getLengthInTime();
                String codecName = grabber.getVideoCodecName();
                int codec = grabber.getVideoCodec();
                grabber.stop();
                if (width <= 0 || height <= 0 || duration <= 0 || (codec == 0 && isBlank(codecName))) {
                    throw new BusinessException(StatusCode.PARAMS_ERROR, "Video content cannot be decoded");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Video upload validation failed: {}", file, e);
            throw new BusinessException(StatusCode.PARAMS_ERROR, "Video content cannot be decoded");
        }
    }

    private byte[] readProbeBytes(Path file) throws IOException {
        byte[] probe = new byte[PROBE_BYTES];
        try (InputStream input = Files.newInputStream(file)) {
            int read = input.read(probe);
            if (read <= 0) {
                return new byte[0];
            }
            byte[] result = new byte[read];
            System.arraycopy(probe, 0, result, 0, read);
            return result;
        }
    }

    private void validateMagic(String suffix, byte[] bytes) {
        if (!matchesMagic(suffix, bytes)) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "File content does not match extension");
        }
    }

    private boolean matchesMagic(String suffix, byte[] bytes) {
        String normalized = suffix == null ? "" : suffix.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "jpg", "jpeg" -> startsWith(bytes, 0xFF, 0xD8, 0xFF);
            case "png" -> startsWith(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "gif" -> startsWithAscii(bytes, "GIF87a") || startsWithAscii(bytes, "GIF89a");
            case "bmp" -> startsWithAscii(bytes, "BM");
            case "mp4" -> containsAscii(bytes, 4, Math.min(bytes.length, 24), "ftyp");
            case "mkv" -> startsWith(bytes, 0x1A, 0x45, 0xDF, 0xA3);
            case "wmv" -> startsWith(bytes, 0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11);
            case "flv" -> startsWithAscii(bytes, "FLV");
            case "rmvb" -> startsWithAscii(bytes, ".RMF");
            default -> false;
        };
    }

    private boolean startsWith(byte[] bytes, int... expected) {
        if (bytes == null || bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWithAscii(byte[] bytes, String expected) {
        if (bytes == null || bytes.length < expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if ((char) bytes[i] != expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsAscii(byte[] bytes, int from, int to, String expected) {
        if (bytes == null || bytes.length < expected.length()) {
            return false;
        }
        int end = Math.min(to, bytes.length - expected.length() + 1);
        for (int i = Math.max(0, from); i < end; i++) {
            boolean matched = true;
            for (int j = 0; j < expected.length(); j++) {
                if ((char) bytes[i + j] != expected.charAt(j)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private Path createTempVideoFile(String suffix) {
        try {
            Path dir = Path.of(videoTempDir);
            Files.createDirectories(dir);
            return Files.createTempFile(dir, "upload-validate-", "." + suffix);
        } catch (IOException e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "Failed to create upload validation temp file");
        }
    }

    private void deleteTempFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete upload validation temp file: {}", file, e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
