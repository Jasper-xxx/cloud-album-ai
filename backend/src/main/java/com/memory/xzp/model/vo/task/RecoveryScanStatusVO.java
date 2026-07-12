package com.memory.xzp.model.vo.task;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RecoveryScanStatusVO {

    private String taskType;

    private boolean enabled;
}
