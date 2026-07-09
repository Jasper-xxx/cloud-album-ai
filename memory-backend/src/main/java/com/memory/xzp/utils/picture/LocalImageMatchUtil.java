package com.memory.xzp.utils.picture;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class LocalImageMatchUtil {

    private static final int GRID_SIZE = 4;
    private static final int NORMALIZED_SIZE = 256;
    private static final int HASH_WIDTH = 9;
    private static final int HASH_HEIGHT = 8;

    public double computeOrbSimilarity(byte[] leftImageBytes, byte[] rightImageBytes) {
        BufferedImage left = readImage(leftImageBytes);
        BufferedImage right = readImage(rightImageBytes);
        if (left == null || right == null) {
            return 0D;
        }

        BufferedImage normalizedLeft = resize(toRgb(left), NORMALIZED_SIZE, NORMALIZED_SIZE);
        BufferedImage normalizedRight = resize(toRgb(right), NORMALIZED_SIZE, NORMALIZED_SIZE);

        long[] leftHashes = buildGridHashes(normalizedLeft);
        long[] rightHashes = buildGridHashes(normalizedRight);
        double localScore = bestRegionMatch(leftHashes, rightHashes);
        double globalScore = hashSimilarity(differenceHash(normalizedLeft), differenceHash(normalizedRight));
        double ratioScore = aspectRatioSimilarity(left, right);

        return clamp(localScore * 0.65D + globalScore * 0.25D + ratioScore * 0.10D);
    }

    private BufferedImage readImage(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(imageBytes)) {
            return ImageIO.read(inputStream);
        } catch (IOException e) {
            return null;
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

    private long[] buildGridHashes(BufferedImage image) {
        int cellWidth = image.getWidth() / GRID_SIZE;
        int cellHeight = image.getHeight() / GRID_SIZE;
        long[] hashes = new long[GRID_SIZE * GRID_SIZE];
        int index = 0;
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int col = 0; col < GRID_SIZE; col++) {
                hashes[index++] = differenceHash(image.getSubimage(
                        col * cellWidth,
                        row * cellHeight,
                        cellWidth,
                        cellHeight
                ));
            }
        }
        return hashes;
    }

    private double bestRegionMatch(long[] leftHashes, long[] rightHashes) {
        List<Double> bestScores = new ArrayList<>(leftHashes.length);
        for (long leftHash : leftHashes) {
            double best = 0D;
            for (long rightHash : rightHashes) {
                best = Math.max(best, hashSimilarity(leftHash, rightHash));
            }
            bestScores.add(best);
        }

        bestScores.sort(Comparator.reverseOrder());
        int sampleCount = Math.max(4, bestScores.size() / 2);
        double total = 0D;
        for (int i = 0; i < sampleCount; i++) {
            total += bestScores.get(i);
        }
        return total / sampleCount;
    }

    private double hashSimilarity(long leftHash, long rightHash) {
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

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }
}
