package com.memory.xzp.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.model.dto.agent.AgentAdvancedSearchCriteria;
import com.memory.xzp.model.vo.agent.AgentFileSearchItemVO;
import com.memory.xzp.model.vo.agent.AgentLibraryHealthRawVO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * P1 智能体组合检索和图库健康分析。
 */
public interface AgentLibraryMapper {

    Page<AgentFileSearchItemVO> selectAdvancedSearch(
            Page<AgentFileSearchItemVO> page,
            @Param("criteria") AgentAdvancedSearchCriteria criteria,
            @Param("userId") Long userId
    );

    AgentLibraryHealthRawVO analyzeLibrary(
            @Param("userId") Long userId,
            @Param("staleBefore") LocalDateTime staleBefore
    );
}
