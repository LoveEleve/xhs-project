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

    private TraceContextHolder() {
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
    private static void injectSkyWalkingCorrelation(TraceContext ctx) {
        if (ctx == null || ctx.getTraceId() == null) return;
        try {
            Class<?> swContext = Class.forName(
                "org.apache.skywalking.apm.toolkit.trace.TraceContext");
            java.lang.reflect.Method putCorrelation = swContext.getMethod(
                "putCorrelation", String.class, String.class);
            putCorrelation.invoke(null, "traceId", ctx.getTraceId());
        } catch (Exception ignored) {
            // SkyWalking Agent 未加载
        }
    }

    private static void clearSkyWalkingCorrelation() {
        try {
            Class<?> swContext = Class.forName(
                "org.apache.skywalking.apm.toolkit.trace.TraceContext");
            java.lang.reflect.Method removeCorrelation = swContext.getMethod(
                "removeCorrelation", String.class);
            removeCorrelation.invoke(null, "traceId");
        } catch (Exception ignored) {
        }
    }
}
