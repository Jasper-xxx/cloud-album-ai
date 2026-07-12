package com.memory.xzp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.memory.xzp.mapper.FaceMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.PersonFaceMapper;
import com.memory.xzp.mapper.PersonMapper;
import com.memory.xzp.model.entity.Face;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.model.entity.Person;
import com.memory.xzp.model.entity.PersonFace;
import com.memory.xzp.service.ExternalServiceExecutor;
import com.memory.xzp.service.FaceService;
import com.memory.xzp.utils.CosineSimilarityUtil;
import com.memory.xzp.utils.file.MinioOSSUtil;
import jakarta.annotation.Resource;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 人像特征表服务实现类。
 * @author xzp
 * @since 2025-03-07
 */
@Service
public class FaceServiceImpl extends ServiceImpl<FaceMapper, Face> implements FaceService {

    private static final Logger log = LoggerFactory.getLogger(FaceServiceImpl.class);

    /**
     * 人脸聚类余弦相似度阈值。
     * AliyunEmbedding uses the model default vector dimension.
     * 不同人脸通常低于该阈值，用于兼顾精度与召回。
     */
    private static final double DEFAULT_FACE_COSINE_THRESHOLD = 0.46D;

    /**
     * Valid feature bytes are checked against the stored feature_dim value.
     * 用于区分新格式（float32 向量）与旧格式（AliyunVisual PNG 字节流）。
     */
    private static final int PERSON_CLUSTER_SAMPLE_LIMIT = 12;
    private static final int MAX_REPRESENTATIVE_FACES = 5;
    private static final int MIN_REPRESENTATIVE_FACES = 3;
    private static final int FACE_COVER_SIZE = 150;
    private static final int FACE_SELECTION_CANDIDATE_LIMIT = 3;

    @Resource
    private FaceMapper faceMapper;

    @Resource
    private FileMapper fileMapper;

    @Resource
    private PersonMapper personMapper;

    @Resource
    private MinioOSSUtil minioOSSUtil;

    @Resource
    private PersonFaceMapper personFaceMapper;

    /** 由 RestTemplateConfig 提供，包含连接超时与读取超时配置。 */
    @Resource(name = "aiHttp11RestTemplate")
    private RestTemplate restTemplate;

    @Resource
    private ExternalServiceExecutor externalServiceExecutor;

    /** Python 推理服务基础 URL，配置于 application.yml ai.service.url。 */
    @Value("${ai.service.url}")
    private String aiServiceUrl;

    @Value("${ai.feature.provider:aliyun}")
    private String featureProvider;

    @Value("${ai.feature.model:qwen3-vl-embedding}")
    private String featureModel;

    @Value("${ai.face.detect-provider:aliyun-qwen-vl-face-detect}")
    private String faceDetectProvider;

    @Value("${face.cluster.cosine-threshold:" + DEFAULT_FACE_COSINE_THRESHOLD + "}")
    private double faceCosineThreshold;

    // =========================================================================
    // 单张人脸处理（特征提取 + 人物聚类）
    // =========================================================================

    /**
     * 处理单张图片的人脸信息。
     *
     * @param face 待处理的人脸记录（is_processed = false）
     */
    @Transactional(rollbackFor = Exception.class)
    public void processOneFace(Face face) {
        UpdateWrapper<Face> updateWrapper = new UpdateWrapper<>();
        processOneFaceWithUnifiedPipeline(
                face,
                updateWrapper,
                face.getFaceId(),
                face.getUserId(),
                face.getFileId()
        );
    }

