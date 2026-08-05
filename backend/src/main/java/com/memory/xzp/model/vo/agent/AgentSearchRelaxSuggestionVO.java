package com.memory.xzp.model.vo.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 组合检索为空时的确定性放宽建议。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSearchRelaxSuggestionVO {

    private String code;
    private String message;
}
