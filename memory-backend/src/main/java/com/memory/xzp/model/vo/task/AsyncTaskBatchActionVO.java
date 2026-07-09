package com.memory.xzp.model.vo.task;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AsyncTaskBatchActionVO {

    private int requestedCount;

    private int updatedCount;
}