    private void processOneFaceWithUnifiedPipeline(
            Face face,
            UpdateWrapper<Face> updateWrapper,
            Long faceId,
            Long userId,
            String fileId
    ) {
        FileEntity fileEntity = fileMapper.selectById(fileId);
        if (fileEntity == null) {
            updateWrapper.eq("face_id", faceId);
            updateWrapper.set("is_processed", true);
            updateWrapper.set("is_face", false);
            faceMapper.update(updateWrapper);
            return;
        }

        byte[] fileBytes = loadFaceSourceBytes(fileEntity);
        if (fileBytes == null || fileBytes.length == 0) {
            updateWrapper.eq("face_id", faceId);
            updateWrapper.set("is_processed", true);
            updateWrapper.set("is_face", false);
            faceMapper.update(updateWrapper);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDate localDate = now.toLocalDate();
        updateWrapper.eq("face_id", faceId);
        updateWrapper.set("is_processed", true);
        updateWrapper.set("create_time", now);

        FaceAnalyzeResult analyzeResult = callFaceAnalyze(fileBytes, faceId);
        if (analyzeResult == null) {
            faceMapper.update(updateWrapper);
            return;
        }
        if (analyzeResult.getFaces().isEmpty()) {
            updateWrapper.set("is_face", false);
            faceMapper.update(updateWrapper);
            return;
        }

        QueryWrapper<Person> personQuery = new QueryWrapper<>();
        personQuery.eq("user_id", userId);
        List<Person> personList = personMapper.selectList(personQuery);

        SelectedFaceCandidate selectedFaceCandidate = selectFaceCandidate(
                userId,
                faceId,
                analyzeResult.getFaces(),
                personList
        );
        if (selectedFaceCandidate == null) {
            updateWrapper.set("is_face", false);
            faceMapper.update(updateWrapper);
            return;
        }

        FaceCandidate primaryFace = selectedFaceCandidate.getCandidate();
        byte[] faceCoverBytes = cropFaceCover(fileBytes, primaryFace.getBoundingBox());
        if (faceCoverBytes == null || faceCoverBytes.length == 0) {
            updateWrapper.set("is_face", false);
            faceMapper.update(updateWrapper);
            return;
        }

        String objectName = "face/" + localDate + "/" + UUID.randomUUID().toString().replace("-", "") + ".png";
        minioOSSUtil.uploadToOSS(
                objectName,
                new ByteArrayInputStream(faceCoverBytes),
                faceCoverBytes.length,
                "image/png"
        );
        updateWrapper.set("is_face", true);
        updateWrapper.set("person_object_name", objectName);
        updateWrapper.set("person_cover_url", minioOSSUtil.getFileUrl(objectName));

        float[] featureVector = primaryFace.getFeatureVector();
        if (featureVector == null || featureVector.length == 0) {
            faceMapper.update(updateWrapper);
            return;
        }
        updateWrapper.set("feature_vector", CosineSimilarityUtil.floatsToBytes(featureVector));
        updateWrapper.set("feature_dim", featureVector.length);
        updateWrapper.set("feature_model", featureModel);
        updateWrapper.set("feature_provider", featureProvider);
        updateWrapper.set("detect_provider", faceDetectProvider);
        updateWrapper.set("bbox_json", primaryFace.getBoundingBox().toJson());
        updateWrapper.set("quality_score", primaryFace.getQualityScore());
        faceMapper.update(updateWrapper);

        Long targetPersonId;
        ClusterMatchResult bestMatch = selectedFaceCandidate.getMatchResult();
        if (bestMatch != null && bestMatch.isMatched()) {
            PersonFace personFace = new PersonFace();
            personFace.setUserId(userId);
            personFace.setPersonId(bestMatch.getPersonId());
            personFace.setFaceId(faceId);
            personFace.setRepresentative(false);
            personFace.setUpdateTime(LocalDateTime.now());
            personFaceMapper.insert(personFace);
            log.info("Unified face cluster matched: faceId={}, personId={}, score={}, prototypeSimilarity={}, bestSampleSimilarity={}, supportCount={}, matchedSamples={}, threshold={}",
                    faceId,
                    bestMatch.getPersonId(),
                    bestMatch.getFinalScore(),
                    bestMatch.getPrototypeSimilarity(),
                    bestMatch.getBestSampleSimilarity(),
                    bestMatch.getSupportCount(),
                    bestMatch.getSampleCount(),
                    faceCosineThreshold);
            targetPersonId = bestMatch.getPersonId();
        } else {
            Person newPerson = new Person();
            newPerson.setUserId(userId);
            newPerson.setDisplay(true);
            newPerson.setCreateTime(LocalDateTime.now());
            personMapper.insert(newPerson);

            PersonFace personFace = new PersonFace();
            personFace.setUserId(userId);
            personFace.setPersonId(newPerson.getPersonId());
            personFace.setFaceId(faceId);
            personFace.setRepresentative(true);
            personFace.setUpdateTime(LocalDateTime.now());
            personFaceMapper.insert(personFace);
            targetPersonId = newPerson.getPersonId();
        }

        Long reconciledPersonId = reconcileClusterAfterInsert(userId, targetPersonId);
        log.info("Unified face cluster reconciled: faceId={}, personId={}", faceId, reconciledPersonId);
    }

    private byte[] loadFaceSourceBytes(FileEntity fileEntity) {
        if (fileEntity == null) {
            return null;
        }

        if ("image".equalsIgnoreCase(fileEntity.getCategory())) {
            String fileObjectName = fileEntity.getFileObjectName();
            if (fileObjectName != null && !fileObjectName.isBlank()) {
                byte[] originalBytes = minioOSSUtil.getFileBytes(fileObjectName);
                if (originalBytes != null && originalBytes.length > 0) {
                    return originalBytes;
                }
            }
        }

        String thumbnailObjectName = fileEntity.getThumbnailObjectName();
        if (thumbnailObjectName != null && !thumbnailObjectName.isBlank()) {
            byte[] thumbnailBytes = minioOSSUtil.getFileBytes(thumbnailObjectName);
            if (thumbnailBytes != null && thumbnailBytes.length > 0) {
                return thumbnailBytes;
            }
        }

        return null;
    }

    private SelectedFaceCandidate selectFaceCandidate(
            Long userId,
            Long faceId,
            List<FaceCandidate> candidates,
            List<Person> personList
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        int candidateLimit = Math.min(FACE_SELECTION_CANDIDATE_LIMIT, candidates.size());
        FaceCandidate fallbackCandidate = candidates.get(0);
        if (personList == null || personList.isEmpty()) {
            return new SelectedFaceCandidate(fallbackCandidate, null);
        }

        SelectedFaceCandidate bestSelection = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int index = 0; index < candidateLimit; index++) {
            FaceCandidate candidate = candidates.get(index);
            float[] candidateVector = candidate.getFeatureVector();
            if (candidateVector == null || candidateVector.length == 0) {
                continue;
            }

            ClusterMatchResult matchResult = findBestPersonMatch(userId, faceId, candidateVector, personList);
            if (matchResult == null || !matchResult.isMatched()) {
                continue;
            }

            double rankPenalty = index * 0.01D;
            double candidateScore = matchResult.getFinalScore() + (candidate.getConfidence() * 0.02D) - rankPenalty;
            if (bestSelection == null || candidateScore > bestScore) {
                bestSelection = new SelectedFaceCandidate(candidate, matchResult);
                bestScore = candidateScore;
            }
        }

        if (bestSelection != null) {
            return bestSelection;
        }
        return new SelectedFaceCandidate(fallbackCandidate, null);
    }

