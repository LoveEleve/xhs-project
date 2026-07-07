package com.myxhs.common.trace;

import lombok.extern.slf4j.Slf4j;

/**
 * 业务 Span 辅助工具
 * 
 * SkyWalking 自动埋点覆盖 HTTP/Feign/MQ 层面的 Span，
 * 但无法覆盖业务语义。本工具在关键业务节点手动创建 Span。
 * 
 * 使用示例：
 * BizSpanHelper.trace("inventory.preDeduct", span -> {
 *     span.tag("skuId", String.valueOf(skuId));
 *     span.tag("quantity", String.valueOf(qty));
 *     // ... 业务逻辑 ...
 * });
 * 
 * 注意：如果 SkyWalking Agent 未加载（本地开发），自动降级为空操作。
 */
@Slf4j
public final class BizSpanHelper {

    private static final boolean SKYWALKING_AVAILABLE;

    static {
        boolean available = false;
        try {
            Class.forName("org.apache.skywalking.apm.toolkit.trace.TraceContext");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        SKYWALKING_AVAILABLE = available;
    }

    private BizSpanHelper() {}

    /**
     * 创建业务 Span 并执行
     * 
     * @param operationName Span 名称，建议格式：{模块}.{操作}
     * @param tags          Tag 键值对（可选）
     * @param runnable      业务逻辑
     */
    public static void trace(String operationName, Runnable runnable, String... tags) {
        if (!SKYWALKING_AVAILABLE) {
            runnable.run();
            return;
        }

        try {
            Object span = SkyWalkingSpanHelper.createEntrySpan(operationName);
            // 添加 Tag
            for (int i = 0; i + 1 < tags.length; i += 2) {
                SkyWalkingSpanHelper.tag(span, tags[i], tags[i + 1]);
            }
            runnable.run();
        } catch (Exception e) {
            SkyWalkingSpanHelper.log(e);
            throw e;
        } finally {
            SkyWalkingSpanHelper.stopSpan();
        }
    }

    /**
     * SkyWalking 反射调用辅助（避免编译期强依赖 skywalking-apm-toolkit）
     * <p>
     * Method 对象在 static 初始化块中预加载并缓存，避免每次调用都执行反射查找。
     * </p>
     */
    private static class SkyWalkingSpanHelper {
        private static final Class<?> TRACE_CONTEXT_CLASS;
        private static final java.lang.reflect.Method CREATE_ENTRY_SPAN;
        private static final java.lang.reflect.Method ACTIVE_SPAN;
        private static final java.lang.reflect.Method SPAN_TAG;
        private static final java.lang.reflect.Method SPAN_LOG;
        private static final java.lang.reflect.Method STOP_SPAN;

        static {
            Class<?> traceContextClass = null;
            java.lang.reflect.Method createEntrySpan = null;
            java.lang.reflect.Method activeSpan = null;
            java.lang.reflect.Method spanTag = null;
            java.lang.reflect.Method spanLog = null;
            java.lang.reflect.Method stopSpan = null;
            try {
                traceContextClass = Class.forName("org.apache.skywalking.apm.toolkit.trace.TraceContext");
                createEntrySpan = traceContextClass.getMethod("createEntrySpan", String.class);
                activeSpan = traceContextClass.getMethod("activeSpan");
                stopSpan = traceContextClass.getMethod("stopSpan");
                Class<?> spanClass = Class.forName("org.apache.skywalking.apm.toolkit.trace.Span");
                spanTag = spanClass.getMethod("tag", String.class, String.class);
                spanLog = spanClass.getMethod("log", Throwable.class);
            } catch (Exception ignored) {}
            TRACE_CONTEXT_CLASS = traceContextClass;
            CREATE_ENTRY_SPAN = createEntrySpan;
            ACTIVE_SPAN = activeSpan;
            SPAN_TAG = spanTag;
            SPAN_LOG = spanLog;
            STOP_SPAN = stopSpan;
        }

        static Object createEntrySpan(String name) {
            if (CREATE_ENTRY_SPAN == null) return null;
            try {
                return CREATE_ENTRY_SPAN.invoke(null, name);
            } catch (Exception e) {
                return null;
            }
        }

        static void tag(Object span, String key, String value) {
            if (span == null || SPAN_TAG == null) return;
            try {
                SPAN_TAG.invoke(span, key, value);
            } catch (Exception ignored) {}
        }

        static void log(Throwable e) {
            if (ACTIVE_SPAN == null || SPAN_LOG == null) return;
            try {
                Object span = ACTIVE_SPAN.invoke(null);
                if (span != null) {
                    SPAN_LOG.invoke(span, e);
                }
            } catch (Exception ignored) {}
        }

        static void stopSpan() {
            if (STOP_SPAN == null) return;
            try {
                STOP_SPAN.invoke(null);
            } catch (Exception ignored) {}
        }
    }
}
