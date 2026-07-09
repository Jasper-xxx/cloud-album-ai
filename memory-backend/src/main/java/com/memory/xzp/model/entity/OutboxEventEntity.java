package com.memory.xzp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("outbox_event")
public class OutboxEventEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String eventKey;

    private String eventType;

    private String aggregateType;

    private String aggregateId;

    private String payloadJson;

    private String status;

    private Integer retryCount;

    private Integer maxRetries;

    private LocalDateTime nextAttemptTime;

    private String lockToken;

    private LocalDateTime lockedUntil;

    private String lastError;

    private LocalDateTime publishedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
