package com.memory.xzp.service.impl;

import com.memory.xzp.mapper.FileFeatureMapper;
import com.memory.xzp.model.dto.FileFeatureQueryDTO;
import com.memory.xzp.model.vo.SimilarFileInfoListVO;
import com.memory.xzp.model.vo.entity.FileInfo;
import com.memory.xzp.service.SimilarDetectService;
import com.memory.xzp.utils.CosineSimilarityUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SimilarDetectServiceImpl implements SimilarDetectService {

    @Resource
    private FileFeatureMapper fileFeatureMapper;

    @Value("${ai.feature.provider:aliyun}")
    private String featureProvider;

    @Value("${ai.feature.model:qwen3-vl-embedding}")
    private String featureModel;

    private final java.util.Map<String, CacheEntry> cache = new java.util.LinkedHashMap<>();
    private record CacheEntry(long expiresAt, List<List<Integer>> groups) {}

    @Override
    public List<SimilarFileInfoListVO> detectSimilarImages(Double threshold, Integer maxGroups, Long userId) {
        return discover(threshold, maxGroups, userId, 500, 10, value -> {});
    }

    public List<SimilarFileInfoListVO> discover(Double threshold, Integer maxGroups, Long userId,
            int candidateLimit, int seconds, java.util.function.IntConsumer progress) {
        if (threshold == null || !Double.isFinite(threshold) || threshold < 0 || threshold > 1
                || maxGroups == null || maxGroups < 1 || maxGroups > 50 || userId == null)
            throw new com.memory.xzp.exception.BusinessException(com.memory.xzp.exception.StatusCode.PARAMS_ERROR, "相似发现参数无效");
        candidateLimit = Math.max(2, Math.min(10000, candidateLimit));
        List<FileFeatureQueryDTO> features = fileFeatureMapper.selectDiscoveryCandidates(
                userId, featureProvider, featureModel, candidateLimit + 1);
        if (features.size() > candidateLimit) throw new com.memory.xzp.exception.BusinessException(
                com.memory.xzp.exception.StatusCode.PARAMS_ERROR,
                candidateLimit == 500 ? "图库超过500张，请使用后台相似发现任务，可查看进度和取消。" : "当前任务最多支持10000张有效特征图片，请缩小范围。");
        String key = fingerprint(features, userId, threshold);
        List<List<Integer>> groups = null;
        synchronized(cache) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAt() < System.currentTimeMillis());
            CacheEntry entry = cache.get(key);
            if(entry != null) groups=entry.groups();
        }
        if(groups==null) {
            groups=com.memory.xzp.service.SimilarDiscoveryEngine.group(features, threshold,
                    System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(Math.min(120,seconds)), progress);
            synchronized(cache) {
                if(cache.size()>=8) cache.remove(cache.keySet().iterator().next());
                cache.put(key,new CacheEntry(System.currentTimeMillis()+120000,groups));
            }
        }
        progress.accept(100);
        return groups.stream().limit(maxGroups).map(g -> {
            SimilarFileInfoListVO result=new SimilarFileInfoListVO();
            result.setSimilarId(UUID.randomUUID().toString());
            result.setTotalFiles(g.size());
            result.setTruncated(g.size()>50);
            result.setFileList(g.stream().limit(50).map(i -> toFileInfo(features.get(i))).toList());
            return result;
        }).toList();
    }

    private String fingerprint(List<FileFeatureQueryDTO> features, Long userId, double threshold) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            digest.update((userId+":"+threshold+":"+featureProvider+":"+featureModel).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for(var f:features) {
                digest.update(f.getFileId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(f.getFeatureVector());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }

    private FileInfo toFileInfo(FileFeatureQueryDTO dto) {
        FileInfo info = new FileInfo();
        info.setFileId(dto.getFileId());
        info.setOriginFileName(dto.getOriginFileName());
        info.setSize(dto.getSize());
        info.setContentType(dto.getContentType());
        info.setCategory(dto.getCategory());
        info.setDuration(null);
        info.setWidth(dto.getWidth());
        info.setHeight(dto.getHeight());
        info.setFileUrl(dto.getFileUrl());
        info.setThumbnailUrl(dto.getThumbnailUrl());
        info.setThumbnailObjectName(dto.getThumbnailObjectName());
        return info;
    }
}
