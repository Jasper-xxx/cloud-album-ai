package com.memory.xzp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.entity.OutboxEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventMapper extends BaseMapper<OutboxEventEntity> {

    @Insert("""
            INSERT INTO outbox_event (
                event_key, event_type, aggregate_type, aggregate_id, payload_json,
                status, retry_count, max_retries, next_attempt_time, create_time, update_time
            ) VALUES (
                #{event.eventKey}, #{event.eventType}, #{event.aggregateType}, #{event.aggregateId},
                #{event.payloadJson}, 'PENDING', 0, #{event.maxRetries}, NOW(), NOW(), NOW()
            )
            ON DUPLICATE KEY UPDATE
                payload_json = VALUES(payload_json),
                retry_count = CASE
                    WHEN status = 'PUBLISHING' THEN retry_count
                    ELSE 0
                END,
                max_retries = VALUES(max_retries),
                next_attempt_time = CASE
                    WHEN status = 'PUBLISHING' THEN next_attempt_time
                    ELSE NOW()
                END,
                last_error = CASE
                    WHEN status = 'PUBLISHING' THEN last_error
                    ELSE NULL
                END,
                status = CASE
                    WHEN status = 'PUBLISHING' THEN status
                    ELSE 'PENDING'
                END,
                update_time = NOW()
            """)
    int upsertDispatchEvent(@Param("event") OutboxEventEntity event);

    @Select("""
            SELECT id
            FROM outbox_event
            WHERE status IN ('PENDING', 'FAILED')
              AND retry_count < max_retries
              AND (next_attempt_time IS NULL OR next_attempt_time <= NOW())
            ORDER BY create_time ASC, id ASC
            LIMIT #{limit}
            """)
    List<Long> selectDueEventIds(@Param("limit") int limit);

    @Update("""
            UPDATE outbox_event
            SET status = 'PUBLISHING',
                lock_token = #{lockToken},
                locked_until = #{lockedUntil},
                update_time = NOW()
            WHERE id = #{eventId}
              AND status IN ('PENDING', 'FAILED')
              AND retry_count < max_retries
              AND (next_attempt_time IS NULL OR next_attempt_time <= NOW())
            """)
    int claim(
            @Param("eventId") Long eventId,
            @Param("lockToken") String lockToken,
            @Param("lockedUntil") LocalDateTime lockedUntil
    );

    @Update("""
            UPDATE outbox_event
            SET status = 'SENT',
                lock_token = NULL,
                locked_until = NULL,
                last_error = NULL,
                published_at = NOW(),
                update_time = NOW()
            WHERE id = #{eventId}
              AND status = 'PUBLISHING'
              AND lock_token = #{lockToken}
            """)
    int markPublished(
            @Param("eventId") Long eventId,
            @Param("lockToken") String lockToken
    );

    @Update("""
            UPDATE outbox_event
            SET retry_count = retry_count + 1,
                status = CASE
                    WHEN retry_count + 1 >= max_retries THEN 'DEAD'
                    ELSE 'FAILED'
                END,
                next_attempt_time = CASE
                    WHEN retry_count + 1 >= max_retries THEN NULL
                    ELSE #{nextAttemptTime}
                END,
                lock_token = NULL,
                locked_until = NULL,
                last_error = #{lastError},
                update_time = NOW()
            WHERE id = #{eventId}
              AND status = 'PUBLISHING'
              AND lock_token = #{lockToken}
            """)
    int markPublishFailure(
            @Param("eventId") Long eventId,
            @Param("lockToken") String lockToken,
            @Param("nextAttemptTime") LocalDateTime nextAttemptTime,
            @Param("lastError") String lastError
    );

    @Update("""
            UPDATE outbox_event
            SET status = 'FAILED',
                lock_token = NULL,
                locked_until = NULL,
                next_attempt_time = NOW(),
                last_error = 'Publisher stopped before completing the event',
                update_time = NOW()
            WHERE status = 'PUBLISHING'
              AND locked_until < #{cutoff}
            """)
    int recoverStalePublishing(@Param("cutoff") LocalDateTime cutoff);
}
