package com.memory.xzp.metrics;

import com.memory.xzp.mapper.AsyncTaskMapper;
import com.memory.xzp.model.dto.task.AsyncTaskStatusCount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsyncTaskMetricsTest {

    @Test
    void recordsExecutionRetryAndRecoveryCounters() {
        AsyncTaskMapper mapper = mock(AsyncTaskMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AsyncTaskMetrics metrics = new AsyncTaskMetrics(mapper, registry);

        metrics.recordExecution("IMAGE_TAG", "SUCCESS", 2_000_000);
        metrics.recordRetry("IMAGE_TAG");
        metrics.recordDispatchRejected("IMAGE_TAG");
        metrics.recordStaleRecovered(3);

        assertEquals(1, registry.get("memory.async.task.executions")
                .tags("task_type", "IMAGE_TAG", "outcome", "SUCCESS")
                .counter()
                .count());
        assertEquals(1, registry.get("memory.async.task.duration")
                .tags("task_type", "IMAGE_TAG", "outcome", "SUCCESS")
                .timer()
                .count());
        assertEquals(1, registry.get("memory.async.task.retries")
                .tag("task_type", "IMAGE_TAG")
                .counter()
                .count());
        assertEquals(1, registry.get("memory.async.task.dispatch.rejected")
                .tag("task_type", "IMAGE_TAG")
                .counter()
                .count());
        assertEquals(3, registry.get("memory.async.task.stale.recovered")
                .counter()
                .count());
    }

    @Test
    void refreshesBacklogGaugesFromDatabaseCounts() {
        AsyncTaskMapper mapper = mock(AsyncTaskMapper.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AsyncTaskMetrics metrics = new AsyncTaskMetrics(mapper, registry);
        AsyncTaskStatusCount pending = statusCount("IMAGE_TAG", "PENDING", 7L);
        AsyncTaskStatusCount dead = statusCount("GEO_CODING", "DEAD", 2L);
        when(mapper.selectStatusCounts()).thenReturn(List.of(pending, dead));

        metrics.refreshBacklog();

        var pendingGauge = registry.get("memory.async.task.backlog")
                .tags("task_type", "IMAGE_TAG", "status", "PENDING")
                .gauge();
        var deadGauge = registry.get("memory.async.task.backlog")
                .tags("task_type", "GEO_CODING", "status", "DEAD")
                .gauge();
        assertNotNull(pendingGauge);
        assertNotNull(deadGauge);
        assertEquals(7, pendingGauge.value());
        assertEquals(2, deadGauge.value());
    }

    private AsyncTaskStatusCount statusCount(String taskType, String status, long count) {
        AsyncTaskStatusCount result = new AsyncTaskStatusCount();
        result.setTaskType(taskType);
        result.setStatus(status);
        result.setTaskCount(count);
        return result;
    }
}
