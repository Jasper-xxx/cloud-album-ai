package com.memory.xzp.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskExecutorConfigTest {

    private final AsyncTaskExecutorConfig config = new AsyncTaskExecutorConfig();

    @Test
    void createsBoundedFileTaskExecutor() {
        ThreadPoolTaskExecutor executor = config.fileTaskExecutor(2, 4, 8, 15);
        executor.initialize();
        try {
            assertEquals(2, executor.getCorePoolSize());
            assertEquals(4, executor.getMaxPoolSize());
            assertEquals(8, executor.getQueueCapacity());
            assertEquals("file-task-", executor.getThreadNamePrefix());
            assertInstanceOf(
                    ThreadPoolExecutor.AbortPolicy.class,
                    executor.getThreadPoolExecutor().getRejectedExecutionHandler()
            );
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void createsSeparateBoundedAiBatchExecutor() {
        ThreadPoolTaskExecutor executor = config.aiBatchTaskExecutor(1, 3, 5, 15);
        executor.initialize();
        try {
            assertEquals(1, executor.getCorePoolSize());
            assertEquals(3, executor.getMaxPoolSize());
            assertEquals(5, executor.getQueueCapacity());
            assertEquals("ai-batch-", executor.getThreadNamePrefix());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aiBatchExecutorStartsFourTasksWithoutQueueDelay() throws InterruptedException {
        ThreadPoolTaskExecutor executor = config.aiBatchTaskExecutor(4, 4, 50, 15);
        executor.initialize();
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(4, executor.getActiveCount());
            assertEquals(0, executor.getThreadPoolExecutor().getQueue().size());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
