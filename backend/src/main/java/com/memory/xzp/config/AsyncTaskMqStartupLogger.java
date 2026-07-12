package com.memory.xzp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AsyncTaskMqStartupLogger {

    private static final Logger log = LoggerFactory.getLogger(AsyncTaskMqStartupLogger.class);

    private final RabbitListenerEndpointRegistry listenerEndpointRegistry;

    @Value("${app.async.task.mq.enabled:false}")
    private boolean mqEnabled;

    @Value("${spring.rabbitmq.host:localhost}")
    private String host;

    @Value("${spring.rabbitmq.port:5672}")
    private int port;

    @Value("${spring.rabbitmq.username:guest}")
    private String username;

    @Value("${spring.rabbitmq.virtual-host:/}")
    private String virtualHost;

    @Value("${app.async.task.mq.exchange:memory.async-task.exchange}")
    private String exchange;

    @Value("${app.async.task.mq.queue:memory.async-task.dispatch}")
    private String queue;

    @Value("${app.async.task.mq.routing-key:async-task.dispatch}")
    private String routingKey;

    public AsyncTaskMqStartupLogger(ObjectProvider<RabbitListenerEndpointRegistry> listenerEndpointRegistryProvider) {
        this.listenerEndpointRegistry = listenerEndpointRegistryProvider.getIfAvailable();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logMqStartupState() {
        if (!mqEnabled) {
            log.info("Async task MQ is disabled; set ASYNC_TASK_MQ_ENABLED=true to enable RabbitMQ dispatch");
            return;
        }
        int listenerCount = listenerEndpointRegistry == null
                ? 0
                : listenerEndpointRegistry.getListenerContainerIds().size();
        log.info(
                "Async task MQ is enabled: rabbitmq={}:{} virtualHost={} username={} exchange={} queue={} routingKey={} listenerContainers={}",
                host,
                port,
                virtualHost,
                username,
                exchange,
                queue,
                routingKey,
                listenerCount
        );
    }
}
