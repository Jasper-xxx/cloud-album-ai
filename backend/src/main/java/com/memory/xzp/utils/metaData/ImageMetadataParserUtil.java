package com.memory.xzp.utils.metaData;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.memory.xzp.model.entity.ImageMetaData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * @description: 图片元数据提取工具
 *   支持从 EXIF/XMP/IPTC 等目录中提取图片拍摄信息，包含增强版 GPS 坐标解析。
 * @author: xzp
 * @date: 2025/2/19,21:14
 */
@Slf4j
@Component
public class ImageMetadataParserUtil {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    // ── GPS 坐标解析正则 ──────────────────────────────────────────────────────────
    // DMS（度分秒），支持 °/d 分隔度，'/′/m 分隔分，"/″/s 分隔秒（秒可省略）
    private static final Pattern DMS_PATTERN = Pattern.compile(
            "([+-]?\\d+(?:\\.\\d+)?)\\s*[°d]\\s*(\\d+(?:\\.\\d+)?)\\s*['\u2032m]\\s*(\\d+(?:\\.\\d+)?)\\s*[\"\u2033s]?"
    );
    // DM（度分，无秒）
    private static final Pattern DM_PATTERN = Pattern.compile(
            "([+-]?\\d+(?:\\.\\d+)?)\\s*[°d]\\s*(\\d+(?:\\.\\d+)?)\\s*['\u2032m]"
    );

    // ── 公共入口 ──────────────────────────────────────────────────────────────────

    /**
     * 从 MultipartFile 提取图片元数据（用于上传接口）
     */
    public ImageMetaData GetIMageMetadata(MultipartFile file) throws IOException, ImageProcessingException {
        Metadata metadata = ImageMetadataReader.readMetadata(file.getInputStream());
        return extractFromMetadata(metadata, file.getOriginalFilename());
    }

    public ImageMetaData getImageMetadata(InputStream inputStream, String fileName)
            throws IOException, ImageProcessingException {
        Metadata metadata = ImageMetadataReader.readMetadata(inputStream);
        return extractFromMetadata(metadata, fileName);
    }

    // ── 核心解析 ──────────────────────────────────────────────────────────────────

    private static ImageMetaData extractFromMetadata(Metadata metadata, String fileName) {
        ImageMetaData imageMetadata = new ImageMetaData();
        for (Directory directory : metadata.getDirectories()) {
            for (Tag tag : directory.getTags()) {
                String tagName    = tag.getTagName();
                String description = tag.getDescription();
                if (description == null || description.isBlank()) continue;
                try {
                    mapTagToField(imageMetadata, tagName, description);
                } catch (Exception e) {
                    // 单字段解析失败不影响其他字段，以 debug 级别记录
                    log.debug("[元数据] 解析失败 file={} tag={} value={} msg={}",
                            fileName, tagName, description, e.getMessage());
                }
            }
        }
        // ── 后处理：根据 Ref 字段对 GPS 坐标进行符号纠正 ─────────────────────────
        // EXIF GPS 坐标通常以正数存储，南纬/西经方向由 LatitudeRef/LongitudeRef 指定
        applyGpsSign(imageMetadata);
        return imageMetadata;
    }

    /**
     * 根据 LatitudeRef(N/S) 和 LongitudeRef(E/W) 为坐标设置正确的符号。
     * 南纬(S)→负纬度，西经(W)→负经度。
     */
    private static void applyGpsSign(ImageMetaData m) {
        if ("S".equalsIgnoreCase(m.getLatitudeRef())
                && m.getLatitude() != null && m.getLatitude() > 0) {
            m.setLatitude(-m.getLatitude());
            log.debug("[元数据] GPS 纬度符号纠正（南纬）: {}", m.getLatitude());
        }
        if ("W".equalsIgnoreCase(m.getLongitudeRef())
                && m.getLongitude() != null && m.getLongitude() > 0) {
            m.setLongitude(-m.getLongitude());
            log.debug("[元数据] GPS 经度符号纠正（西经）: {}", m.getLongitude());
        }
    }

    // ── 标签映射 ──────────────────────────────────────────────────────────────────

