package com.memory.xzp.model.dto.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端保存的智能体待执行操作。
 *
 * <p>该对象只由预览阶段生成。执行阶段必须使用这里冻结的目标集合，
 * 不能重新采用模型提交的筛选条件。</p>
 */
@Data
public class AgentPendingActionPayload {

    private String pendingActionId;
    private Long userId;
    private String family;
    private String action;
    private Long albumId;
    private String albumName;
    private String tagName;
    private String imageType;
    private List<String> fileIds = new ArrayList<>();
    private String agentTaskId;
    private Double minConfidence;
    private List<AgentSuggestedTagPayload> suggestedTags = new ArrayList<>();
    private List<Long> albumIds = new ArrayList<>();
    private List<Long> personIds = new ArrayList<>();
    private Long sourcePersonId;
    private Long targetPersonId;
    private String personName;
    private String personRelation;
    private String locationValue;
    private Integer shareDays;
    private Boolean includePictures;
    private String riskLevel;
}
