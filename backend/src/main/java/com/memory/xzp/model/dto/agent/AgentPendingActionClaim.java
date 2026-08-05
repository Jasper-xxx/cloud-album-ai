package com.memory.xzp.model.dto.agent;

import com.memory.xzp.model.vo.agent.AgentActionResultVO;
import lombok.Data;

/**
 * 领取待执行操作的结果。
 */
@Data
public class AgentPendingActionClaim {

    private AgentPendingActionPayload payload;
    private AgentActionResultVO cachedResult;
    private boolean idempotentReplay;
}
