package com.memory.xzp.service;

import com.memory.xzp.config.ObservabilityConstants;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Supplier;

@Component
public class ExternalServiceExecutor {

    public static final String AI = "ai";
    public static final String MINIO = "minio";
    public static final String MINIO_UPLOAD = "minioUpload";
    public static final String MAP = "map";
    public static final String MAIL = "mail";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final Executor externalCallExecutor;

    public ExternalServiceExecutor(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            BulkheadRegistry bulkheadRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            @Qualifier("externalCallExecutor") Executor externalCallExecutor
    ) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.externalCallExecutor = externalCallExecutor;
    }

    public <T> T execute(String backend, CheckedOperation<T> operation) {
        String serviceName = backend == null || backend.isBlank() ? "default" : backend;
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.put(ObservabilityConstants.MDC_EXTERNAL_SERVICE, serviceName);
        try {
            TimeLimiter timeLimiter = timeLimiterRegistry.timeLimiter(serviceName);
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
            Retry retry = retryRegistry.retry(serviceName);
            Bulkhead bulkhead = bulkheadRegistry.bulkhead(serviceName);

            Supplier<Future<T>> futureSupplier = () -> CompletableFuture.supplyAsync(
                    () -> invoke(operation),
                    externalCallExecutor
            );
            Callable<T> callable = () -> timeLimiter.executeFutureSupplier(futureSupplier);
            callable = CircuitBreaker.decorateCallable(circuitBreaker, callable);
            callable = Retry.decorateCallable(retry, callable);
            callable = Bulkhead.decorateCallable(bulkhead, callable);
            return callable.call();
        } catch (Exception e) {
            throw propagate(e);
        } finally {
            restoreMdc(previousMdc);
        }
    }

    public void run(String backend, CheckedRunnable runnable) {
        execute(backend, () -> {
            runnable.run();
            return null;
        });
    }

    private <T> T invoke(CheckedOperation<T> operation) {
        try {
            return operation.call();
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    private RuntimeException propagate(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new RuntimeException(current);
    }

    private void restoreMdc(Map<String, String> previousMdc) {
        if (previousMdc == null) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(previousMdc);
    }

    @FunctionalInterface
    public interface CheckedOperation<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
