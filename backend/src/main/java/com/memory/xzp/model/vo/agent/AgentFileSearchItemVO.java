package com.memory.xzp.model.vo.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 组合检索返回的安全文件摘要，不包含对象存储路径或向量。
 */
@Data
public class AgentFileSearchItemVO {

    private String fileId;
    private String originFileName;
    private Long size;
    private String contentType;
    private String category;
    private Integer duration;
    private Integer width;
    private Integer height;
    private String fileUrl;
    private String thumbnailUrl;
    private LocalDateTime takenAt;
    private LocalDateTime uploadedAt;
    private String make;
    private String model;
    private String country;
    private String province;
    private String city;
    private String district;
    private Boolean hasLocation;
    private Boolean hasFeature;
    private Boolean hasAiAnalysis;
    private List<String> tags = new ArrayList<>();

    @JsonIgnore
    private String tagNamesText;
}
