package com.memory.xzp.model.dto.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 经过后端白名单归一化的组合检索条件，只供 Mapper 使用。
 */
@Data
public class AgentAdvancedSearchCriteria {

    private LocalDateTime dateFrom;
    private LocalDateTime dateTo;
    private String dateField;
    private List<String> tags = new ArrayList<>();
    private String tagOperator;
    private String country;
    private String province;
    private String city;
    private String district;
    private String make;
    private String model;
    private Long albumId;
    private String albumName;
    private Long personId;
    private String personName;
    private Boolean withoutNormalAlbum;
    private List<String> mediaTypes = new ArrayList<>();
    private String tagState;
    private Boolean missingLocation;
    private Boolean missingFeature;
    private Boolean missingAiAnalysis;
    private String keyword;
    private String orderBy;
    private String orderType;
}