    /**
     * 将单个 Tag 的值映射到 ImageMetaData 对应字段。
     * 新增了多种非标准 GPS 标签名支持，提高兼容性。
     */
    private static void mapTagToField(ImageMetaData imageMetadata, String tagName, String description) {
        switch (tagName) {

            /* ── 图像基础信息 ─────────────────────────── */
            case "Image Width":
                imageMetadata.setWidth(parseInt(description.replace(" pixels", "").trim()));
                break;
            case "Image Height":
                imageMetadata.setHeight(parseInt(description.replace(" pixels", "").trim()));
                break;
            case "Make":
                imageMetadata.setMake(description);
                break;
            case "Model":
                imageMetadata.setModel(description);
                break;
            case "Software":
                imageMetadata.setSoftware(description);
                break;
            case "Date/Time Original":
                try {
                    imageMetadata.setDateTimeOriginal(
                            LocalDateTime.parse(description, DATE_TIME_FORMATTER));
                } catch (Exception e) {
                    log.debug("[元数据] 时间解析失败: {}", description);
                }
                break;

            /* ── 曝光参数 ─────────────────────────────── */
            case "Exposure Program":
                imageMetadata.setExposureProgram(description);
                break;
            case "Exposure Time":
                imageMetadata.setExposureTime(parseExposureTime(description));
                break;
            case "F-Number":
                imageMetadata.setFNumber(parseFNumber(description));
                break;
            case "ISO Speed Ratings":
            case "ISO":
            case "Recommended Exposure Index":
                // 部分设备返回 "Unknown" 等非数字字符串，安全解析
                imageMetadata.setIso(parseInt(description));
                break;
            case "Focal Length":
                imageMetadata.setFocalLength(
                        parseDouble(description.replace(" mm", "").replace("mm", "").trim()));
                break;
            case "Focal Length 35":
            case "Focal Length 35mm Film":
                imageMetadata.setFocalLength35(
                        parseDouble(description.replace("mm", "").trim()));
                break;
            case "Aperture Value":
                imageMetadata.setApertureValue(parseFNumber(description));
                break;
            case "Shutter Speed Value":
                imageMetadata.setShutterSpeed(parseShutterSpeed(description));
                break;
            case "Metering Mode":
                imageMetadata.setMeteringMode(description);
                break;
            case "White Balance":
                imageMetadata.setWhiteBalance(description);
                break;
            case "Color Space":
                imageMetadata.setColorSpace(description);
                break;
            case "Sensing Method":
                imageMetadata.setSensingMethod(description);
                break;
            case "Subject Distance":
                imageMetadata.setSubjectDistance(
                        parseDouble(description.replace(" metres", "").replace(" m", "").trim()));
                break;
            case "Scene Type":
                imageMetadata.setSceneType(description);
                break;

            /* ── GPS 标准标签（metadata-extractor 标准名） ─ */
            case "GPS Latitude":
            case "GPS Lat":          // 非标准缩写
            case "Latitude": {       // 部分设备省略前缀
                Double lat = parseGpsCoordinate(description);
                if (lat != null) {
                    imageMetadata.setLatitude(lat);
                } else {
                    log.warn("[元数据] GPS 纬度解析失败: tagName={} value={}", tagName, description);
                }
                break;
            }
            case "GPS Latitude Ref":
                imageMetadata.setLatitudeRef(description);
                break;

            case "GPS Longitude":
            case "GPS Lon":          // 非标准缩写
            case "Longitude": {      // 部分设备省略前缀
                Double lon = parseGpsCoordinate(description);
                if (lon != null) {
                    imageMetadata.setLongitude(lon);
                } else {
                    log.warn("[元数据] GPS 经度解析失败: tagName={} value={}", tagName, description);
                }
                break;
            }
            case "GPS Longitude Ref":
                imageMetadata.setLongitudeRef(description);
                break;

            case "GPS Altitude":
                imageMetadata.setAltitude(
                        parseDouble(description.replace(" metres", "").replace(" m", "").trim()));
                break;
            case "GPS Altitude Ref":
                imageMetadata.setAltitudeRef(description);
                break;

            /* ── GPS 扩展标签（记录到日志，暂不持久化） ── */
            case "GPS Date Stamp":
            case "GPS Time-Stamp":
            case "GPS Speed":
            case "GPS Speed Ref":
            case "GPS Img Direction":
            case "GPS Img Direction Ref":
            case "GPS Map Datum":
            case "GPS Measure Mode":
            case "GPS DOP":
                log.debug("[元数据] GPS 扩展标签: {}={}", tagName, description);
                break;

            /* ── GPS 非标准合并字段 ─────────────────────── */
            // 部分软件输出 "GPS Position" 或 "GPS Coords"，格式为 "34.1234, 108.8765"
            case "GPS Position":
            case "GPS Coords":
            case "GPS Coordinates": {
                String[] parts = description.split("[,\\s]+");
                if (parts.length >= 2) {
                    Double lat = parseGpsCoordinate(parts[0].trim());
                    Double lon = parseGpsCoordinate(parts[1].trim());
                    if (lat != null) imageMetadata.setLatitude(lat);
                    if (lon != null) imageMetadata.setLongitude(lon);
                }
                break;
            }

            /* ── EXIF 版本 ────────────────────────────── */
            case "Exif Version":
                imageMetadata.setVersion(description);
                break;

            default:
                // 其余未映射的标签静默忽略
                break;
        }
    }

