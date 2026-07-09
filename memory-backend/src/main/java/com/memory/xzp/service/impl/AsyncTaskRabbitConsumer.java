package com.memory.xzp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.model.dto.task.AsyncTaskDispatchMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.async.task.mq", name = "enabled", havingValue = "true")
public class AsyncTaskRabbitConsumer {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskRabbitConsumer.class);

    private final ObjectMapper objectMapper;
    private final AsyncTaskServiceImpl asyncTaskService;

    public AsyncTaskRabbitConsumer(ObjectMapper objectMapper, AsyncTaskServiceImpl asyncTaskService) {
        this.objectMapper = objectMapper;
        this.asyncTaskService = asyncTaskService;
    }

    @RabbitListener(queues = "${app.async.task.mq.queue:memory.async-task.dispatch}")
    public void consume(String payload) {
        AsyncTaskDispatchMessage message;
        try {
            message = objectMapper.readValue(payload, AsyncTaskDispatchMessage.class);
        } catch (Exception e) {
            throw new AmqpRejectAndDontRequeueException("Invalid async task dispatch message", e);
        }
        if (message.taskId() == null) {
            throw new AmqpRejectAndDontRequeueException("Async task dispatch message taskId is missing");
        }
        log.debug("Received async task dispatch message: taskId={}, taskType={}",
                message.taskId(), message.taskType());
        asyncTaskService.dispatch(message.taskId());
    }
}
