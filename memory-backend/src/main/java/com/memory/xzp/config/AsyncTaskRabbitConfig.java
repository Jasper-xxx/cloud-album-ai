package com.memory.xzp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.async.task.mq", name = "enabled", havingValue = "true")
public class AsyncTaskRabbitConfig {

    @Value("${app.async.task.mq.exchange:memory.async-task.exchange}")
    private String exchangeName;

    @Value("${app.async.task.mq.queue:memory.async-task.dispatch}")
    private String queueName;

    @Value("${app.async.task.mq.routing-key:async-task.dispatch}")
    private String routingKey;

    @Bean
    public DirectExchange asyncTaskExchange() {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue asyncTaskDispatchQueue() {
        return QueueBuilder.durable(queueName).build();
    }

    @Bean
    public Binding asyncTaskDispatchBinding(Queue asyncTaskDispatchQueue, DirectExchange asyncTaskExchange) {
        return BindingBuilder.bind(asyncTaskDispatchQueue)
                .to(asyncTaskExchange)
                .with(routingKey);
    }
}
