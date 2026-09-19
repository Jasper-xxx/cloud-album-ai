package com.memory.xzp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.entity.AgentPendingActionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 智能体待确认操作持久化。
 */
public interface AgentPendingActionMapper extends BaseMapper<AgentPendingActionEntity> {
    @Select("SELECT * FROM agent_pending_action WHERE user_id=#{userId} ORDER BY create_time DESC LIMIT 10")
    java.util.List<AgentPendingActionEntity> recentByUser(Long userId);
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO agent_conversation (user_id, conversation_id) VALUES (#{userId}, #{conversationId})
            ON DUPLICATE KEY UPDATE conversation_id = conversation_id
            """)
    int lockConversation(@Param("userId") Long userId, @Param("conversationId") String conversationId);

    @Update("""
            UPDATE agent_pending_action
            SET status = 'CANCELLED',
                completed_at = #{now},
                update_time = #{now}
            WHERE user_id = #{userId}
              AND conversation_id = #{conversationId}
              AND status = 'PREVIEWED'
            """)
    int supersedeOpenPreviews(
            @Param("userId") Long userId,
            @Param("conversationId") String conversationId,
            @Param("now") LocalDateTime now
    );

    @Select("""
            SELECT *
            FROM agent_pending_action
            WHERE pending_action_id = #{pendingActionId}
            LIMIT 1
            """)
    AgentPendingActionEntity selectByPendingActionId(@Param("pendingActionId") String pendingActionId);

    @Update("""
            UPDATE agent_pending_action
            SET status = 'EXECUTING',
                confirmed_at = #{now},
                started_at = #{now},
                last_error = NULL,
                update_time = #{now}
            WHERE pending_action_id = #{pendingActionId}
              AND user_id = #{userId}
              AND status = 'PREVIEWED'
              AND expires_at > #{now}
            """)
    int claim(
            @Param("pendingActionId") String pendingActionId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE agent_pending_action
            SET status = 'EXPIRED',
                completed_at = #{now},
                update_time = #{now}
            WHERE pending_action_id = #{pendingActionId}
              AND user_id = #{userId}
              AND status = 'PREVIEWED'
              AND expires_at <= #{now}
            """)
    int markExpired(
            @Param("pendingActionId") String pendingActionId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE agent_pending_action
            SET status = 'FAILED',
                last_error = #{safeMessage},
                completed_at = #{now},
                update_time = #{now}
            WHERE pending_action_id = #{pendingActionId}
              AND user_id = #{userId}
              AND status = 'PREVIEWED'
            """)
    int markInvalidPreviewFailed(
            @Param("pendingActionId") String pendingActionId,
            @Param("userId") Long userId,
            @Param("safeMessage") String safeMessage,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE agent_pending_action
            SET status = 'FAILED',
                last_error = #{safeMessage},
                completed_at = #{now},
                update_time = #{now}
            WHERE pending_action_id = #{pendingActionId}
              AND user_id = #{userId}
              AND status = 'EXECUTING'
              AND started_at <= #{leaseCutoff}
            """)
    int markStaleExecutingFailed(
            @Param("pendingActionId") String pendingActionId,
            @Param("userId") Long userId,
            @Param("leaseCutoff") LocalDateTime leaseCutoff,
            @Param("safeMessage") String safeMessage,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE agent_pending_action
            SET status = 'EXPIRED',
                completed_at = #{now},
                update_time = #{now}
            WHERE status = 'PREVIEWED'
              AND expires_at <= #{now}
            """)
    int sweepExpired(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE agent_pending_action
            SET status = 'FAILED',
                last_error = #{safeMessage},
                completed_at = #{now},
                update_time = #{now}
            WHERE status = 'EXECUTING'
              AND started_at <= #{leaseCutoff}
            """)
    int sweepStaleExecuting(
            @Param("leaseCutoff") LocalDateTime leaseCutoff,
            @Param("safeMessage") String safeMessage,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE agent_pending_action
            SET status = 'CANCELLED',
                completed_at = #{now},
                update_time = #{now}
            WHERE pending_action_id = #{pendingActionId}
              AND user_id = #{userId}
              AND status = 'PREVIEWED'
              AND expires_at > #{now}
            """)
    int cancel(
            @Param("pendingActionId") String pendingActionId,
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE agent_pending_action
            SET status = 'SUCCEEDED',
                result_json = #{resultJson},
                actual_affected_file_count = #{actualAffectedFileCount},
                actual_created_album_count = #{actualCreatedAlbumCount},
                skipped_file_count = #{skippedFileCount},
                last_error = NULL,
                completed_at = #{now},
                update_time = #{now}
            WHERE pending_action_id = #{pendingActionId}
              AND user_id = #{userId}
              AND status = 'EXECUTING'
            """)
    int markSuccess(
            @Param("pendingActionId") String pendingActionId,
            @Param("userId") Long userId,
            @Param("resultJson") String resultJson,
            @Param("actualAffectedFileCount") Integer actualAffectedFileCount,
            @Param("actualCreatedAlbumCount") Integer actualCreatedAlbumCount,
            @Param("skippedFileCount") Integer skippedFileCount,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE agent_pending_action
            SET status = 'FAILED',
                last_error = #{lastError},
                completed_at = #{now},
                update_time = #{now}
            WHERE pending_action_id = #{pendingActionId}
              AND user_id = #{userId}
              AND status = 'EXECUTING'
            """)
    int markFailed(
            @Param("pendingActionId") String pendingActionId,
            @Param("userId") Long userId,
            @Param("lastError") String lastError,
            @Param("now") LocalDateTime now
    );
}
