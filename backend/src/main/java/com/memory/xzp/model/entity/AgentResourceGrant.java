package com.memory.xzp.model.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class AgentResourceGrant {
    private String tokenHash;
    private Long userId;
    private String kind;
    private String fileIdsJson;
    private LocalDateTime expiresAt;
}
