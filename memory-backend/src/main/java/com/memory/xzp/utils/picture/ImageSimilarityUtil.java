package com.memory.xzp.utils.picture;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Pure Java image similarity utility used by duplicate-image grouping.
 */
@Component
public class ImageSimilarityUtil {

    private static final int HISTOGRAM_WIDTH = 128;
    private static final int HISTOGRAM_HEIGHT = 128;
    private static final int HASH_WIDTH = 9;
    private static final int HASH_HEIGHT = 8;
    private static final int H_BINS = 36;
    private static final int S_BINS = 20;
    private static final double DEFAULT_THRESHOLD = 0.7D;

    public boolean isSamePicture(Double threshold, byte[] leftBytes, byte[] rightBytes) {
        double effectiveThreshold = threshold == null ? DEFAULT_THRESHOLD : threshold;
        return compare(leftBytes, rightBytes) >= effectiveThreshold;
    }

    public double compare(byte[] leftBytes, byte[] rightBytes) {
        BufferedImage left = readImage(leftBytes);
        BufferedImage right = readImage(rightBytes);
        double histogramSimilarity = histogramIntersection(buildHsvHistogram(left), buildHsvHistogram(right));
        double hashSimilarity = hashSimilarity(left, right);
        double ratioSimilarity = aspectRatioSimilarity(left, right);
        return clamp(histogramSimilarity * 0.55D + hashSimilarity * 0.35D + ratioSimilarity * 0.10D);
    }

    private BufferedImage readImage(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("输入图片数据为空");
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new IllegalArgumentException("输入图片数据无效，无法解码为图像");
            }
            return toRgb(image);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取图片数据失败", e);
        }
    }

    private BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private double[] buildHsvHistogram(BufferedImage image) {
        BufferedImage scaled = resize(image, HISTOGRAM_WIDTH, HISTOGRAM_HEIGHT);
        double[] histogram = new double[H_BINS * S_BINS];
        int pixelCount = scaled.getWidth() * scaled.getHeight();
        for (int y = 0; y < scaled.getHeight(); y++) {
            for (int x = 0; x < scaled.getWidth(); x++) {
                int rgb = scaled.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                float[] hsv = Color.RGBtoHSB(red, green, blue, null);
                int hIndex = Math.min(H_BINS - 1, (int) (hsv[0] * H_BINS));
                int sIndex = Math.min(S_BINS - 1, (int) (hsv[1] * S_BINS));
                histogram[hIndex * S_BINS + sIndex] += 1D;
            }
        }
        for (int i = 0; i < histogram.length; i++) {
            histogram[i] /= pixelCount;
        }
        return histogram;
    }

    private double histogramIntersection(double[] left, double[] right) {
        double total = 0D;
        for (int i = 0; i < left.length; i++) {
            total += Math.min(left[i], right[i]);
        }
        return clamp(total);
    }

    private double hashSimilarity(BufferedImage left, BufferedImage right) {
        long leftHash = differenceHash(left);
        long rightHash = differenceHash(right);
        int distance = Long.bitCount(leftHash ^ rightHash);
        return 1D - (distance / 64D);
    }

    private long differenceHash(BufferedImage image) {
        BufferedImage scaled = resize(image, HASH_WIDTH, HASH_HEIGHT);
        long hash = 0L;
        int bitIndex = 0;
        for (int y = 0; y < HASH_HEIGHT; y++) {
            for (int x = 0; x < HASH_WIDTH - 1; x++) {
                int currentGray = grayscale(scaled.getRGB(x, y));
                int nextGray = grayscale(scaled.getRGB(x + 1, y));
                if (currentGray > nextGray) {
                    hash |= (1L << bitIndex);
                }
                bitIndex++;
            }
        }
        return hash;
    }

    private int grayscale(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }

    private double aspectRatioSimilarity(BufferedImage left, BufferedImage right) {
        double leftRatio = left.getWidth() / (double) left.getHeight();
        double rightRatio = right.getWidth() / (double) right.getHeight();
        double diff = Math.abs(Math.log(leftRatio / rightRatio));
        return clamp(1D - Math.min(1D, diff));
    }

    private BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }
}
