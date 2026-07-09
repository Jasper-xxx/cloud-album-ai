package com.memory.xzp.config;

public final class ObservabilityConstants {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String TRACE_ID_HEADER = "X-Trace-ID";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_ASYNC_TASK_ID = "asyncTaskId";
    public static final String MDC_ASYNC_TASK_TYPE = "asyncTaskType";
    public static final String MDC_EXTERNAL_SERVICE = "externalService";

    private ObservabilityConstants() {
    }
}
