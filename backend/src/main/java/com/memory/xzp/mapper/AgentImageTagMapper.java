package com.memory.xzp.mapper;

import com.memory.xzp.model.vo.agent.AgentImageTagBatchRawVO;
import com.memory.xzp.model.vo.agent.AgentImageTagTaskItemRawVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * P2 智能体 AI 标签任务批次。
 */
public interface AgentImageTagMapper {

    @Insert("""
            INSERT INTO agent_image_tag_batch (
                batch_id, user_id, pending_action_id, min_confidence,
                total_count, create_time, update_time
            ) VALUES (
                #{batchId}, #{userId}, #{pendingActionId}, #{minConfidence},
                #{totalCount}, #{now}, #{now}
            )
            """)
    int insertBatch(
            @Param("batchId") String batchId,
            @Param("userId") Long userId,
            @Param("pendingActionId") String pendingActionId,
            @Param("minConfidence") Double minConfidence,
            @Param("totalCount") Integer totalCount,
            @Param("now") LocalDateTime now
    );

    @Insert("""
            INSERT INTO agent_image_tag_batch_item (
                batch_id, file_id, async_task_id, create_time
            ) VALUES (
                #{batchId}, #{fileId}, #{asyncTaskId}, #{now}
            )
            """)
    int insertItem(
            @Param("batchId") String batchId,
            @Param("fileId") String fileId,
            @Param("asyncTaskId") Long asyncTaskId,
            @Param("now") LocalDateTime now
    );

    @Select("""
            SELECT
                batch_id AS batchId,
                user_id AS userId,
                pending_action_id AS pendingActionId,
                min_confidence AS minConfidence,
                total_count AS totalCount,
                create_time AS createTime,
                update_time AS updateTime
            FROM agent_image_tag_batch
            WHERE batch_id = #{batchId}
              AND user_id = #{userId}
            """)
    AgentImageTagBatchRawVO selectOwnedBatch(
            @Param("batchId") String batchId,
            @Param("userId") Long userId
    );

    @Select("""
            SELECT
                item.file_id AS fileId,
                item.async_task_id AS asyncTaskId,
                task.status AS status,
                task.result_json AS resultJson,
                task.last_error AS lastError,
                task.started_at AS startedAt,
                task.completed_at AS completedAt
            FROM agent_image_tag_batch_item item
            INNER JOIN async_task task ON task.id = item.async_task_id
            WHERE item.batch_id = #{batchId}
              AND task.user_id = #{userId}
              AND task.task_type = 'IMAGE_TAG'
            ORDER BY item.id
            """)
    List<AgentImageTagTaskItemRawVO> selectOwnedItems(
            @Param("batchId") String batchId,
            @Param("userId") Long userId
    );
}
