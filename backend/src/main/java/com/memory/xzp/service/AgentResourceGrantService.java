package com.memory.xzp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.mapper.AgentResourceGrantMapper;
import com.memory.xzp.model.dto.DownLoadInfoDTO;
import com.memory.xzp.model.entity.AgentResourceGrant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/** The database transaction is the sole source of truth, including for cache outages/replays. */
@Service
public class AgentResourceGrantService {
    private final AgentResourceGrantMapper mapper;
    private final ObjectMapper json;
    private static final SecureRandom RANDOM = new SecureRandom();
    public AgentResourceGrantService(AgentResourceGrantMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Transactional
    public String issue(Long userId, List<String> fileIds, String kind, int minutes) {
        if (userId == null || fileIds == null || fileIds.isEmpty() || fileIds.size() > 500
                || !Set.of("share", "download").contains(kind) || minutes < 1 || minutes > 43200) {
            throw new BusinessException(StatusCode.PARAMS_ERROR, "资源授权参数无效");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "ag_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AgentResourceGrant grant = new AgentResourceGrant();
        grant.setTokenHash(hash(token));
        grant.setUserId(userId);
        grant.setKind(kind);
        grant.setExpiresAt(LocalDateTime.now().plusMinutes(minutes));
        try { grant.setFileIdsJson(json.writeValueAsString(fileIds.stream().distinct().toList())); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize resource grant", e); }
        if (mapper.insert(grant) != 1) throw new IllegalStateException("Cannot persist resource grant");
        return token;
    }

    public AgentResourceGrant require(String token, String kind) {
        if (token == null || !token.matches("ag_[A-Za-z0-9_-]{43}"))
            throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "资源授权不存在或已过期");
        AgentResourceGrant grant = mapper.findActive(hash(token), kind);
        if (grant == null) throw new BusinessException(StatusCode.NOT_FOUND_ERROR, "资源授权不存在或已过期");
        return grant;
    }

    public Map<String, String> shareData(String token) {
        AgentResourceGrant grant = require(token, "share");
        return Map.of("userId", grant.getUserId().toString(), "fileIds", grant.getFileIdsJson(), "visitCount", "0");
    }

    public DownLoadInfoDTO downloadData(String token) {
        AgentResourceGrant grant = require(token, "download");
        DownLoadInfoDTO result = new DownLoadInfoDTO();
        result.setUserId(grant.getUserId());
        try { result.setFileIds(json.readValue(grant.getFileIdsJson(), new TypeReference<List<String>>() {})); }
        catch (Exception e) { throw new IllegalStateException("Invalid resource grant", e); }
        return result;
    }

    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
