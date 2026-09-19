package com.memory.xzp.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3/P4 智能体扩展操作预览请求。
 */
@Data
public class AgentExtendedActionRequest {

    private String action;
    /** 仅用于按普通相册名称预览图片移入回收站；与显式 ID 范围互斥。 */
    private String albumName;
    /** 明确恢复回收站全部图片时，由预览解析并冻结范围；不能与 fileIds 混用。 */
    private Boolean allRecycleImages;
    private List<String> fileIds;
    private List<Long> albumIds;
    private List<Long> personIds;
    private Long sourcePersonId;
    private Long targetPersonId;
    private String personName;
    private String personRelation;
    private String locationValue;
    private Double similarity;
    private Integer size;
    private Integer shareDays;
    private Boolean includePictures;
    private String imageType;

    @JsonIgnore
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
