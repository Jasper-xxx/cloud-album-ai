package com.memory.xzp.metrics;

import com.memory.xzp.mapper.AsyncTaskMapper;
import com.memory.xzp.model.dto.task.AsyncTaskStatusCount;
import com.memory.xzp.model.enums.AsyncTaskStatus;
import com.memory.xzp.model.enums.AsyncTaskType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AsyncTaskMetrics {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskMetrics.class);

    private final AsyncTaskMapper asyncTaskMapper;
    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> backlog = new ConcurrentHashMap<>();

    public AsyncTaskMetrics(AsyncTaskMapper asyncTaskMapper, MeterRegistry meterRegistry) {
        this.asyncTaskMapper = asyncTaskMapper;
        this.meterRegistry = meterRegistry;
        registerBacklogGauges();
    }

    public void recordExecution(String taskType, String outcome, long durationNanos) {
        try {
            String safeTaskType = normalizeTaskType(taskType);
            Counter.builder("memory.async.task.executions")
                    .description("Total persistent async task executions")
                    .tag("task_type", safeTaskType)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();
            Timer.builder("memory.async.task.duration")
                    .description("Persistent async task execution duration")
                    .tag("task_type", safeTaskType)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(10))
                    .maximumExpectedValue(Duration.ofMinutes(30))
                    .register(meterRegistry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.warn("Failed to record async task execution metrics: {}", e.getMessage());
        }
    }

    public void recordRetry(String taskType) {
        try {
            Counter.builder("memory.async.task.retries")
                    .description("Persistent async task retries scheduled")
                    .tag("task_type", normalizeTaskType(taskType))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.warn("Failed to record async task retry metric: {}", e.getMessage());
        }
    }

    public void recordDispatchRejected(String taskType) {
        try {
            Counter.builder("memory.async.task.dispatch.rejected")
                    .description("Persistent async task dispatches rejected by local executors")
                    .tag("task_type", normalizeTaskType(taskType))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.warn("Failed to record async task rejection metric: {}", e.getMessage());
        }
    }

    public void recordStaleRecovered(int count) {
        if (count <= 0) {
            return;
        }
        try {
            Counter.builder("memory.async.task.stale.recovered")
                    .description("Stale running tasks recovered for retry")
                    .register(meterRegistry)
                    .increment(count);
        } catch (RuntimeException e) {
            log.warn("Failed to record stale async task recovery metric: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        refreshBacklog();
    }

    @Scheduled(
            fixedDelayString = "${app.async.task.metrics-refresh-ms:30000}",
            initialDelayString = "${app.async.task.metrics-initial-delay-ms:5000}"
    )
    public void refreshBacklog() {
        try {
            backlog.values().forEach(value -> value.set(0));
            List<AsyncTaskStatusCount> counts = asyncTaskMapper.selectStatusCounts();
            for (AsyncTaskStatusCount count : counts) {
                AtomicLong gauge = backlog.get(metricKey(count.getTaskType(), count.getStatus()));
                if (gauge != null && count.getTaskCount() != null) {
                    gauge.set(count.getTaskCount());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to refresh async task backlog metrics: {}", e.getMessage());
        }
    }

    private void registerBacklogGauges() {
        for (AsyncTaskType taskType : AsyncTaskType.values()) {
            for (AsyncTaskStatus status : AsyncTaskStatus.values()) {
                AtomicLong value = new AtomicLong();
                backlog.put(metricKey(taskType.name(), status.name()), value);
                Gauge.builder("memory.async.task.backlog", value, AtomicLong::get)
                        .description("Current persistent async task count")
                        .tag("task_type", taskType.name())
                        .tag("status", status.name())
                        .register(meterRegistry);
            }
        }
    }

    private String metricKey(String taskType, String status) {
        return taskType + ":" + status;
    }

    private String normalizeTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            return "UNKNOWN";
        }
        try {
            return AsyncTaskType.valueOf(taskType).name();
        } catch (IllegalArgumentException e) {
            return "UNKNOWN";
        }
    }
}
