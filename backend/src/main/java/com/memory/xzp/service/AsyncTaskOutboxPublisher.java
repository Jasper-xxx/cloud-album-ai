package com.memory.xzp.service;

import com.memory.xzp.mapper.OutboxEventMapper;
import com.memory.xzp.model.entity.OutboxEventEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@ConditionalOnProperty(prefix = "app.async.task.mq", name = "enabled", havingValue = "true")
public class AsyncTaskOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskOutboxPublisher.class);

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ScheduledTaskLockService scheduledTaskLockService;

    @Value("${app.async.task.mq.exchange:memory.async-task.exchange}")
    private String exchangeName;

    @Value("${app.async.task.mq.routing-key:async-task.dispatch}")
    private String routingKey;

    @Value("${app.async.task.mq.outbox-batch-size:100}")
    private int batchSize;

    @Value("${app.async.task.mq.outbox-retry-delay-seconds:30}")
    private long retryDelaySeconds;

    @Value("${app.async.task.mq.outbox-lock-ttl-seconds:120}")
    private long outboxLockTtlSeconds;

    @Value("${app.async.task.mq.publish-confirm-timeout-ms:5000}")
    private long publishConfirmTimeoutMs;

    public AsyncTaskOutboxPublisher(
            OutboxEventMapper outboxEventMapper,
            RabbitTemplate rabbitTemplate,
            ScheduledTaskLockService scheduledTaskLockService
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.scheduledTaskLockService = scheduledTaskLockService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishOnStartup() {
        try {
            publishDueEventsWithLock();
        } catch (RuntimeException e) {
            log.warn("Startup task failed: taskName=async-task-outbox-publish, error={}", e.getMessage(), e);
        }
    }

    @Scheduled(
            fixedDelayString = "${app.async.task.mq.outbox-publish-delay-ms:2000}",
            initialDelayString = "${app.async.task.mq.outbox-initial-delay-ms:5000}"
    )
    public void publishDueEventsWithLock() {
        scheduledTaskLockService.runWithLock(
                "async-task:outbox-publish",
                Duration.ofSeconds(outboxLockTtlSeconds),
                this::publishDueEvents
        );
    }

    private void publishDueEvents() {
        outboxEventMapper.recoverStalePublishing(LocalDateTime.now());
        List<Long> eventIds = outboxEventMapper.selectDueEventIds(Math.min(Math.max(batchSize, 1), 500));
        for (Long eventId : eventIds) {
            publishOne(eventId);
        }
    }

    private void publishOne(Long eventId) {
        String lockToken = UUID.randomUUID().toString();
        LocalDateTime lockedUntil = LocalDateTime.now().plusSeconds(Math.max(1L, outboxLockTtlSeconds));
        if (outboxEventMapper.claim(eventId, lockToken, lockedUntil) != 1) {
            return;
        }

        OutboxEventEntity event = outboxEventMapper.selectById(eventId);
        if (event == null) {
            outboxEventMapper.markPublishFailure(
                    eventId,
                    lockToken,
                    LocalDateTime.now().plusSeconds(Math.max(1L, retryDelaySeconds)),
                    "Outbox event disappeared after claim"
            );
            return;
        }
        try {
            CorrelationData correlationData = new CorrelationData(event.getEventKey());
            rabbitTemplate.convertAndSend(
                    exchangeName,
                    routingKey,
                    event.getPayloadJson(),
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(event.getEventKey());
                        message.getMessageProperties().setType(event.getEventType());
                        message.getMessageProperties().setCorrelationId(event.getEventKey());
                        return message;
                    },
                    correlationData
            );
            awaitPublisherConfirm(correlationData);
            outboxEventMapper.markPublished(eventId, lockToken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handlePublishFailure(eventId, lockToken, event, e);
        } catch (RuntimeException | ExecutionException | TimeoutException e) {
            handlePublishFailure(eventId, lockToken, event, e);
        }
    }

    private void awaitPublisherConfirm(CorrelationData correlationData)
            throws InterruptedException, ExecutionException, TimeoutException {
        CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(Math.max(1L, publishConfirmTimeoutMs), TimeUnit.MILLISECONDS);
        if (correlationData.getReturned() != null) {
            throw new IllegalStateException("RabbitMQ returned message: "
                    + correlationData.getReturned().getReplyText());
        }
        if (confirm == null || !confirm.isAck()) {
            String reason = confirm == null ? "confirm timeout" : confirm.getReason();
            throw new IllegalStateException("RabbitMQ publisher confirm failed: " + reason);
        }
    }

    private void handlePublishFailure(
            Long eventId,
            String lockToken,
            OutboxEventEntity event,
            Exception exception
    ) {
        outboxEventMapper.markPublishFailure(
                eventId,
                lockToken,
                LocalDateTime.now().plusSeconds(Math.max(1L, retryDelaySeconds)),
                errorMessage(exception)
        );
        log.warn("Failed to publish outbox event: eventId={}, eventKey={}, error={}",
                eventId, event.getEventKey(), exception.getMessage());
    }

    private String errorMessage(Exception exception) {
        String message = exception.getClass().getSimpleName() + ": "
                + (exception.getMessage() == null ? "unknown error" : exception.getMessage());
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
