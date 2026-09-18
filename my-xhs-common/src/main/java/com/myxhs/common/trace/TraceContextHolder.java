package com.myxhs.common.trace;

/**
 * 全链路流量染色上下文持有者
 * <p>
 * 基于 ThreadLocal 管理 TraceContext 的生命周期。
 * 所有业务代码通过此类读取染色标记，不直接操作 ThreadLocal。
 * </p>
 * <p>
 * 内存泄漏防护：
 * - HTTP 请求：TraceIdConfig 拦截器 afterCompletion 中清理
 * - MQ 消费：Consumer finally 中清理
 * - 异步线程：TaskDecorator finally 中清理
 * 三层保障，确保 ThreadLocal 不泄漏。
 * </p>
 */
public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    /** MDC 中的 traceId Key（与 TraceIdConfig/MqTraceHelper 保持一致） */
    private static final String MDC_TRACE_KEY = "traceId";

    private TraceContextHolder() {
    }

    /**
     * 为无入口的后台线程（定时任务/模拟器/补偿 Job）开启一条新链路：
     * 生成 traceId → 写入 ThreadLocal + MDC（+ SkyWalking correlation）。
     * <p>使用方必须在 finally 中调用 {@link #clear()}（会同时清理 MDC）。</p>
     */
    public static TraceContext startNewTrace() {
        TraceContext ctx = new TraceContext();
        ctx.setTraceId(java.util.UUID.randomUUID().toString().replace("-", ""));
        set(ctx);
        org.slf4j.MDC.put(MDC_TRACE_KEY, ctx.getTraceId());
        return ctx;
    }

    public static void set(TraceContext ctx) {
        CONTEXT.set(ctx);
        // 同步注入 SkyWalking Correlation：将业务 traceId 挂到当前 Span 的 Tag
        // 配合 agent.config: correlation.auto_tag_keys=traceId
        injectSkyWalkingCorrelation(ctx);
    }

    public static TraceContext get() {
        return CONTEXT.get();
    }

    /**
     * 获取上下文（不存在则创建空上下文）
     * <p>
     * 避免 NPE，适用于需要写入染色标记的场景。
     * </p>
     */
    public static TraceContext getOrCreate() {
        TraceContext ctx = CONTEXT.get();
        if (ctx == null) {
            ctx = new TraceContext();
            CONTEXT.set(ctx);
        }
        return ctx;
    }

    /**
     * 清理 ThreadLocal（必须在请求/消费/任务结束时调用）
     */
    public static void clear() {
        CONTEXT.remove();
        org.slf4j.MDC.remove(MDC_TRACE_KEY);
        clearSkyWalkingCorrelation();
    }

    // ==================== 便捷方法 ====================

    /**
     * 判断当前请求是否为压测流量
     * <p>
     * 压测流量写影子表，正常流量写生产表。
     * </p>
     */
    public static boolean isPressureTest() {
        TraceContext ctx = get();
        return ctx != null && "true".equals(ctx.getPressureTest());
    }

    /**
     * 获取灰度标记（默认 stable）
     */
    public static String getGrayTag() {
        TraceContext ctx = get();
        return ctx != null && ctx.getGrayTag() != null ? ctx.getGrayTag() : "stable";
    }

    /**
     * 获取 TraceId
     */
    public static String getTraceId() {
        TraceContext ctx = get();
        return ctx != null ? ctx.getTraceId() : null;
    }

    /**
     * 获取 UserId
     */
    public static String getUserId() {
        TraceContext ctx = get();
        return ctx != null ? ctx.getUserId() : null;
    }

    /**
     * 深拷贝当前上下文（用于跨线程传递）
     * <p>
     * 必须深拷贝，不能直接传引用。
     * 否则父线程清理 ThreadLocal 后，子线程拿到的是 null。
     * </p>
     */
    public static TraceContext snapshot() {
        TraceContext src = get();
        if (src == null) {
            return null;
        }
        TraceContext copy = new TraceContext();
        copy.setTraceId(src.getTraceId());
        copy.setUserId(src.getUserId());
        copy.setGrayTag(src.getGrayTag());
        copy.setApiVersion(src.getApiVersion());
        copy.setAbGroup(src.getAbGroup());
        copy.setPressureTest(src.getPressureTest());
        return copy;
    }

    // ==================== SkyWalking 关联 ====================

    /**
     * 将业务 traceId 注入到 SkyWalking Correlation Context（Span Tag）
     * <p>
     * 当 SkyWalking Agent 加载时，每个 Span 自动携带 traceId Tag，
     * 实现"用业务 ID 搜索 SkyWalking 链路"。
     * Agent 未加载时（本地开发）静默降级，不影响业务。
     * </p>
     */
    /**
     * SkyWalking 桥接（A2 性能修复）：Class/Method 静态缓存一次。
     * <p>原实现每次请求 Class.forName + getMethod：无 agent 时每次抛 CNFE 并争抢
     * 类加载锁，压测中 92/99 个 Tomcat 线程 BLOCKED（吞吐被压到 ~1k RPS）。
     * 修复后每请求仅一次静态字段判断 + 反射调用（agent 存在时）。</p>
     */
    private static final class SkyWalkingBridge {
        static final java.lang.reflect.Method PUT_CORRELATION;
        static final java.lang.reflect.Method REMOVE_CORRELATION;

        static {
            java.lang.reflect.Method put = null;
            java.lang.reflect.Method remove = null;
            try {
                Class<?> swContext = Class.forName("org.apache.skywalking.apm.toolkit.trace.TraceContext");
                put = swContext.getMethod("putCorrelation", String.class, String.class);
                remove = swContext.getMethod("removeCorrelation", String.class);
            } catch (Throwable ignored) {
                // SkyWalking Agent 未加载：桥接不可用，业务无感
            }
            PUT_CORRELATION = put;
            REMOVE_CORRELATION = remove;
        }
    }

    private static void injectSkyWalkingCorrelation(TraceContext ctx) {
        if (ctx == null || ctx.getTraceId() == null || SkyWalkingBridge.PUT_CORRELATION == null) {
            return;
        }
        try {
            SkyWalkingBridge.PUT_CORRELATION.invoke(null, "traceId", ctx.getTraceId());
        } catch (Exception ignored) {
            // 桥接异常不影响业务
        }
    }

    private static void clearSkyWalkingCorrelation() {
        if (SkyWalkingBridge.REMOVE_CORRELATION == null) {
            return;
        }
        try {
            SkyWalkingBridge.REMOVE_CORRELATION.invoke(null, "traceId");
        } catch (Exception ignored) {
            // 桥接异常不影响业务
        }
    }
}