    private double adaptiveClusterMatchThreshold(int sampleCount) {
        if (sampleCount <= 1) {
            return Math.max(faceCosineThreshold - 0.04D, 0.44D);
        }
        if (sampleCount == 2) {
            return Math.max(faceCosineThreshold - 0.03D, 0.44D);
        }
        if (sampleCount == 3) {
            return Math.max(faceCosineThreshold - 0.01D, 0.45D);
        }
        return faceCosineThreshold;
    }

    private double adaptiveClusterPrototypeThreshold(int sampleCount) {
        if (sampleCount <= 1) {
            return Math.max(faceCosineThreshold - 0.05D, 0.42D);
        }
        if (sampleCount == 2) {
            return Math.max(faceCosineThreshold - 0.04D, 0.43D);
        }
        if (sampleCount == 3) {
            return Math.max(faceCosineThreshold - 0.03D, 0.44D);
        }
        return Math.max(faceCosineThreshold - 0.02D, 0.45D);
    }

    private double adaptiveClusterMergeThreshold(int leftFaceCount, int rightFaceCount) {
        int smallerClusterFaceCount = Math.min(leftFaceCount, rightFaceCount);
        if (smallerClusterFaceCount <= 1) {
            return Math.max(faceCosineThreshold, 0.46D);
        }
        if (smallerClusterFaceCount == 2) {
            return Math.max(faceCosineThreshold + 0.01D, 0.47D);
        }
        return Math.max(faceCosineThreshold + 0.02D, 0.48D);
    }