    // ── GPS 坐标增强解析 ──────────────────────────────────────────────────────────

    /**
     * 增强版 GPS 坐标解析，支持以下格式（解析失败返回 null，不返回 0.0 以避免歧义）：
     *
     * <pre>
     * 格式1 DMS（度分秒）: "50° 3' 44.04\""  "50°3'44\""  "50d 3m 44.04s"
     * 格式2 DM（度分）:    "50° 3'"           "50d 3m"
     * 格式3 纯小数度:      "50.0624833"       "+50.0624833"  "-50.0624833"
     * 格式4 EXIF有理数:    "50/1, 3/1, 4404/100"
     * 格式5 仅整数度:      "50"（罕见，直接返回）
     * </pre>
     *
     * @param value 原始坐标字符串
     * @return 十进制度数，null 表示解析失败
     */
    static Double parseGpsCoordinate(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();

        // ─ 格式3: 纯小数度（包含小数点，含可选正负号）─
        // 最优先匹配，避免 DMS 正则误匹配小数点后数字
        if (v.matches("[+-]?\\d+\\.\\d+")) {
            return Double.parseDouble(v);
        }

        // ─ 格式1: DMS（度°分'秒"，支持多种分隔符）─
        Matcher dms = DMS_PATTERN.matcher(v);
        if (dms.find()) {
            double deg = Double.parseDouble(dms.group(1));
            double min = Double.parseDouble(dms.group(2));
            double sec = Double.parseDouble(dms.group(3));
            double result = Math.abs(deg) + min / 60.0 + sec / 3600.0;
            return deg < 0 ? -result : result;
        }

        // ─ 格式2: DM（度分，无秒）─
        Matcher dm = DM_PATTERN.matcher(v);
        if (dm.find()) {
            double deg = Double.parseDouble(dm.group(1));
            double min = Double.parseDouble(dm.group(2));
            double result = Math.abs(deg) + min / 60.0;
            return deg < 0 ? -result : result;
        }

        // ─ 格式4: EXIF 有理数分数 "50/1, 3/1, 4404/100" ─
        if (v.contains("/") && v.contains(",")) {
            String[] parts = v.split(",");
            if (parts.length == 3) {
                try {
                    double deg = parseRational(parts[0].trim());
                    double min = parseRational(parts[1].trim());
                    double sec = parseRational(parts[2].trim());
                    return deg + min / 60.0 + sec / 3600.0;
                } catch (Exception ignored) { /* 继续尝试其他格式 */ }
            }
        }

        // ─ 格式5: 直接尝试解析为纯数字（整数度或含符号的整数）─
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignored) { /* 不可解析 */ }

        return null;
    }

    /**
     * 解析 EXIF 有理数格式 "分子/分母"，例如 "3600/100" → 36.0
     */
    private static double parseRational(String rational) {
        String[] parts = rational.trim().split("/");
        if (parts.length == 2) {
            double denominator = Double.parseDouble(parts[1]);
            if (denominator == 0) return 0.0;
            return Double.parseDouble(parts[0]) / denominator;
        }
        return Double.parseDouble(rational);
    }

    // ── 其他辅助解析 ──────────────────────────────────────────────────────────────

    private static String parseExposureTime(String value) {
        return value.replace(" sec", "");
    }

    /**
     * 解析光圈值，兼容多种格式：F1.8 / f1.8 / f/1.8 / F/1.8
     */
    private static double parseFNumber(String value) {
        String cleaned = value.trim().replaceAll("(?i)^f/?", "").trim();
        return parseDouble(cleaned);
    }

    /**
     * 安全解析 double，无法解析时返回 0.0（不抛异常）
     */
    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) return 0.0;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 安全解析 int，无法解析时返回 null（保持字段为空，而非错误值）
     */
    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double parseShutterSpeed(String value) {
        String[] parts = value.split("/");
        if (parts.length == 2) {
            try {
                return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
        return 0.0;
    }
}
