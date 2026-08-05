package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P1 智能体组合检索请求。
 */
@Data
public class AgentAdvancedSearchRequest {

    private Integer current;
    private Integer size;
    private String dateField;
    private String dateFrom;
    private String dateTo;
    private List<String> tags;
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
    private String normalAlbumState;
    private List<String> mediaTypes;
    private String tagState;
    private Boolean missingLocation;
    private Boolean missingFeature;
    private Boolean missingAiAnalysis;
    private String locationState;
    private String featureState;
    private String aiAnalysisState;
    private String keyword;
    private String orderBy;
    private String orderType;

    @JsonIgnore
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
