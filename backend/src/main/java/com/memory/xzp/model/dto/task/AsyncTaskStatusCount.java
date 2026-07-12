package com.memory.xzp.model.dto.task;

import lombok.Data;

@Data
public class AsyncTaskStatusCount {

    private String taskType;

    private String status;

    private Long taskCount;
}