    private Long reconcileClusterAfterInsert(Long userId, Long personId) {
        refreshRepresentativeFaces(userId, personId);
        Long mergedPersonId = mergeNearbyClusters(userId, personId);
        refreshRepresentativeFaces(userId, mergedPersonId);
        ClusterMergeExecutionResult mergeResult = mergeConnectedClusters(userId, mergedPersonId);
        mergedPersonId = mergeResult.getPreferredPersonId();
        refreshRepresentativeFaces(userId, mergedPersonId);
        return mergedPersonId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reclusterUserFaces(Long userId) {
        QueryWrapper<Person> personQuery = new QueryWrapper<>();
        personQuery.eq("user_id", userId);
        List<Person> persons = personMapper.selectList(personQuery);
        for (Person person : persons) {
            refreshRepresentativeFaces(userId, person.getPersonId());
        }
        return mergeConnectedClusters(userId, null).getMergedPersonCount();
    }

    private ClusterMatchResult findBestPersonMatch(Long userId, Long faceId, float[] featureVector, List<Person> personList) {
        ClusterMatchResult bestMatch = null;
        for (Person person : personList) {
            ClusterMatchResult candidate = evaluatePersonCluster(userId, faceId, featureVector, person.getPersonId());
            if (candidate == null) {
                continue;
            }
            if (bestMatch == null || candidate.getFinalScore() > bestMatch.getFinalScore()) {
                bestMatch = candidate;
            }
        }
        return bestMatch;
    }

    private ClusterMatchResult evaluatePersonCluster(Long userId, Long faceId, float[] featureVector, Long personId) {
        List<FaceSample> candidateSamples = loadCandidateSamples(userId, personId, PERSON_CLUSTER_SAMPLE_LIMIT);
        if (candidateSamples.isEmpty()) {
            return null;
        }

        int sampleCount = candidateSamples.size();
        double bestSampleSimilarity = Double.NEGATIVE_INFINITY;
        Long bestSampleFaceId = null;
        int supportCount = 0;
        double matchThreshold = adaptiveClusterMatchThreshold(sampleCount);
        double prototypeThreshold = adaptiveClusterPrototypeThreshold(sampleCount);
        double supportThreshold = Math.max(matchThreshold - 0.03D, 0.43D);

        for (FaceSample sample : candidateSamples) {
            double sampleSimilarity = CosineSimilarityUtil.cosine(featureVector, sample.getVector());
            if (sampleSimilarity > bestSampleSimilarity) {
                bestSampleSimilarity = sampleSimilarity;
                bestSampleFaceId = sample.getFaceId();
            }
            if (sampleSimilarity >= supportThreshold) {
                supportCount++;
            }
        }

        float[] prototypeVector = buildClusterPrototypeFromSamples(candidateSamples);
        double prototypeSimilarity = CosineSimilarityUtil.cosine(featureVector, prototypeVector);
        double finalScore = Math.max(bestSampleSimilarity, prototypeSimilarity + Math.min(0.03D, supportCount * 0.01D));
        boolean matched = bestSampleSimilarity >= matchThreshold
                || (prototypeSimilarity >= prototypeThreshold
                && (supportCount >= 2
                || sampleCount <= 2
                || bestSampleSimilarity >= matchThreshold - 0.02D));

        log.info("Unified face cluster candidate: faceId={}, personId={}, bestSampleFaceId={}, prototypeSimilarity={}, bestSampleSimilarity={}, supportCount={}, finalScore={}, sampleCount={}, threshold={}, prototypeThreshold={}",
                faceId,
                personId,
                prototypeSimilarity,
                bestSampleSimilarity,
                supportCount,
                finalScore,
                sampleCount,
                matchThreshold,
                prototypeThreshold);

        return new ClusterMatchResult(
                personId,
                prototypeSimilarity,
                bestSampleSimilarity,
                finalScore,
                supportCount,
                sampleCount,
                matched
        );
    }

    private Long mergeNearbyClusters(Long userId, Long anchorPersonId) {
        Long currentPersonId = anchorPersonId;
        boolean merged;
        do {
            merged = false;
            ClusterSummary anchorSummary = buildClusterSummary(userId, currentPersonId);
            if (anchorSummary == null) {
                return currentPersonId;
            }

            QueryWrapper<Person> personQuery = new QueryWrapper<>();
            personQuery.eq("user_id", userId);
            personQuery.ne("person_id", currentPersonId);
            List<Person> otherPersons = personMapper.selectList(personQuery);

            for (Person otherPerson : otherPersons) {
                ClusterSummary otherSummary = buildClusterSummary(userId, otherPerson.getPersonId());
                if (otherSummary == null) {
                    continue;
                }

                if (!shouldMergeClusterSummaries(anchorSummary, otherSummary)) {
                    continue;
                }

                Long survivorPersonId = anchorSummary.getFaceCount() >= otherSummary.getFaceCount()
                        ? anchorSummary.getPersonId()
                        : otherSummary.getPersonId();
                Long mergePersonId = survivorPersonId.equals(anchorSummary.getPersonId())
                        ? otherSummary.getPersonId()
                        : anchorSummary.getPersonId();

                personMapper.updatePersonFaces(userId, survivorPersonId, Collections.singletonList(mergePersonId));
                personMapper.deletePersonById(userId, Collections.singletonList(mergePersonId));
                refreshRepresentativeFaces(userId, survivorPersonId);

                log.info("Unified face cluster merged: userId={}, survivorPersonId={}, mergePersonId={}",
                        userId, survivorPersonId, mergePersonId);

                currentPersonId = survivorPersonId;
                merged = true;
                break;
            }
        } while (merged);

        return currentPersonId;
    }

    private ClusterMergeExecutionResult mergeConnectedClusters(Long userId, Long preferredPersonId) {
        int mergedPersonCount = 0;
        Long currentPreferredPersonId = preferredPersonId;

        while (true) {
            QueryWrapper<Person> personQuery = new QueryWrapper<>();
            personQuery.eq("user_id", userId);
            List<Person> persons = personMapper.selectList(personQuery);
            if (persons.size() < 2) {
                return new ClusterMergeExecutionResult(currentPreferredPersonId, mergedPersonCount);
            }

            Map<Long, Person> personMap = new HashMap<>();
            List<ClusterSummary> summaries = new ArrayList<>();
            for (Person person : persons) {
                personMap.put(person.getPersonId(), person);
                ClusterSummary summary = buildClusterSummary(userId, person.getPersonId());
                if (summary != null) {
                    summaries.add(summary);
                }
            }
            if (summaries.size() < 2) {
                return new ClusterMergeExecutionResult(currentPreferredPersonId, mergedPersonCount);
            }

            DisjointSet disjointSet = new DisjointSet(summaries.size());
            for (int i = 0; i < summaries.size(); i++) {
                for (int j = i + 1; j < summaries.size(); j++) {
                    if (shouldMergeClusterSummaries(summaries.get(i), summaries.get(j))) {
                        disjointSet.union(i, j);
                    }
                }
            }

            Map<Integer, List<ClusterSummary>> components = new HashMap<>();
            for (int i = 0; i < summaries.size(); i++) {
                int root = disjointSet.find(i);
                components.computeIfAbsent(root, key -> new ArrayList<>()).add(summaries.get(i));
            }

            boolean mergedThisRound = false;
            for (List<ClusterSummary> component : components.values()) {
                if (component.size() < 2) {
                    continue;
                }

                Person survivor = chooseSurvivorPerson(component, personMap, currentPreferredPersonId);
                if (survivor == null) {
                    continue;
                }

                List<Long> mergePersonIds = new ArrayList<>();
                List<Person> mergedPersons = new ArrayList<>();
                for (ClusterSummary summary : component) {
                    if (summary.getPersonId().equals(survivor.getPersonId())) {
                        continue;
                    }
                    mergePersonIds.add(summary.getPersonId());
                    Person mergedPerson = personMap.get(summary.getPersonId());
                    if (mergedPerson != null) {
                        mergedPersons.add(mergedPerson);
                    }
                }
                if (mergePersonIds.isEmpty()) {
                    continue;
                }

                inheritPersonMetadata(survivor, mergedPersons);
                personMapper.updatePersonFaces(userId, survivor.getPersonId(), mergePersonIds);
                personMapper.deletePersonById(userId, mergePersonIds);
                refreshRepresentativeFaces(userId, survivor.getPersonId());

                if (currentPreferredPersonId != null) {
                    if (currentPreferredPersonId.equals(survivor.getPersonId()) || mergePersonIds.contains(currentPreferredPersonId)) {
                        currentPreferredPersonId = survivor.getPersonId();
                    }
                }

                mergedPersonCount += mergePersonIds.size();
                mergedThisRound = true;
                log.info("Unified face cluster global merge: userId={}, survivorPersonId={}, mergedPersonIds={}",
                        userId, survivor.getPersonId(), mergePersonIds);
            }

            if (!mergedThisRound) {
                return new ClusterMergeExecutionResult(currentPreferredPersonId, mergedPersonCount);
            }
        }
    }

    private boolean shouldMergeClusterSummaries(ClusterSummary leftSummary, ClusterSummary rightSummary) {
        int smallerClusterFaceCount = Math.min(leftSummary.getFaceCount(), rightSummary.getFaceCount());
        double mergeThreshold = adaptiveClusterMergeThreshold(leftSummary.getFaceCount(), rightSummary.getFaceCount());
        double prototypeMergeThreshold = Math.max(mergeThreshold - 0.02D, adaptiveClusterPrototypeThreshold(smallerClusterFaceCount));
        double bestCrossSimilarity = computeBestCrossSimilarity(leftSummary.getCandidateSamples(), rightSummary.getCandidateSamples());
        int crossSupportCount = countCrossSupport(leftSummary.getCandidateSamples(), rightSummary.getCandidateSamples(),
                Math.max(mergeThreshold - 0.03D, 0.47D));
        double prototypeSimilarity = CosineSimilarityUtil.cosine(leftSummary.getPrototypeVector(), rightSummary.getPrototypeVector());

        return bestCrossSimilarity >= mergeThreshold
                || (prototypeSimilarity >= prototypeMergeThreshold && crossSupportCount >= 1)
                || (smallerClusterFaceCount <= 2
                && bestCrossSimilarity >= mergeThreshold - 0.02D
                && prototypeSimilarity >= prototypeMergeThreshold);
    }

    private Person chooseSurvivorPerson(List<ClusterSummary> component, Map<Long, Person> personMap, Long preferredPersonId) {
        Person survivor = null;
        for (ClusterSummary summary : component) {
            Person candidate = personMap.get(summary.getPersonId());
            if (candidate == null) {
                continue;
            }
            if (survivor == null) {
                survivor = candidate;
                continue;
            }
            if (isPreferredSurvivor(summary, candidate, findSummary(component, survivor.getPersonId()), survivor, preferredPersonId)) {
                survivor = candidate;
            }
        }
        return survivor;
    }

    private ClusterSummary findSummary(List<ClusterSummary> component, Long personId) {
        for (ClusterSummary summary : component) {
            if (summary.getPersonId().equals(personId)) {
                return summary;
            }
        }
        return null;
    }

    private boolean isPreferredSurvivor(
            ClusterSummary candidateSummary,
            Person candidatePerson,
            ClusterSummary currentSummary,
            Person currentPerson,
            Long preferredPersonId
    ) {
        if (preferredPersonId != null) {
            boolean candidatePreferred = candidatePerson.getPersonId().equals(preferredPersonId);
            boolean currentPreferred = currentPerson.getPersonId().equals(preferredPersonId);
            if (candidatePreferred != currentPreferred) {
                return candidatePreferred;
            }
        }

        boolean candidateHasMetadata = hasPersonMetadata(candidatePerson);
        boolean currentHasMetadata = hasPersonMetadata(currentPerson);
        if (candidateHasMetadata != currentHasMetadata) {
            return candidateHasMetadata;
        }

        int candidateFaceCount = candidateSummary == null ? 0 : candidateSummary.getFaceCount();
        int currentFaceCount = currentSummary == null ? 0 : currentSummary.getFaceCount();
        if (candidateFaceCount != currentFaceCount) {
            return candidateFaceCount > currentFaceCount;
        }

        return candidatePerson.getPersonId() < currentPerson.getPersonId();
    }

    private boolean hasPersonMetadata(Person person) {
        return person != null
                && ((person.getPersonName() != null && !person.getPersonName().isBlank())
                || (person.getPersonRelation() != null && !person.getPersonRelation().isBlank()));
    }

    private void inheritPersonMetadata(Person survivor, List<Person> mergedPersons) {
        if (survivor == null || mergedPersons == null || mergedPersons.isEmpty()) {
            return;
        }

        boolean updated = false;
        if (survivor.getPersonName() == null || survivor.getPersonName().isBlank()) {
            for (Person mergedPerson : mergedPersons) {
                if (mergedPerson.getPersonName() != null && !mergedPerson.getPersonName().isBlank()) {
                    survivor.setPersonName(mergedPerson.getPersonName());
                    updated = true;
                    break;
                }
            }
        }
        if (survivor.getPersonRelation() == null || survivor.getPersonRelation().isBlank()) {
            for (Person mergedPerson : mergedPersons) {
                if (mergedPerson.getPersonRelation() != null && !mergedPerson.getPersonRelation().isBlank()) {
                    survivor.setPersonRelation(mergedPerson.getPersonRelation());
                    updated = true;
                    break;
                }
            }
        }
        if (updated) {
            personMapper.updateById(survivor);
        }
    }

    private void refreshRepresentativeFaces(Long userId, Long personId) {
        List<FaceSample> candidateSamples = loadCandidateSamples(userId, personId, PERSON_CLUSTER_SAMPLE_LIMIT);
        if (candidateSamples.isEmpty()) {
            return;
        }

        List<FaceSample> representativeSamples = pickRepresentativeSamples(candidateSamples);

        UpdateWrapper<PersonFace> clearWrapper = new UpdateWrapper<>();
        clearWrapper.eq("user_id", userId);
        clearWrapper.eq("person_id", personId);
        clearWrapper.set("representative", false);
        personFaceMapper.update(clearWrapper);

        for (FaceSample sample : representativeSamples) {
            UpdateWrapper<PersonFace> representativeWrapper = new UpdateWrapper<>();
            representativeWrapper.eq("user_id", userId);
            representativeWrapper.eq("person_id", personId);
            representativeWrapper.eq("face_id", sample.getFaceId());
            representativeWrapper.set("representative", true);
            personFaceMapper.update(representativeWrapper);
        }
    }

    private ClusterSummary buildClusterSummary(Long userId, Long personId) {
        List<FaceSample> candidateSamples = loadCandidateSamples(userId, personId, PERSON_CLUSTER_SAMPLE_LIMIT);
        if (candidateSamples.isEmpty()) {
            return null;
        }
        List<FaceSample> representativeSamples = loadRepresentativeSamples(userId, personId);
        if (representativeSamples.isEmpty()) {
            representativeSamples = pickRepresentativeSamples(candidateSamples);
        }

        QueryWrapper<PersonFace> countQuery = new QueryWrapper<>();
        countQuery.eq("user_id", userId);
        countQuery.eq("person_id", personId);
        Long faceCountValue = personFaceMapper.selectCount(countQuery);
        int faceCount = faceCountValue == null ? 0 : faceCountValue.intValue();

        return new ClusterSummary(
                personId,
                representativeSamples,
                candidateSamples,
                buildClusterPrototypeFromSamples(candidateSamples),
                faceCount
        );
    }

    private List<FaceSample> loadRepresentativeSamples(Long userId, Long personId) {
        List<FaceSample> candidateSamples = loadCandidateSamples(userId, personId, PERSON_CLUSTER_SAMPLE_LIMIT);
        if (candidateSamples.isEmpty()) {
            return new ArrayList<>();
        }

        QueryWrapper<PersonFace> representativeQuery = new QueryWrapper<>();
        representativeQuery.eq("user_id", userId);
        representativeQuery.eq("person_id", personId);
        representativeQuery.eq("representative", true);
        representativeQuery.orderByDesc("update_time");
        List<PersonFace> representativeLinks = personFaceMapper.selectList(representativeQuery);
        List<FaceSample> representativeSamples = loadFaceSamplesByLinks(representativeLinks, MAX_REPRESENTATIVE_FACES);
        if (representativeSamples.size() >= Math.min(MIN_REPRESENTATIVE_FACES, candidateSamples.size())) {
            return representativeSamples;
        }
        return pickRepresentativeSamples(candidateSamples);
    }

    private List<FaceSample> loadCandidateSamples(Long userId, Long personId, int limit) {
        List<Face> clusterFaces = personMapper.selectRecentFacesForCluster(userId, personId, limit);
        List<FaceSample> samples = new ArrayList<>();
        if (clusterFaces == null) {
            return samples;
        }

        for (int i = 0; i < clusterFaces.size(); i++) {
            FaceSample sample = toFaceSample(clusterFaces.get(i), i);
            if (sample != null) {
                samples.add(sample);
            }
        }
        return samples;
    }

    private List<FaceSample> loadFaceSamplesByLinks(List<PersonFace> links, int limit) {
        List<FaceSample> samples = new ArrayList<>();
        if (links == null || links.isEmpty()) {
            return samples;
        }

        List<Long> faceIds = new ArrayList<>();
        int max = Math.min(limit, links.size());
        for (int i = 0; i < max; i++) {
            faceIds.add(links.get(i).getFaceId());
        }

        List<Face> faces = faceMapper.selectBatchIds(faceIds);
        Map<Long, Face> faceMap = new HashMap<>();
        for (Face face : faces) {
            faceMap.put(face.getFaceId(), face);
        }

        for (int i = 0; i < faceIds.size(); i++) {
            FaceSample sample = toFaceSample(faceMap.get(faceIds.get(i)), i);
            if (sample != null) {
                samples.add(sample);
            }
        }
        return samples;
    }

    private List<FaceSample> pickRepresentativeSamples(List<FaceSample> candidates) {
        List<FaceSample> selected = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return selected;
        }
        if (candidates.size() <= MAX_REPRESENTATIVE_FACES) {
            selected.addAll(candidates);
            return selected;
        }

        float[] clusterPrototype = buildClusterPrototypeFromSamples(candidates);
        int targetCount = Math.min(MAX_REPRESENTATIVE_FACES, candidates.size());
        int minimumCount = Math.min(MIN_REPRESENTATIVE_FACES, targetCount);

        while (selected.size() < targetCount) {
            FaceSample bestCandidate = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (FaceSample candidate : candidates) {
                if (containsFaceSample(selected, candidate.getFaceId())) {
                    continue;
                }

                double prototypeSimilarity = CosineSimilarityUtil.cosine(candidate.getVector(), clusterPrototype);
                double maxSelectedSimilarity = selected.isEmpty() ? 0D : computeBestSimilarity(candidate.getVector(), selected);
                double recencyBoost = 0.03D * ((double) (candidates.size() - candidate.getRank()) / candidates.size());
                double score = prototypeSimilarity - (selected.isEmpty() ? 0D : 0.18D * maxSelectedSimilarity) + recencyBoost;

                if (selected.size() >= minimumCount && maxSelectedSimilarity > 0.98D) {
                    score -= 0.03D;
                }

                if (score > bestScore) {
                    bestScore = score;
                    bestCandidate = candidate;
                }
            }

            if (bestCandidate == null) {
                break;
            }
            selected.add(bestCandidate);
        }

        return selected;
    }

