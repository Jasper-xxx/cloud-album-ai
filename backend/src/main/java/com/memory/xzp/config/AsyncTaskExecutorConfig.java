package com.memory.xzp.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executors for file post-processing and batch AI requests.
 */
@Configuration
public class AsyncTaskExecutorConfig {

    @Bean(name = "fileTaskExecutor")
    public ThreadPoolTaskExecutor fileTaskExecutor(
            @Value("${app.async.file.core-pool-size:4}") int corePoolSize,
            @Value("${app.async.file.max-pool-size:8}") int maxPoolSize,
            @Value("${app.async.file.queue-capacity:100}") int queueCapacity,
            @Value("${app.async.shutdown-await-seconds:30}") int awaitSeconds
    ) {
        return createExecutor("file-task-", corePoolSize, maxPoolSize, queueCapacity, awaitSeconds);
    }

    @Bean(name = "aiBatchTaskExecutor")
    public ThreadPoolTaskExecutor aiBatchTaskExecutor(
            @Value("${app.async.ai-batch.core-pool-size:4}") int corePoolSize,
            @Value("${app.async.ai-batch.max-pool-size:4}") int maxPoolSize,
            @Value("${app.async.ai-batch.queue-capacity:50}") int queueCapacity,
            @Value("${app.async.shutdown-await-seconds:30}") int awaitSeconds
    ) {
        return createExecutor("ai-batch-", corePoolSize, maxPoolSize, queueCapacity, awaitSeconds);
    }

    @Bean(name = "externalCallExecutor")
    public ThreadPoolTaskExecutor externalCallExecutor(
            @Value("${app.resilience.executor.core-pool-size:8}") int corePoolSize,
            @Value("${app.resilience.executor.max-pool-size:32}") int maxPoolSize,
            @Value("${app.resilience.executor.queue-capacity:200}") int queueCapacity,
            @Value("${app.async.shutdown-await-seconds:30}") int awaitSeconds
    ) {
        return createExecutor("external-call-", corePoolSize, maxPoolSize, queueCapacity, awaitSeconds);
    }

    private ThreadPoolTaskExecutor createExecutor(
            String threadNamePrefix,
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            int awaitSeconds
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setTaskDecorator(mdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitSeconds);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    private TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previousContextMap = MDC.getCopyOfContextMap();
                if (contextMap == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(contextMap);
                }
                try {
                    runnable.run();
                } finally {
                    if (previousContextMap == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previousContextMap);
                    }
                }
            };
        };
    }
}
