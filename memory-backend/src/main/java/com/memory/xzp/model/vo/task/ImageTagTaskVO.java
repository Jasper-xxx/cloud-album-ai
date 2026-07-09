package com.memory.xzp.model.vo.task;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ImageTagTaskVO {

    private Long taskId;

    private String fileId;

    private String status;
}
