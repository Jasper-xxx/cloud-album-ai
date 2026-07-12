package com.memory.xzp.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("async_task")
public class AsyncTaskEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String taskKey;

    private String taskType;

    private Long userId;

    private String fileId;

    private String payloadJson;

    private String resultJson;

    private String status;

    private Integer retryCount;

    private Integer maxRetries;

    private LocalDateTime nextRetryTime;

    private String lastError;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
