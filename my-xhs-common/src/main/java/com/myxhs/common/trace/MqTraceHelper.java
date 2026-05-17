package com.myxhs.common.trace;

import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * MQ 消息全链路染色标记透传工具
 * <p>
 * 解决 MQ 场景下染色标记断裂问题：
 * - Producer 发送时：从 TraceContextHolder 取出染色标记，注入到 Message Header
 * - Consumer 消费时：从 Message Header 取出染色标记，恢复到 TraceContextHolder + MDC
 * </p>
 * <p>
 * 使用方式：
 * <pre>
 * // Producer 端
 * Message<?> msg = MqTraceHelper.wrapWithTraceContext(MessageBuilder.withPayload(payload).build());
 * rocketMQTemplate.asyncSend(topic, msg, callback);
 *
 * // Consumer 端
 * public void onMessage(MessageExt msg) {
 *     MqTraceHelper.restoreTraceContext(msg);
 *     try {
 *         // 业务逻辑...（可通过 TraceContextHolder.isPressureTest() 判断压测流量）
 *     } finally {
 *         MqTraceHelper.clearTraceContext();
 *     }
 * }
 * </pre>
 * </p>
 */
public final class MqTraceHelper {

    /** MQ Message Header 中各染色标记的 Key */
    public static final String MQ_TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MQ_USER_ID_HEADER = "X-User-Id";
    public static final String MQ_GRAY_TAG_HEADER = "X-Gray-Tag";
    public static final String MQ_API_VERSION_HEADER = "X-Api-Version";
    public static final String MQ_AB_GROUP_HEADER = "X-AB-Group";
    public static final String MQ_PRESSURE_TEST_HEADER = "X-Pressure-Test";

    /** MDC 中 TraceId 的 Key（与 TraceIdConfig 保持一致） */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private MqTraceHelper() {
    }

    // ==================== 发送端 ====================

    /**
     * 发送端：将当前线程的完整 TraceContext 注入到 MQ 消息 Header
     * <p>
     * 优先从 TraceContextHolder 读取，兜底从 MDC 读取 traceId。
     * </p>
     */
    public static <T> Message<T> wrapWithTraceContext(Message<T> message) {
        TraceContext ctx = TraceContextHolder.get();
        if (ctx != null) {
            MessageBuilder<T> builder = MessageBuilder.fromMessage(message);
            setHeaderIfPresent(builder, MQ_TRACE_ID_HEADER, ctx.getTraceId());
            setHeaderIfPresent(builder, MQ_USER_ID_HEADER, ctx.getUserId());
            setHeaderIfPresent(builder, MQ_GRAY_TAG_HEADER, ctx.getGrayTag());
            setHeaderIfPresent(builder, MQ_API_VERSION_HEADER, ctx.getApiVersion());
            setHeaderIfPresent(builder, MQ_AB_GROUP_HEADER, ctx.getAbGroup());
            setHeaderIfPresent(builder, MQ_PRESSURE_TEST_HEADER, ctx.getPressureTest());
            return builder.build();
        }

        // 兜底：从 MDC 读取 traceId（定时任务场景可能只有 MDC 没有 TraceContext）
        String traceId = MDC.get(TRACE_ID_MDC_KEY);
        if (traceId != null && !traceId.isEmpty()) {
            return MessageBuilder.fromMessage(message)
                    .setHeader(MQ_TRACE_ID_HEADER, traceId)
                    .build();
        }
        return message;
    }

    /**
     * 向后兼容：仅注入 TraceId（旧代码调用不报错）
     *
     * @deprecated 请使用 {@link #wrapWithTraceContext(Message)}
     */
    @Deprecated
    public static <T> Message<T> wrapWithTraceId(Message<T> message) {
        return wrapWithTraceContext(message);
    }

    // ==================== 消费端 ====================

    /**
     * 消费端：从 RocketMQ MessageExt 中恢复完整 TraceContext 到 ThreadLocal + MDC
     * <p>
     * 必须在 finally 中调用 {@link #clearTraceContext()} 清理。
     * </p>
     */
    public static void restoreTraceContext(org.apache.rocketmq.common.message.MessageExt msg) {
        TraceContext ctx = new TraceContext();
        ctx.setTraceId(msg.getUserProperty(MQ_TRACE_ID_HEADER));
        ctx.setUserId(msg.getUserProperty(MQ_USER_ID_HEADER));
        ctx.setGrayTag(msg.getUserProperty(MQ_GRAY_TAG_HEADER));
        ctx.setApiVersion(msg.getUserProperty(MQ_API_VERSION_HEADER));
        ctx.setAbGroup(msg.getUserProperty(MQ_AB_GROUP_HEADER));
        ctx.setPressureTest(msg.getUserProperty(MQ_PRESSURE_TEST_HEADER));
        TraceContextHolder.set(ctx);

        // 同步设置 MDC（日志携带 traceId）
        if (ctx.getTraceId() != null && !ctx.getTraceId().isEmpty()) {
            MDC.put(TRACE_ID_MDC_KEY, ctx.getTraceId());
        }
    }

    /**
     * 向后兼容：仅恢复 TraceId（旧代码调用不报错）
     *
     * @deprecated 请使用 {@link #restoreTraceContext(org.apache.rocketmq.common.message.MessageExt)}
     */
    @Deprecated
    public static void restoreTraceId(org.apache.rocketmq.common.message.MessageExt msg) {
        restoreTraceContext(msg);
    }

    /**
     * 消费端：从 traceId 字符串恢复到 MDC
     */
    public static void restoreTraceId(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
        }
    }

    // ==================== 清理 ====================

    /**
     * 清理 TraceContext + MDC（必须在 Consumer finally 中调用）
     */
    public static void clearTraceContext() {
        TraceContextHolder.clear();
        MDC.remove(TRACE_ID_MDC_KEY);
    }

    /**
     * 向后兼容
     *
     * @deprecated 请使用 {@link #clearTraceContext()}
     */
    @Deprecated
    public static void clearTraceId() {
        clearTraceContext();
    }

    // ==================== 私有方法 ====================

    private static <T> void setHeaderIfPresent(MessageBuilder<T> builder, String key, String value) {
        if (value != null && !value.isEmpty()) {
            builder.setHeader(key, value);
        }
    }
}
