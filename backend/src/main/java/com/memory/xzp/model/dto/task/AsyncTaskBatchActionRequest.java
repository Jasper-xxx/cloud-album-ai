package com.memory.xzp.model.dto.task;

import lombok.Data;

import java.util.List;

@Data
public class AsyncTaskBatchActionRequest {

    private List<Long> taskIds;
}
