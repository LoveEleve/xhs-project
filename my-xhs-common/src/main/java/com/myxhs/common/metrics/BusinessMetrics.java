package com.myxhs.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 业务指标定义
 * <p>
 * 统一管理所有业务级 Prometheus 指标。
 * 各服务通过注入此 Bean 来记录业务指标，Prometheus 自动采集。
 * </p>
 * <p>
 * 指标命名规范（Prometheus 最佳实践）：
 * - 前缀：myxhs_（项目标识）
 * - Counter 后缀：_total
 * - Histogram/Timer 后缀：_duration_seconds
 * - Gauge 无特殊后缀
 * </p>
 * <p>
 * 设计要点：
 * 1. Counter/Timer 使用 registry.counter()/registry.timer() 快捷方法（内部有缓存，避免重复构建 Meter.Id）
 * 2. Gauge 使用 AtomicLong 持有值引用，确保 Prometheus 采集时能读到最新值
 *    （Gauge.builder(() -> lag) 中 lag 是基本类型参数，lambda 捕获的是值副本，不会更新！）
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * businessMetrics.recordOrderCreate("success");
 * businessMetrics.recordMqConsumeLag("ORDER_CREATED", "order-consumer-group", 1234);
 * </pre>
 * </p>
 */
@Slf4j
@Component
public class BusinessMetrics {

    private final MeterRegistry registry;

    /**
     * Gauge 值缓存：key = "metricName:tag1=val1,tag2=val2", value = AtomicLong
     * <p>
     * 为什么需要这个？
     * Gauge 与 Counter/Timer 不同，它不是"累加"而是"当前值"。
     * Micrometer 的 Gauge 通过 Supplier 读取值，如果 Supplier 捕获的是基本类型副本，
     * 后续更新不会反映到 Gauge 上。必须用 AtomicLong 等引用类型持有值。
     * </p>
     */
    private final ConcurrentHashMap<String, AtomicLong> gaugeValues = new ConcurrentHashMap<>();

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ==================== 订单指标 ====================

    /**
     * 记录订单创建
     *
     * @param status success / fail / timeout
     */
    public void recordOrderCreate(String status) {
        registry.counter("myxhs_order_create_total", "status", status).increment();
    }

    /**
     * 记录订单状态变更
     *
     * @param fromStatus 原状态
     * @param toStatus   新状态
     */
    public void recordOrderStatusChange(String fromStatus, String toStatus) {
        registry.counter("myxhs_order_status_change_total", "from", fromStatus, "to", toStatus).increment();
    }

    // ==================== 支付指标 ====================

    /**
     * 记录支付结果
     *
     * @param payType 支付方式（alipay / wechat / mock）
     * @param status  success / fail / timeout
     */
    public void recordPayment(String payType, String status) {
        registry.counter("myxhs_payment_total", "pay_type", payType, "status", status).increment();
    }

    // ==================== 库存指标 ====================

    /**
     * 记录库存扣减耗时
     *
     * @param durationMs 耗时（毫秒）
     * @param success    是否成功
     */
    public void recordInventoryDeduct(long durationMs, boolean success) {
        Timer.builder("myxhs_inventory_deduct_duration_seconds")
                .description("库存扣减耗时")
                .tag("status", success ? "success" : "fail")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ==================== 缓存指标 ====================

    /**
     * 记录缓存命中/未命中
     *
     * @param cacheName 缓存名称（如 user:info, note:detail）
     * @param hit       是否命中
     */
    public void recordCacheAccess(String cacheName, boolean hit) {
        registry.counter("myxhs_cache_access_total", "cache", cacheName, "result", hit ? "hit" : "miss").increment();
    }

    // ==================== MQ 指标 ====================

    /**
     * 记录 MQ 消费结果
     *
     * @param topic  Topic 名称
     * @param status success / fail / retry
     */
    public void recordMqConsume(String topic, String status) {
        registry.counter("myxhs_mq_consume_total", "topic", topic, "status", status).increment();
    }

    /**
     * 记录 MQ 消费积压量
     * <p>
     * 注意：Gauge 的值通过 AtomicLong 引用持有，确保 Prometheus 采集时能读到最新值。
     * 首次调用时注册 Gauge（绑定 AtomicLong 的 Supplier），后续调用只更新 AtomicLong 的值。
     * </p>
     *
     * @param topic         Topic 名称
     * @param consumerGroup 消费者组
     * @param lag           积压量
     */
    public void recordMqConsumeLag(String topic, String consumerGroup, long lag) {
        String key = "myxhs_mq_consume_lag:topic=" + topic + ",consumer_group=" + consumerGroup;
        AtomicLong gaugeValue = gaugeValues.computeIfAbsent(key, k -> {
            AtomicLong value = new AtomicLong(lag);
            // 首次注册 Gauge，绑定 AtomicLong 的 Supplier
            io.micrometer.core.instrument.Gauge.builder("myxhs_mq_consume_lag", value, AtomicLong::doubleValue)
                    .description("MQ 消费积压量")
                    .tag("topic", topic)
                    .tag("consumer_group", consumerGroup)
                    .register(registry);
            return value;
        });
        // 更新 Gauge 值（后续调用走这里）
        gaugeValue.set(lag);
    }

    // ==================== 登录/注册指标 ====================

    /**
     * 记录登录结果
     *
     * @param status success / fail / locked
     */
    public void recordLogin(String status) {
        registry.counter("myxhs_login_total", "status", status).increment();
    }

    // ==================== 通用计时器 ====================

    /**
     * 计时执行一段逻辑，自动记录耗时指标
     *
     * @param metricName 指标名称
     * @param tags       标签（key1, value1, key2, value2, ...）
     * @param action     要执行的逻辑
     * @param <T>        返回类型
     * @return 执行结果
     */
    public <T> T timeExecution(String metricName, String[] tags, Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = action.get();
            sample.stop(Timer.builder(metricName)
                    .tags(tags)
                    .tag("status", "success")
                    .register(registry));
            return result;
        } catch (Exception e) {
            sample.stop(Timer.builder(metricName)
                    .tags(tags)
                    .tag("status", "error")
                    .register(registry));
            throw e;
        }
    }
}
