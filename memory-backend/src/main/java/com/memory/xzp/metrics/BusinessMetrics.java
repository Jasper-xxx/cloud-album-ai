package com.memory.xzp.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class BusinessMetrics {

    private static final Logger log = LoggerFactory.getLogger(BusinessMetrics.class);

    private final MeterRegistry meterRegistry;

    public BusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordUploadLifecycle(String phase, String mode, String mediaType, String outcome) {
        try {
            Counter.builder("memory.upload.lifecycle.events")
                    .description("Upload lifecycle business events")
                    .tag("phase", normalize(phase))
                    .tag("mode", normalize(mode))
                    .tag("media_type", normalize(mediaType))
                    .tag("outcome", normalize(outcome))
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.warn("Failed to record upload lifecycle metric: {}", e.getMessage());
        }
    }

    public void recordUploadBytes(String mode, String mediaType, long bytes) {
        if (bytes <= 0) {
            return;
        }
        try {
            DistributionSummary.builder("memory.upload.bytes")
                    .description("Uploaded object size distribution")
                    .baseUnit("bytes")
                    .tag("mode", normalize(mode))
                    .tag("media_type", normalize(mediaType))
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(bytes);
        } catch (RuntimeException e) {
            log.warn("Failed to record upload byte metric: {}", e.getMessage());
        }
    }

    public void recordQuotaReservation(String action, String outcome, long bytes) {
        try {
            Counter.builder("memory.storage.quota.reservations")
                    .description("Storage quota reservation operations")
                    .tag("action", normalize(action))
                    .tag("outcome", normalize(outcome))
                    .register(meterRegistry)
                    .increment();
            if (bytes > 0) {
                DistributionSummary.builder("memory.storage.quota.reserved.bytes")
                        .description("Storage quota reserved byte distribution")
                        .baseUnit("bytes")
                        .tag("action", normalize(action))
                        .tag("outcome", normalize(outcome))
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                        .record(bytes);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to record quota reservation metric: {}", e.getMessage());
        }
    }

    public void recordMinioOperation(String operation, String outcome, long durationNanos) {
        try {
            Timer.builder("memory.minio.operation.duration")
                    .description("MinIO operation latency and error rate")
                    .tag("operation", normalize(operation))
                    .tag("outcome", normalize(outcome))
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofMinutes(2))
                    .register(meterRegistry)
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException e) {
            log.warn("Failed to record MinIO operation metric: {}", e.getMessage());
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().replace(' ', '_').toUpperCase();
    }
}