    private boolean containsFaceSample(List<FaceSample> samples, Long faceId) {
        for (FaceSample sample : samples) {
            if (sample.getFaceId().equals(faceId)) {
                return true;
            }
        }
        return false;
    }

    private FaceSample toFaceSample(Face face, int rank) {
        if (face == null || !isValidFeatureBytes(face)) {
            return null;
        }
        return new FaceSample(face.getFaceId(), CosineSimilarityUtil.bytesToFloats(face.getFeatureVector()), rank);
    }

    private boolean isValidFeatureBytes(Face face) {
        byte[] featureBytes = face.getFeatureVector();
        Integer storedDim = face.getFeatureDim();
        return featureBytes != null
                && storedDim != null
                && storedDim > 0
                && featureBytes.length == storedDim * Float.BYTES;
    }

    private float[] buildClusterPrototypeFromSamples(List<FaceSample> samples) {
        List<float[]> vectors = new ArrayList<>(samples.size());
        for (FaceSample sample : samples) {
            vectors.add(sample.getVector());
        }
        return buildClusterPrototype(vectors);
    }

    private float[] buildClusterPrototype(List<float[]> sampleVectors) {
        float[] prototype = new float[sampleVectors.get(0).length];
        for (float[] sampleVector : sampleVectors) {
            for (int i = 0; i < prototype.length; i++) {
                prototype[i] += sampleVector[i];
            }
        }

        float sampleCount = sampleVectors.size();
        double norm = 0D;
        for (int i = 0; i < prototype.length; i++) {
            prototype[i] /= sampleCount;
            norm += prototype[i] * prototype[i];
        }

        if (norm == 0D) {
            return prototype;
        }

        float invNorm = (float) (1D / Math.sqrt(norm));
        for (int i = 0; i < prototype.length; i++) {
            prototype[i] *= invNorm;
        }
        return prototype;
    }

