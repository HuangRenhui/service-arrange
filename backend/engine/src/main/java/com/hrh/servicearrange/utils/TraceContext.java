package com.hrh.servicearrange.utils;

import java.util.UUID;

/**
 * 请求级链路标识。大厂编排里一次运行会贯穿接入层、调度、算子与日志。
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE = new ThreadLocal<>();

    private TraceContext() {
    }

    public static void set(String traceId) {
        TRACE.set(traceId);
    }

    public static String get() {
        return TRACE.get();
    }

    public static String getOrCreate() {
        String id = TRACE.get();
        if (id == null || id.isEmpty()) {
            id = newTraceId();
            TRACE.set(id);
        }
        return id;
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void clear() {
        TRACE.remove();
    }
}
