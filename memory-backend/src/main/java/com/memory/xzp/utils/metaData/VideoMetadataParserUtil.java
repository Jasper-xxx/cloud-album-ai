package com.memory.xzp.utils.metaData;


import com.memory.xzp.model.entity.VideoMetaData;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @description: 视频元数据提取工具（JavaCV/FFmpeg，支持多格式 GPS 解析）
 * @author: xzp
 * @date: 2025/2/19,21:14
 */
@Slf4j
@Component
public class VideoMetadataParserUtil {

    private static void mapMetadata(VideoMetaData metadata, String key, String value) {
        if (value == null || value.isBlank()) return;
        switch (key.toLowerCase()) {

            // ── 时间信息 ──────────────────────────────────────────────────────────
            case "creation_time":
            case "com.apple.quicktime.creationdate":   // iOS 设备时间
                metadata.setDateTimeOriginal(parseQuickTimeDate(value));
                break;

            // ── GPS 坐标 ──────────────────────────────────────────────────────────
            case "location":
            case "location-eng":                       // 与 location 格式相同，统一处理
            case "com.apple.quicktime.location.iso6709": // iOS ISO 6709 格式
            case "gps_coordinates":
            case "geo_coordinates":
                parseAndSetCoordinates(metadata, value);
                break;

            // ── 设备信息 ──────────────────────────────────────────────────────────
            case "com.android.manufacturer":
            case "manufacturer":
            case "make":
                if (metadata.getMake() == null) {      // 避免低优先级字段覆盖高优先级
                    metadata.setMake(value);
                }
                break;
            case "com.android.model":
            case "model":
                if (metadata.getModel() == null) {
                    metadata.setModel(value);
                }
                break;

            // ── 图像/编码参数 ─────────────────────────────────────────────────────
            case "color_space":
                metadata.setColorSpace(value);
                break;
            case "profile":
                metadata.setProfile(value);
                break;
            case "level":
                metadata.setLevel(value);
                break;
            case "pixel_format":
                metadata.setPixelFormat(value);
                break;

            // ── 文件信息 ──────────────────────────────────────────────────────────
            case "copyright":
                metadata.setCopyright(value);
                break;
            case "language":
                metadata.setLanguage(value);
                break;

            default:
                // 未映射的字段静默忽略，避免日志刷屏
                break;
        }
    }

