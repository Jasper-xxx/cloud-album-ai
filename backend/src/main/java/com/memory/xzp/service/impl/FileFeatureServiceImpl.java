package com.memory.xzp.service.impl;

import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.FileFeatureMapper;
import com.memory.xzp.model.dto.FileFeatureQueryDTO;
import com.memory.xzp.model.dto.imageSearch.ImageSearchRequestDTO;
import com.memory.xzp.model.entity.FileFeature;
import com.memory.xzp.model.vo.entity.ImageSearchResultVO;
import com.memory.xzp.service.ExternalServiceExecutor;
import com.memory.xzp.service.FileFeatureService;
import com.memory.xzp.utils.CosineSimilarityUtil;
import com.memory.xzp.utils.file.MinioOSSUtil;
import com.memory.xzp.utils.picture.LocalImageMatchUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class FileFeatureServiceImpl implements FileFeatureService {

    private static final Logger log = LoggerFactory.getLogger(FileFeatureServiceImpl.class);

    private static final int LOCAL_TOP_N = 20;
    private static final int LOCAL_RECALL_TOP_N = 100;

    private static final String MODE_FUZZY = "fuzzy";
    private static final String MODE_EXACT = "exact";
    private static final String MODE_LOCAL = "local";

    private static final double EXACT_SIMILARITY_THRESHOLD = 0.85D;
    private static final double FUZZY_SIMILARITY_THRESHOLD = 0.60D;
    private static final double LOCAL_SIMILARITY_THRESHOLD = 0.12D;

    @Value("${ai.service.url}")
    private String aiServiceUrl;

    @Value("${ai.feature.provider:aliyun}")
    private String featureProvider;

    @Value("${ai.feature.model:qwen3-vl-embedding}")
    private String featureModel;

    @Value("${ai.feature.version:v1}")
    private String featureVersion;

    @Resource
    private FileFeatureMapper fileFeatureMapper;

    @Resource(name = "aiHttp11RestTemplate")
    private RestTemplate restTemplate;

    @Resource
    private MinioOSSUtil minioOSSUtil;

    @Resource
    private LocalImageMatchUtil localImageMatchUtil;

    @Resource
    private ExternalServiceExecutor externalServiceExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void extractAndSaveFeature(String fileId, Long userId, String fileObjectName) {
        log.info("开始提取特征向量: fileId={}, userId={}, objectName={}", fileId, userId, fileObjectName);

        Long existId = fileFeatureMapper.selectIdByFileIdAndUserId(
                fileId,
                userId,
                featureProvider,
                featureModel
        );
        if (existId != null) {
            log.info("特征向量已存在，跳过提取: fileId={}, featureId={}", fileId, existId);
            return;
        }

        FeatureExtractionResult extractionResult = callExtractFeatureByObjectKey(fileObjectName);
        float[] featureVector = extractionResult.featureVector();
        byte[] featureBytes = CosineSimilarityUtil.floatsToBytes(featureVector);

        FileFeature fileFeature = new FileFeature();
        fileFeature.setFileId(fileId);
        fileFeature.setUserId(userId);
        fileFeature.setFeatureVector(featureBytes);
        fileFeature.setFeatureDim(extractionResult.featureDim());
        fileFeature.setFeatureModel(extractionResult.featureModel());
        fileFeature.setFeatureProvider(extractionResult.featureProvider());
        fileFeature.setFeatureVersion(extractionResult.featureVersion());
        fileFeature.setCreateTime(LocalDateTime.now());
        fileFeatureMapper.insert(fileFeature);

        log.info("特征向量保存成功: fileId={}, 维度={}, 字节数={}", fileId, featureVector.length, featureBytes.length);
    }

    @Override
    public List<ImageSearchResultVO> searchSimilarImages(MultipartFile queryImage, Long userId, ImageSearchRequestDTO request) {
        validateQueryImage(queryImage);

        ImageSearchRequestDTO safeRequest = request == null ? new ImageSearchRequestDTO() : request;
        String mode = normalizeMode(safeRequest.getMode());

        FeatureExtractionResult queryFeature;
        try {
            queryFeature = callExtractFeatureByFile(queryImage);
        } catch (Exception e) {
            log.error("查询图片特征提取失败: {}", e.getMessage(), e);
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "AI服务调用失败，请稍后重试");
        }

        PixelRange pixelRange = resolvePixelRange(safeRequest.getSizeRange());
        List<FileFeatureQueryDTO> candidateFeatures = fileFeatureMapper.selectFeatureListByCondition(
                userId,
                normalizeAlbumIds(safeRequest.getAlbumIds()),
                normalizeTagNames(safeRequest.getTagNames()),
                pixelRange.minPixels(),
                pixelRange.maxPixels(),
                queryFeature.featureProvider(),
                queryFeature.featureModel(),
                queryFeature.featureDim(),
                expectedFeatureBytes(queryFeature.featureDim())
        );
        if (candidateFeatures == null || candidateFeatures.isEmpty()) {
            log.info("用户 {} 暂无可参与检索的候选图片", userId);
            return Collections.emptyList();
        }

        List<ScoredCandidate> scoredCandidates = candidateFeatures.stream()
                .map(candidate -> buildScoredCandidate(candidate, queryFeature.featureVector()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                .collect(Collectors.toList());

        if (MODE_LOCAL.equals(mode)) {
            return buildLocalModeResults(queryImage, scoredCandidates);
        }

        double threshold = resolveThreshold(mode);
        List<ImageSearchResultVO> results = scoredCandidates.stream()
                .filter(candidate -> candidate.score() >= threshold)
                .map(candidate -> toSearchResult(candidate.file(), candidate.score()))
                .collect(Collectors.toList());

        log.info("以图搜图完成: userId={}, mode={}, threshold={}, 候选总数={}, 命中数={}",
                userId, mode, threshold, candidateFeatures.size(), results.size());
        return results;
    }

    private void validateQueryImage(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "查询图片不能为空");
        }
        String contentType = image.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(StatusCode.PARAMS_ERROR,
                    "仅支持图片文件（jpg/png/gif/bmp/webp），当前类型: " + contentType);
        }
        if (image.getSize() > 20 * 1024 * 1024) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "查询图片大小不能超过 20MB");
        }
    }

    @SuppressWarnings("unchecked")
    private FeatureExtractionResult callExtractFeatureByObjectKey(String fileObjectName) {
        String url = aiServiceUrl + "/extract_feature";

        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("object_key", fileObjectName);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = externalServiceExecutor.execute(
                    ExternalServiceExecutor.AI,
                    () -> restTemplate.postForEntity(url, requestEntity, Map.class)
            );
            return parseFeatureFromResponse(response, fileObjectName);
        } catch (Exception e) {
            log.error("FastAPI 特征提取失败 (MinIO 模式): objectName={}, error={}", fileObjectName, e.getMessage(), e);
            throw new BusinessException(StatusCode.SYSTEM_ERROR,
                    "AI服务调用失败（MinIO 模式）: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private FeatureExtractionResult callExtractFeatureByFile(MultipartFile imageFile) {
        String url = aiServiceUrl + "/extract_feature";

        try {
            byte[] imageBytes = imageFile.getBytes();
            String originalFilename = imageFile.getOriginalFilename() != null
                    ? imageFile.getOriginalFilename() : "query.jpg";

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return originalFilename;
                }
            });

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = externalServiceExecutor.execute(
                    ExternalServiceExecutor.AI,
                    () -> restTemplate.postForEntity(url, requestEntity, Map.class)
            );
            return parseFeatureFromResponse(response, "uploaded-file");

        } catch (IOException e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "读取查询图片文件失败: " + e.getMessage());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("FastAPI 特征提取失败 (文件模式): error={}", e.getMessage(), e);
            throw new BusinessException(StatusCode.SYSTEM_ERROR,
                    "AI服务调用失败（文件模式）: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private FeatureExtractionResult parseFeatureFromResponse(ResponseEntity<Map> response, String imageDesc) {
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR,
                    "AI服务返回异常: HTTP " + response.getStatusCode());
        }

        Map<String, Object> body = response.getBody();
        if (body.containsKey("error")) {
            String errorMsg = String.valueOf(body.get("error"));
            log.error("AI 服务返回错误: imageDesc={}, error={}", imageDesc, errorMsg);
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "AI服务处理失败: " + errorMsg);
        }

        Object featureObj = body.get("feature");
        if (!(featureObj instanceof List<?> featureList) || featureList.isEmpty()) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR,
                    "AI服务返回格式异常: feature 字段缺失或为空");
        }

        float[] floats = new float[featureList.size()];
        for (int i = 0; i < featureList.size(); i++) {
            floats[i] = ((Number) featureList.get(i)).floatValue();
        }

        return new FeatureExtractionResult(
                floats,
                floats.length,
                getStringValue(body.get("featureProvider"), featureProvider),
                getStringValue(body.get("featureModel"), featureModel),
                getStringValue(body.get("featureVersion"), featureVersion)
        );
    }

    private ScoredCandidate buildScoredCandidate(FileFeatureQueryDTO dto, float[] queryVector) {
        if (dto.getFeatureVector() == null || dto.getFeatureVector().length == 0) {
            return null;
        }

        try {
            float[] dbVector = CosineSimilarityUtil.bytesToFloats(dto.getFeatureVector());
            double similarity = CosineSimilarityUtil.cosine(queryVector, dbVector);
            return new ScoredCandidate(dto, roundScore(similarity));
        } catch (Exception e) {
            log.warn("相似度计算异常，跳过该记录: fileId={}, error={}", dto.getFileId(), e.getMessage());
            return null;
        }
    }

    private List<ImageSearchResultVO> buildLocalModeResults(MultipartFile queryImage, List<ScoredCandidate> scoredCandidates) {
        byte[] queryBytes;
        try {
            queryBytes = queryImage.getBytes();
        } catch (IOException e) {
            throw new BusinessException(StatusCode.SYSTEM_ERROR, "读取查询图片失败: " + e.getMessage());
        }

        List<RankedCandidate> rankedCandidates = scoredCandidates.stream()
                .limit(LOCAL_RECALL_TOP_N)
                .map(candidate -> new RankedCandidate(
                        candidate.file(),
                        candidate.score(),
                        roundScore(computeLocalSimilarity(queryBytes, candidate.file()))
                ))
                .filter(candidate -> candidate.localScore() >= LOCAL_SIMILARITY_THRESHOLD)
                .sorted(Comparator.comparingDouble(RankedCandidate::localScore)
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(RankedCandidate::globalScore).reversed()))
                .limit(LOCAL_TOP_N)
                .collect(Collectors.toList());

        return rankedCandidates.stream()
                .map(candidate -> toSearchResult(candidate.file(), candidate.localScore()))
                .collect(Collectors.toList());
    }

    private double computeLocalSimilarity(byte[] queryBytes, FileFeatureQueryDTO fileFeature) {
        if (!StringUtils.hasText(fileFeature.getThumbnailObjectName())) {
            return 0D;
        }
        try {
            byte[] candidateBytes = minioOSSUtil.getFileBytes(fileFeature.getThumbnailObjectName());
            return localImageMatchUtil.computeOrbSimilarity(queryBytes, candidateBytes);
        } catch (Exception e) {
            log.warn("局部匹配计算失败，跳过候选图片: fileId={}, error={}", fileFeature.getFileId(), e.getMessage());
            return 0D;
        }
    }

    private ImageSearchResultVO toSearchResult(FileFeatureQueryDTO dto, double similarity) {
        ImageSearchResultVO vo = new ImageSearchResultVO();
        vo.setFileId(dto.getFileId());
        vo.setOriginFileName(dto.getOriginFileName());
        vo.setFileUrl(dto.getFileUrl());
        vo.setThumbnailUrl(dto.getThumbnailUrl());
        vo.setWidth(dto.getWidth());
        vo.setHeight(dto.getHeight());
        vo.setSimilarity(roundScore(similarity));
        vo.setSimilarityPercent(CosineSimilarityUtil.toPercent(similarity));
        return vo;
    }

    private double resolveThreshold(String mode) {
        if (MODE_EXACT.equals(mode)) {
            return EXACT_SIMILARITY_THRESHOLD;
        }
        return FUZZY_SIMILARITY_THRESHOLD;
    }

    private PixelRange resolvePixelRange(String sizeRange) {
        if (!StringUtils.hasText(sizeRange)) {
            return new PixelRange(null, null);
        }
        return switch (sizeRange.trim().toLowerCase(Locale.ROOT)) {
            case "small" -> new PixelRange(null, 1_000_000L);
            case "medium" -> new PixelRange(1_000_000L, 5_000_001L);
            case "large" -> new PixelRange(5_000_000L, null);
            default -> new PixelRange(null, null);
        };
    }

    private List<Long> normalizeAlbumIds(List<Long> albumIds) {
        if (albumIds == null || albumIds.isEmpty()) {
            return null;
        }
        return albumIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }

    private List<String> normalizeTagNames(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return null;
        }
        List<String> normalized = tagNames.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .collect(Collectors.toList());
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return MODE_FUZZY;
        }
        String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
        if (MODE_EXACT.equals(normalizedMode) || MODE_LOCAL.equals(normalizedMode)) {
            return normalizedMode;
        }
        return MODE_FUZZY;
    }

    private double roundScore(double similarity) {
        return Math.round(similarity * 10000.0D) / 10000.0D;
    }

    private String getStringValue(Object value, String defaultValue) {
        String text = value == null ? null : String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private int expectedFeatureBytes(int dimension) {
        return dimension * Float.BYTES;
    }

    private record FeatureExtractionResult(
            float[] featureVector,
            int featureDim,
            String featureProvider,
            String featureModel,
            String featureVersion
    ) {
    }

    private record ScoredCandidate(FileFeatureQueryDTO file, double score) {
    }

    private record RankedCandidate(FileFeatureQueryDTO file, double globalScore, double localScore) {
    }

    private record PixelRange(Long minPixels, Long maxPixels) {
    }
}
