package com.memory.xzp.utils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 余弦相似度计算工具类
 * 用于以图搜图功能：计算两个特征向量之间的余弦相似度，并提供 float[] ↔ byte[] 转换
 *
 * <p>余弦相似度公式：cos(θ) = (A·B) / (|A| × |B|)</p>
 * <ul>
 *   <li>结果范围 [−1, 1]，值越接近 1 表示越相似</li>
 *   <li>字节序采用 小端序（LITTLE_ENDIAN），与 Python numpy.ndarray.tobytes() 默认一致</li>
 * </ul>
 *
 * @author xzp
 * @date 2026/03/20
 */
public class CosineSimilarityUtil {

    /**
     * 工具类不允许实例化
     */
    private CosineSimilarityUtil() {
    }

    /**
     * 计算两个 float 数组之间的余弦相似度
     *
     * @param vectorA 特征向量 A（如查询图片的特征）
     * @param vectorB 特征向量 B（如数据库中某张图片的特征）
     * @return 余弦相似度，范围 [−1, 1]；向量为零向量时返回 0.0
     * @throws IllegalArgumentException 两个向量维度不一致时抛出
     */
    public static double cosine(float[] vectorA, float[] vectorB) {
        if (vectorA == null || vectorB == null) {
            return 0.0;
        }
        if (vectorA.length != vectorB.length) {
            throw new IllegalArgumentException(
                    "特征向量维度不匹配: vectorA.length=" + vectorA.length
                    + "，vectorB.length=" + vectorB.length);
        }

        double dot = 0.0;   // 点积
        double normA = 0.0; // |A|^2
        double normB = 0.0; // |B|^2

        for (int i = 0; i < vectorA.length; i++) {
            dot   += (double) vectorA[i] * vectorB[i];
            normA += (double) vectorA[i] * vectorA[i];
            normB += (double) vectorB[i] * vectorB[i];
        }

        // 避免零向量导致除零
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 将字节数组（LONGBLOB 存储格式）转换为 float 数组
     *
     * <p>字节序：小端序（LITTLE_ENDIAN），与 Python numpy float32 tobytes() 一致</p>
     * <p>每 4 个字节对应一个 float32 值</p>
     *
     * @param bytes LONGBLOB 中读取的字节数组，长度应为 4 的整数倍
     * @return float32 数组；bytes 为 null 或长度为 0 时返回空数组
     */
    public static float[] bytesToFloats(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        int floatCount = bytes.length / 4;
        float[] floats = new float[floatCount];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < floatCount; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    /**
     * 将 float 数组转换为字节数组（用于存入 LONGBLOB 字段）
     *
     * <p>字节序：小端序（LITTLE_ENDIAN），与 Python numpy float32 frombuffer() 一致</p>
     * <p>字节长度由实际向量维度决定：dimension * 4 bytes</p>
     *
     * @param floats float32 特征向量数组
     * @return 字节数组；floats 为 null 或长度为 0 时返回空字节数组
     */
    public static byte[] floatsToBytes(float[] floats) {
        if (floats == null || floats.length == 0) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * 将相似度 double（[0,1]）转换为百分比整数（0~100）
     * 用于前端展示，如 0.856 → 86
     *
     * @param similarity 余弦相似度，范围 [0, 1]
     * @return 百分比整数（0~100）
     */
    public static int toPercent(double similarity) {
        return (int) Math.round(similarity * 100);
    }
}
