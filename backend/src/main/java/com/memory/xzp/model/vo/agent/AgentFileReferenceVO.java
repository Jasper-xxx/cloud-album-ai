package com.memory.xzp.model.vo.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 智能体回复中用于帮助用户识别目标照片的最小文件摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentFileReferenceVO {

    private String fileId;
    private String originFileName;
}
