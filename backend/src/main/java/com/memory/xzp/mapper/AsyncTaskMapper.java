package com.memory.xzp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.memory.xzp.model.dto.task.AsyncTaskStatusCount;
import com.memory.xzp.model.entity.AsyncTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface AsyncTaskMapper extends BaseMapper<AsyncTaskEntity> {

    @Insert("""
            INSERT INTO async_task (
                task_key, task_type, user_id, file_id, payload_json, status,
                retry_count, max_retries, create_time, update_time
            ) VALUES (
                #{task.taskKey}, #{task.taskType}, #{task.userId}, #{task.fileId},
                #{task.payloadJson}, #{task.status}, 0, #{task.maxRetries}, NOW(), NOW()
            )
            ON DUPLICATE KEY UPDATE task_key = VALUES(task_key)
            """)
    int insertIfAbsent(@Param("task") AsyncTaskEntity task);

    @Select("SELECT * FROM async_task WHERE task_key = #{taskKey} LIMIT 1")
    AsyncTaskEntity selectByTaskKey(@Param("taskKey") String taskKey);

    @Select("""
            SELECT task_type AS taskType,
                   status,
                   COUNT(*) AS taskCount
            FROM async_task
            GROUP BY task_type, status
            """)
    List<AsyncTaskStatusCount> selectStatusCounts();

    @Select("""
            SELECT id
            FROM async_task
            WHERE status IN ('PENDING', 'FAILED')
              AND retry_count < max_retries
              AND (next_retry_time IS NULL OR next_retry_time <= NOW())
            ORDER BY create_time ASC
            LIMIT #{limit}
            """)
    List<Long> selectDueTaskIds(@Param("limit") int limit);

    @Update("""
            UPDATE async_task
            SET status = 'RUNNING',
                started_at = NOW(),
                completed_at = NULL,
                update_time = NOW()
            WHERE id = #{taskId}
              AND status IN ('PENDING', 'FAILED')
              AND retry_count < max_retries
              AND (next_retry_time IS NULL OR next_retry_time <= NOW())
            """)
    int claim(@Param("taskId") Long taskId);

    @Update("""
            UPDATE async_task
            SET status = 'PENDING',
                started_at = NULL,
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 'RUNNING'
            """)
    int releaseClaim(@Param("taskId") Long taskId);

    @Update("""
            UPDATE async_task
            SET status = 'SUCCESS',
                result_json = #{resultJson},
                last_error = NULL,
                next_retry_time = NULL,
                completed_at = NOW(),
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 'RUNNING'
            """)
    int markSuccess(@Param("taskId") Long taskId, @Param("resultJson") String resultJson);

    @Update("""
            UPDATE async_task
            SET retry_count = retry_count + 1,
                status = CASE
                    WHEN retry_count + 1 >= max_retries THEN 'DEAD'
                    ELSE 'FAILED'
                END,
                next_retry_time = CASE
                    WHEN retry_count + 1 >= max_retries THEN NULL
                    ELSE #{nextRetryTime}
                END,
                last_error = #{lastError},
                completed_at = CASE
                    WHEN retry_count + 1 >= max_retries THEN NOW()
                    ELSE NULL
                END,
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 'RUNNING'
            """)
    int markFailure(
            @Param("taskId") Long taskId,
            @Param("nextRetryTime") LocalDateTime nextRetryTime,
            @Param("lastError") String lastError
    );

    @Update("""
            UPDATE async_task
            SET status = 'DEAD',
                retry_count = max_retries,
                next_retry_time = NULL,
                last_error = #{lastError},
                completed_at = NOW(),
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 'RUNNING'
            """)
    int markDead(@Param("taskId") Long taskId, @Param("lastError") String lastError);

    @Update("""
            UPDATE async_task
            SET status = 'PENDING',
                retry_count = 0,
                next_retry_time = NULL,
                result_json = NULL,
                last_error = NULL,
                started_at = NULL,
                completed_at = NULL,
                update_time = NOW()
            WHERE id = #{taskId}
              AND user_id = #{userId}
              AND status IN ('FAILED', 'DEAD')
            """)
    int resetForManualRetry(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Select({
            "<script>",
            "SELECT id",
            "FROM async_task",
            "WHERE status IN ('FAILED', 'DEAD')",
            "  AND id IN",
            "  <foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
            "    #{taskId}",
            "  </foreach>",
            "ORDER BY id",
            "</script>"
    })
    List<Long> selectRetryableTaskIds(@Param("taskIds") List<Long> taskIds);

    @Update({
            "<script>",
            "UPDATE async_task",
            "SET status = 'PENDING',",
            "    retry_count = 0,",
            "    next_retry_time = NULL,",
            "    result_json = NULL,",
            "    last_error = NULL,",
            "    started_at = NULL,",
            "    completed_at = NULL,",
            "    update_time = NOW()",
            "WHERE status IN ('FAILED', 'DEAD')",
            "  AND id IN",
            "  <foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
            "    #{taskId}",
            "  </foreach>",
            "</script>"
    })
    int resetForAdminRetry(@Param("taskIds") List<Long> taskIds);

    @Update({
            "<script>",
            "UPDATE async_task",
            "SET status = 'CANCELLED',",
            "    next_retry_time = NULL,",
            "    completed_at = NOW(),",
            "    update_time = NOW()",
            "WHERE status = 'DEAD'",
            "  AND id IN",
            "  <foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>",
            "    #{taskId}",
            "  </foreach>",
            "</script>"
    })
    int cancelDeadTasks(@Param("taskIds") List<Long> taskIds);

    @Update("""
            UPDATE async_task
            SET status = 'FAILED',
                next_retry_time = NOW(),
                last_error = 'Worker stopped before completing the task',
                started_at = NULL,
                update_time = NOW()
            WHERE status = 'RUNNING'
              AND started_at < #{cutoff}
            """)
    int recoverStaleRunning(@Param("cutoff") LocalDateTime cutoff);

    @Update("""
            UPDATE async_task
            SET status = 'FAILED',
                next_retry_time = NOW(),
                last_error = #{lastError},
                started_at = NULL,
                update_time = NOW()
            WHERE status = 'RUNNING'
              AND task_type = #{taskType}
              AND started_at < #{cutoff}
            """)
    int recoverStaleRunningByType(
            @Param("taskType") String taskType,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("lastError") String lastError
    );
}