    private double computeBestSimilarity(float[] featureVector, List<FaceSample> samples) {
        double bestSimilarity = Double.NEGATIVE_INFINITY;
        for (FaceSample sample : samples) {
            bestSimilarity = Math.max(bestSimilarity, CosineSimilarityUtil.cosine(featureVector, sample.getVector()));
        }
        return bestSimilarity;
    }

    private double computeBestCrossSimilarity(List<FaceSample> leftSamples, List<FaceSample> rightSamples) {
        double bestSimilarity = Double.NEGATIVE_INFINITY;
        for (FaceSample leftSample : leftSamples) {
            for (FaceSample rightSample : rightSamples) {
                bestSimilarity = Math.max(bestSimilarity,
                        CosineSimilarityUtil.cosine(leftSample.getVector(), rightSample.getVector()));
            }
        }
        return bestSimilarity;
    }

    private int countCrossSupport(List<FaceSample> leftSamples, List<FaceSample> rightSamples, double threshold) {
        int supportCount = 0;
        for (FaceSample leftSample : leftSamples) {
            for (FaceSample rightSample : rightSamples) {
                if (CosineSimilarityUtil.cosine(leftSample.getVector(), rightSample.getVector()) >= threshold) {
                    supportCount++;
                }
            }
        }
        return supportCount;
    }

