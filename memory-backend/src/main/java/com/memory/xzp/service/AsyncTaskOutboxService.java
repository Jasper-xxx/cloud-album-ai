package com.memory.xzp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.config.ObservabilityConstants;
import com.memory.xzp.mapper.OutboxEventMapper;
import com.memory.xzp.model.dto.task.AsyncTaskDispatchMessage;
import com.memory.xzp.model.entity.AsyncTaskEntity;
import com.memory.xzp.model.entity.OutboxEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AsyncTaskOutboxService {

    public static final String EVENT_TYPE = "ASYNC_TASK_DISPATCH";
    public static final String AGGREGATE_TYPE = "async_task";

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskOutboxService.class);

    private final OutboxEventMapper outboxEventMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.async.task.mq.outbox-max-retries:10}")
    private int outboxMaxRetries;

    public AsyncTaskOutboxService(OutboxEventMapper outboxEventMapper, ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void recordDispatch(AsyncTaskEntity task) {
        if (task == null || task.getId() == null) {
            return;
        }
        OutboxEventEntity event = new OutboxEventEntity();
        event.setEventKey(EVENT_TYPE + ":" + task.getId());
        event.setEventType(EVENT_TYPE);
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(String.valueOf(task.getId()));
        event.setPayloadJson(payload(task));
        event.setMaxRetries(Math.max(1, outboxMaxRetries));
        outboxEventMapper.upsertDispatchEvent(event);
    }

    private String payload(AsyncTaskEntity task) {
        try {
            AsyncTaskDispatchMessage message = new AsyncTaskDispatchMessage(
                    task.getId(),
                    task.getTaskType(),
                    task.getUserId(),
                    task.getFileId(),
                    traceValue(task.getPayloadJson(), ObservabilityConstants.MDC_REQUEST_ID),
                    traceValue(task.getPayloadJson(), ObservabilityConstants.MDC_TRACE_ID)
            );
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("Failed to serialize async task dispatch outbox payload: taskId={}, error={}",
                    task.getId(), e.getMessage());
            throw new IllegalStateException("Failed to serialize async task dispatch payload", e);
        }
    }

    private String traceValue(String payloadJson, String fieldName) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (!payload.hasNonNull(fieldName)) {
                return null;
            }
            String value = payload.get(fieldName).asText();
            return value == null || value.isBlank() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }
}