    public VideoMetaData GetVideoMetadata(String filePath) {
        VideoMetaData metadata = new VideoMetaData();
        //隐藏log
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(filePath)) {
            grabber.start();

            // 基础信息
            metadata.setDuration(grabber.getLengthInTime() / (double) avutil.AV_TIME_BASE);
            metadata.setWidth(grabber.getImageWidth());
            metadata.setHeight(grabber.getImageHeight());
            metadata.setFps(grabber.getVideoFrameRate());
            metadata.setVideoCodecName(grabber.getVideoCodecName());
            metadata.setVideoCodec(grabber.getVideoCodec());
            metadata.setVideoBitrate(grabber.getVideoBitrate());
            metadata.setRotation(grabber.getDisplayRotation());
            metadata.setAudioCodecName(grabber.getAudioCodecName());
            metadata.setAudioCodec(grabber.getAudioCodec());
            metadata.setAudioChannels(grabber.getAudioChannels());

            // 解析元数据字典
            Map<String, String> metadataMap = grabber.getMetadata();
            metadataMap.forEach((key, value) -> {
                mapMetadata(metadata, key, value);
            });
            grabber.stop();

        } catch (Exception e) {
            log.error("[视频元数据] 解析失败(文件路径): {}", e.getMessage(), e);
            throw new RuntimeException("解析视频元数据失败: " + e.getMessage(), e);
        }
        return metadata;
    }

    public byte[] extractCover(String filePath) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(filePath)) {
            return extractCover(grabber);
        } catch (Exception e) {
            throw new RuntimeException("视频封面提取失败: " + e.getMessage(), e);
        }
    }

    private byte[] extractCover(FFmpegFrameGrabber grabber) throws Exception {

            // 1. 初始化视频解析
            grabber.start();

            // 2. 获取旋转元数据
             double rotate = grabber.getDisplayRotation();

            // 3. 获取首帧图像
            Frame frame = grabber.grabImage();
            if (frame == null) {
                throw new RuntimeException("无法获取视频首帧");
            }

            // 4. 转换并处理旋转
            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage image = converter.getBufferedImage(frame);

            image = applyRotation(image, (int) rotate);  // 关键修复点：应用旋转校正
            // 5. 输出处理后的图像
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", outputStream);
            return outputStream.toByteArray();

    }

    /**
     * 根据旋转角度校正图像方向
     * @param source 原始图像
     * @param degrees 顺时针旋转角度（必须是90的倍数）
     * @return 校正后的图像
     */
    private BufferedImage applyRotation(BufferedImage source, int degrees) {
        if (degrees % 90 != 0) {
            throw new IllegalArgumentException("不支持的旋转角度: " + degrees);
        }

        // 计算实际旋转角度（转换为逆时针角度）
        int steps = degrees / 90;
        int realRotation = (4 - steps % 4) % 4;  // 转换为逆时针旋转次数

        // 无需旋转的情况
        if (realRotation == 0) return source;

        // 计算新画布尺寸（90/270度需要交换宽高）
        int width = source.getWidth();
        int height = source.getHeight();
        int newWidth = realRotation % 2 == 0 ? width : height;
        int newHeight = realRotation % 2 == 0 ? height : width;

        // 创建目标图像
        BufferedImage rotated = new BufferedImage(newWidth, newHeight, source.getType());
        Graphics2D g2d = rotated.createGraphics();

        // 应用变换矩阵
        switch (realRotation) {
            case 1:  // 逆时针90度（等效顺时针270度）
                g2d.rotate(Math.toRadians(90));
                g2d.translate(0, -height);
                break;
            case 2:  // 180度
                g2d.rotate(Math.toRadians(180));
                g2d.translate(-width, -height);
                break;
            case 3:  // 逆时针270度（等效顺时针90度）
                g2d.rotate(Math.toRadians(270));
                g2d.translate(-width, 0);
                break;
        }

        // 绘制并释放资源
        g2d.drawImage(source, 0, 0, null);
        g2d.dispose();

        return rotated;
    }


    /**
     * 增强版视频 GPS 坐标解析，支持多种常见格式：
     *
     * <pre>
     * ISO 6709（最常见）: "+34.1436+108.8665/"   "+34.1436+108.8665+50/"（含海拔）
     * 逗号分隔小数:       "+34.143600,+108.866500"  "34.1436,108.8665"  "-34.14,+108.87"
     * 空格分隔小数:       "34.1436 108.8665"
     * 仅带符号无逗号:     "+34.1436-108.8665"（西经/南纬用负号区分）
     * </pre>
     *
     * 解析失败时仅记录警告，不抛异常，不影响上传流程。
     */
    private static void parseAndSetCoordinates(VideoMetaData metadata, String cordStr) {
        if (cordStr == null || cordStr.isBlank()) return;

        // 去除末尾 /、空白等噪声字符
        String cleaned = cordStr.trim().replaceAll("[/\\s]+$", "").trim();

        // ─ 方案1: 逗号分隔 "lat,lon" 或 "lat, lon" ─────────────────────────────
        // 示例: "+34.143600,+108.866500"  "34.1436,108.8665"
        if (cleaned.contains(",")) {
            String[] parts = cleaned.split(",");
            if (parts.length >= 2) {
                Double lat = parseDecimal(parts[0].trim());
                Double lon = parseDecimal(parts[1].trim());
                if (lat != null && lon != null) {
                    setCoordinates(metadata, lat, lon);
                    return;
                }
            }
        }

        // ─ 方案2: ISO 6709 紧凑格式 "+LAT+LON" 或 "+LAT-LON" ──────────────────
        // 示例: "+34.1436+108.8665"  "+34.1436-108.8665"  "-12.3456-67.8901"
        // 正则：第一个数（含符号），第二个数（必须有符号 +/-）
        Matcher iso = Pattern.compile(
                "([+-]?\\d+\\.\\d+)([+-]\\d+\\.\\d+)"
        ).matcher(cleaned);
        if (iso.find()) {
            Double lat = parseDecimal(iso.group(1));
            Double lon = parseDecimal(iso.group(2));
            if (lat != null && lon != null) {
                setCoordinates(metadata, lat, lon);
                return;
            }
        }

        // ─ 方案3: 空格分隔 "lat lon" ────────────────────────────────────────────
        String[] spaceParts = cleaned.split("\\s+");
        if (spaceParts.length >= 2) {
            Double lat = parseDecimal(spaceParts[0]);
            Double lon = parseDecimal(spaceParts[1]);
            if (lat != null && lon != null) {
                setCoordinates(metadata, lat, lon);
                return;
            }
        }

        log.warn("[视频元数据] GPS 坐标解析失败，无法识别格式: '{}'", cordStr);
    }

    /** 设置纬度/经度及其方向参考（正值→N/E，负值→S/W） */
    private static void setCoordinates(VideoMetaData metadata, double lat, double lon) {
        metadata.setLatitude(lat);
        metadata.setLatitudeRef(lat >= 0 ? "N" : "S");
        metadata.setLongitude(lon);
        metadata.setLongitudeRef(lon >= 0 ? "E" : "W");
        log.debug("[视频元数据] GPS 解析成功: lat={}, lon={}", lat, lon);
    }

    /** 安全解析带符号小数字符串，失败返回 null */
    private static Double parseDecimal(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
    // 辅助解析方法示例


    private static LocalDateTime parseQuickTimeDate(String value) {
        // 处理QuickTime特殊时间格式：2023-08-15T14:30:00+0800
        try {
            return LocalDateTime.parse(value,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSX"));
        } catch (Exception e) {
            return null;
        }
    }


}
