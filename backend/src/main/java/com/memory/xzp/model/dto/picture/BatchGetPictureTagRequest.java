package com.memory.xzp.model.dto.picture;

import lombok.Data;

import java.util.List;

@Data
public class BatchGetPictureTagRequest {
    private List<String> fileIds;
    private Boolean autoAddTag;
}
