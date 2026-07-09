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

    @Override
    public List<SimilarFileInfoListVO> detectSimilarImages(Double threshold, Integer maxGroups, Long userId) {
        if (threshold == null || threshold < 0.0) threshold = 0.0;
        if (threshold > 1.0) threshold = 1.0;
        if (maxGroups == null || maxGroups < 1) maxGroups = 1;

        log.info("Similar image detection started: userId={}, threshold={}, maxGroups={}, provider={}, model={}",
                userId, threshold, maxGroups, featureProvider, featureModel);

        List<FileFeatureQueryDTO> features = fileFeatureMapper.selectFeatureListByUserId(
                userId,
                featureProvider,
                featureModel,
                null,
                null
        );
        if (features.size() < 2) {
            return Collections.emptyList();
        }

        int n = features.size();
        float[][] vectors = new float[n][];
        for (int i = 0; i < n; i++) {
            byte[] bytes = features.get(i).getFeatureVector();
            Integer storedDim = features.get(i).getFeatureDim();
            if (bytes == null || storedDim == null || storedDim <= 0 || bytes.length != storedDim * Float.BYTES) {
                vectors[i] = new float[0];
                log.warn("Skipping feature with unexpected byte length: fileId={}, bytes={}",
                        features.get(i).getFileId(),
                        bytes == null ? "null" : bytes.length);
            } else {
                vectors[i] = CosineSimilarityUtil.bytesToFloats(bytes);
            }
        }

        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            if (vectors[i].length == 0) continue;
            for (int j = i + 1; j < n; j++) {
                if (vectors[j].length == 0) continue;
                if (vectors[i].length != vectors[j].length) continue;
                double sim = CosineSimilarityUtil.cosine(vectors[i], vectors[j]);
                if (sim >= threshold) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<FileFeatureQueryDTO>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(features.get(i));
        }

        List<SimilarFileInfoListVO> result = groups.values().stream()
                .filter(g -> g.size() >= 2)
                .limit(maxGroups)
                .map(g -> {
                    SimilarFileInfoListVO vo = new SimilarFileInfoListVO();
                    vo.setSimilarId(UUID.randomUUID().toString());
                    vo.setFileList(g.stream().map(this::toFileInfo).collect(Collectors.toList()));
                    return vo;
                })
                .collect(Collectors.toList());

        log.info("Similar image detection finished: userId={}, groups={}", userId, result.size());
        return result;
    }

    private int find(int[] parent, int i) {
        if (parent[i] != i) {
            parent[i] = find(parent, parent[i]);
        }
        return parent[i];
    }

    private void union(int[] parent, int i, int j) {
        int ri = find(parent, i);
        int rj = find(parent, j);
        if (ri != rj) parent[ri] = rj;
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
