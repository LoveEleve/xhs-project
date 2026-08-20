package com.myxhs.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jdk.internal.vm.annotation.Contended;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 业务指标收集器
 * 
 * 提供订单、库存、支付等核心业务指标。
 * 指标通过 /actuator/prometheus 端点暴露，由 Prometheus 采集。
 * 
 * 指标类型：
 * - Counter：只增不减，适合计数（订单创建数、支付成功数）
 * - Timer：耗时分布，适合记录操作延迟（下单耗时 P50/P90/P99）
 * - Gauge：可增可减，适合当前状态（热点 SKU 数、MQ 积压量）
 */
@Contended
@Component
public class BusinessMetrics {

    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public BusinessMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    // ==================== 订单指标 ====================

    /** 记录订单创建（成功/失败） */
    public void recordOrderCreated(String status) {
        counter("orders.created.total", "status", status).increment();
    }

    /** 记录订单创建耗时 */
    public void recordOrderCreateLatency(long durationMs) {
        timer("orders.create.latency").record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 记录订单支付结果 */
    public void recordOrderPaid(String channel, boolean success) {
        counter("orders.paid.total", "channel", channel, "result", success ? "success" : "fail").increment();
    }

    /** 记录订单超时关闭 */
    public void recordOrderTimeoutClose() {
        counter("orders.timeout.closed").increment();
    }

    // ==================== 库存指标 ====================

    /** 记录库存预扣结果 */
    public void recordPreDeduct(String result) {
        counter("inventory.prededuct.total", "result", result).increment();
    }

    /** 记录库存预扣耗时 */
    public void recordPreDeductLatency(long durationMs) {
        timer("inventory.prededuct.latency").record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 记录库存确认/释放 */
    public void recordInventoryAction(String action) {
        counter("inventory.action.total", "action", action).increment();
    }

    // ==================== 支付指标 ====================

    /** 记录支付回调结果 */
    public void recordPaymentCallback(String status) {
        counter("payment.callback.total", "status", status).increment();
    }

    // ==================== Feed 指标 ====================

    /** 记录 Feed 推送 */
    public void recordFeedPush(String mode) {
        counter("feed.push.total", "mode", mode).increment();
    }

    /** 记录 Feed 推送延迟 */
    public void recordFeedPushLatency(long durationMs) {
        timer("feed.push.latency").record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ==================== 优惠券指标 ====================

    /** 记录优惠券领取/使用 */
    public void recordCouponAction(String action, boolean success) {
        counter("coupon.action.total", "action", action, "result", success ? "success" : "fail").increment();
    }

    // ==================== MQ 指标 ====================

    /** 记录 MQ 消费结果（成功/失败） */
    public void recordMqConsume(String topic, String consumerGroup, boolean success) {
        counter("mq.consume.total", "topic", topic, "consumerGroup", consumerGroup,
                "result", success ? "success" : "fail").increment();
    }

    /** 记录 DLQ 死信消息 */
    public void recordDlqMessage(String consumerGroup, String topic) {
        counter("myxhs.mq.dlq.total", "consumerGroup", consumerGroup, "topic", topic).increment();
    }

    // ==================== 内部方法 ====================

    /**
     * P-D42 + P1 修复（2026-08-13）：启动时预注册全部指标（**带业务标签空值**）。
     * <p>
     * 原实现预注册无标签 counter——与业务调用的带标签 counter 同名不同标签，
     * Prometheus 输出层（simpleclient）同名 label 集合冲突 → 带标签系列被吞 → 业务指标恒 0。
     * 修复：预注册使用与业务调用一致的标签名（值置空串），消除冲突且保留"面板不空白"。
     * </p>
     */
    @jakarta.annotation.PostConstruct
    public void preRegister() {
        MeterRegistry registry = meterRegistry();
        java.util.List<io.micrometer.core.instrument.Meter> meters = java.util.List.of(
                Counter.builder("orders.created.total").tags("status", "").description("业务指标: orders.created.total").register(registry),
                Counter.builder("orders.paid.total").tags("channel", "", "result", "").description("业务指标: orders.paid.total").register(registry),
                Counter.builder("orders.timeout.closed").description("业务指标: orders.timeout.closed").register(registry),
                Counter.builder("inventory.prededuct.total").tags("result", "").description("业务指标: inventory.prededuct.total").register(registry),
                Counter.builder("inventory.action.total").tags("action", "").description("业务指标: inventory.action.total").register(registry),
                Counter.builder("payment.callback.total").tags("status", "").description("业务指标: payment.callback.total").register(registry),
                Counter.builder("feed.push.total").tags("mode", "").description("业务指标: feed.push.total").register(registry),
                Counter.builder("coupon.action.total").tags("action", "", "result", "").description("业务指标: coupon.action.total").register(registry),
                Counter.builder("mq.consume.total").tags("topic", "", "consumerGroup", "", "result", "").description("业务指标: mq.consume.total").register(registry),
                Counter.builder("myxhs.mq.dlq.total").tags("consumerGroup", "", "topic", "").description("业务指标: myxhs.mq.dlq.total").register(registry),
                Timer.builder("orders.create.latency").description("业务延迟: orders.create.latency").publishPercentiles(0.5, 0.9, 0.99).register(registry),
                Timer.builder("inventory.prededuct.latency").description("业务延迟: inventory.prededuct.latency").publishPercentiles(0.5, 0.9, 0.99).register(registry),
                Timer.builder("feed.push.latency").description("业务延迟: feed.push.latency").publishPercentiles(0.5, 0.9, 0.99).register(registry)
        );
    }

    private MeterRegistry meterRegistry() {
        return meterRegistryProvider.getObject();
    }

    private Counter counter(String name, String... tags) {
        io.micrometer.core.instrument.Tags micrometerTags = io.micrometer.core.instrument.Tags.empty();
        for (int i = 0; i + 1 < tags.length; i += 2) {
            micrometerTags = micrometerTags.and(tags[i], tags[i + 1]);
        }
        return Counter.builder(name)
                .description("业务指标: " + name)
                .tags(micrometerTags)
                .register(meterRegistry());
    }

    private Timer timer(String name) {
        return Timer.builder(name)
                .description("业务延迟: " + name)
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(meterRegistry());
    }
}