    @SuppressWarnings("unchecked")
    private FaceAnalyzeResult callFaceAnalyze(byte[] imageBytes, Long faceId) {
        String url = aiServiceUrl + "/face_analyze";
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "face_source_" + faceId + ".png";
                }
            });

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = externalServiceExecutor.execute(
                    ExternalServiceExecutor.AI,
                    () -> restTemplate.postForEntity(url, requestEntity, Map.class)
            );
            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                throw new IllegalStateException("AI service returned HTTP " + response.getStatusCode());
            }

            Map<String, Object> responseBody = response.getBody();
            if (responseBody.containsKey("error")) {
                throw new IllegalStateException("AI service returned error: " + responseBody.get("error"));
            }

            List<Map<String, Object>> faceItems = (List<Map<String, Object>>) responseBody.get("faces");
            List<FaceCandidate> faces = new ArrayList<>();
            if (faceItems != null) {
                for (Map<String, Object> item : faceItems) {
                    Map<String, Object> bboxMap = (Map<String, Object>) item.get("bbox");
                    List<Number> featureList = (List<Number>) item.get("feature");
                    if (bboxMap == null || featureList == null || featureList.isEmpty()) {
                        continue;
                    }

                    FaceBoundingBox boundingBox = new FaceBoundingBox(
                            toInt(bboxMap.get("x1")),
                            toInt(bboxMap.get("y1")),
                            toInt(bboxMap.get("x2")),
                            toInt(bboxMap.get("y2"))
                    );
                    faces.add(new FaceCandidate(
                            boundingBox,
                            toDouble(item.get("confidence")),
                            toFloatArray(featureList),
                            toDouble(item.get("qualityScore"))
                    ));
                }
            }
            return new FaceAnalyzeResult(faces);
        } catch (Exception e) {
            log.error("调用 AI 服务 /face_analyze 失败: faceId={}, error={}", faceId, e.getMessage(), e);
            throw new IllegalStateException("AI face analysis failed: " + e.getMessage(), e);
        }
    }

    private byte[] cropFaceCover(byte[] imageBytes, FaceBoundingBox boundingBox) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return null;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            int x1 = clamp(boundingBox.getX1(), 0, Math.max(0, width - 1));
            int y1 = clamp(boundingBox.getY1(), 0, Math.max(0, height - 1));
            int x2 = clamp(boundingBox.getX2(), x1 + 1, width);
            int y2 = clamp(boundingBox.getY2(), y1 + 1, height);

            BufferedImage cropped = image.getSubimage(x1, y1, x2 - x1, y2 - y1);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Thumbnails.of(cropped)
                    .size(FACE_COVER_SIZE, FACE_COVER_SIZE)
                    .outputFormat("png")
                    .toOutputStream(outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            log.error("裁剪人脸封面失败: {}", e.getMessage());
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0D;
    }

    private float[] toFloatArray(List<Number> values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).floatValue();
        }
        return vector;
    }

    private static class FaceAnalyzeResult {
        private final List<FaceCandidate> faces;

        private FaceAnalyzeResult(List<FaceCandidate> faces) {
            this.faces = faces;
        }

        public List<FaceCandidate> getFaces() {
            return faces;
        }
    }

    private static class FaceCandidate {
        private final FaceBoundingBox boundingBox;
        private final double confidence;
        private final float[] featureVector;
        private final double qualityScore;

        private FaceCandidate(FaceBoundingBox boundingBox, double confidence, float[] featureVector, double qualityScore) {
            this.boundingBox = boundingBox;
            this.confidence = confidence;
            this.featureVector = featureVector;
            this.qualityScore = qualityScore;
        }

        public FaceBoundingBox getBoundingBox() {
            return boundingBox;
        }

        public double getConfidence() {
            return confidence;
        }

        public float[] getFeatureVector() {
            return featureVector;
        }

        public double getQualityScore() {
            return qualityScore;
        }
    }

    private static class FaceBoundingBox {
        private final int x1;
        private final int y1;
        private final int x2;
        private final int y2;

        private FaceBoundingBox(int x1, int y1, int x2, int y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        public int getX1() {
            return x1;
        }

        public int getY1() {
            return y1;
        }

        public int getX2() {
            return x2;
        }

        public int getY2() {
            return y2;
        }

        public String toJson() {
            return String.format("{\"x1\":%d,\"y1\":%d,\"x2\":%d,\"y2\":%d}", x1, y1, x2, y2);
        }
    }

    private static class FaceSample {
        private final Long faceId;
        private final float[] vector;
        private final int rank;

        private FaceSample(Long faceId, float[] vector, int rank) {
            this.faceId = faceId;
            this.vector = vector;
            this.rank = rank;
        }

        public Long getFaceId() {
            return faceId;
        }

        public float[] getVector() {
            return vector;
        }

        public int getRank() {
            return rank;
        }
    }

    private static class ClusterSummary {
        private final Long personId;
        private final List<FaceSample> representativeSamples;
        private final List<FaceSample> candidateSamples;
        private final float[] prototypeVector;
        private final int faceCount;

        private ClusterSummary(
                Long personId,
                List<FaceSample> representativeSamples,
                List<FaceSample> candidateSamples,
                float[] prototypeVector,
                int faceCount
        ) {
            this.personId = personId;
            this.representativeSamples = representativeSamples;
            this.candidateSamples = candidateSamples;
            this.prototypeVector = prototypeVector;
            this.faceCount = faceCount;
        }

        public Long getPersonId() {
            return personId;
        }

        public List<FaceSample> getCandidateSamples() {
            return candidateSamples;
        }

        public float[] getPrototypeVector() {
            return prototypeVector;
        }

        public int getFaceCount() {
            return faceCount;
        }

    }

    private static class ClusterMatchResult {
        private final Long personId;
        private final double prototypeSimilarity;
        private final double bestSampleSimilarity;
        private final double finalScore;
        private final int supportCount;
        private final int sampleCount;
        private final boolean matched;

        private ClusterMatchResult(Long personId,
                                   double prototypeSimilarity,
                                   double bestSampleSimilarity,
                                   double finalScore,
                                   int supportCount,
                                   int sampleCount,
                                   boolean matched) {
            this.personId = personId;
            this.prototypeSimilarity = prototypeSimilarity;
            this.bestSampleSimilarity = bestSampleSimilarity;
            this.finalScore = finalScore;
            this.supportCount = supportCount;
            this.sampleCount = sampleCount;
            this.matched = matched;
        }

        public Long getPersonId() {
            return personId;
        }

        public double getPrototypeSimilarity() {
            return prototypeSimilarity;
        }

        public double getBestSampleSimilarity() {
            return bestSampleSimilarity;
        }

        public double getFinalScore() {
            return finalScore;
        }

        public int getSupportCount() {
            return supportCount;
        }

        public int getSampleCount() {
            return sampleCount;
        }

        public boolean isMatched() {
            return matched;
        }
    }

    private static class DisjointSet {
        private final int[] parent;
        private final int[] rank;

        private DisjointSet(int size) {
            this.parent = new int[size];
            this.rank = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        private int find(int index) {
            if (parent[index] != index) {
                parent[index] = find(parent[index]);
            }
            return parent[index];
        }

        private void union(int left, int right) {
            int leftRoot = find(left);
            int rightRoot = find(right);
            if (leftRoot == rightRoot) {
                return;
            }
            if (rank[leftRoot] < rank[rightRoot]) {
                parent[leftRoot] = rightRoot;
            } else if (rank[leftRoot] > rank[rightRoot]) {
                parent[rightRoot] = leftRoot;
            } else {
                parent[rightRoot] = leftRoot;
                rank[leftRoot]++;
            }
        }
    }

    private static class ClusterMergeExecutionResult {
        private final Long preferredPersonId;
        private final int mergedPersonCount;

        private ClusterMergeExecutionResult(Long preferredPersonId, int mergedPersonCount) {
            this.preferredPersonId = preferredPersonId;
            this.mergedPersonCount = mergedPersonCount;
        }

        public Long getPreferredPersonId() {
            return preferredPersonId;
        }

        public int getMergedPersonCount() {
            return mergedPersonCount;
        }
    }

    private static class SelectedFaceCandidate {
        private final FaceCandidate candidate;
        private final ClusterMatchResult matchResult;

        private SelectedFaceCandidate(FaceCandidate candidate, ClusterMatchResult matchResult) {
            this.candidate = candidate;
            this.matchResult = matchResult;
        }

        public FaceCandidate getCandidate() {
            return candidate;
        }

        public ClusterMatchResult getMatchResult() {
            return matchResult;
        }
    }
}


