package com.memory.xzp.model.vo.agent;

import lombok.Data;

import java.util.List;

/**
 * 暴露给智能体的能力边界。
 */
@Data
public class AgentCapabilitiesVO {
    private String name;
    private String mode;
    private List<String> readOnlyTools;
    private List<String> confirmationRequiredTools;
    private List<String> disabledTools;
    private List<String> riskRules;
}
