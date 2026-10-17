package com.myxhs.payment.simulator;

import com.myxhs.payment.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 支付回调模拟器
 * <p>
 * 在支付宝/微信 Mock 模式下，pay() 方法返回交易号后，
 * 此定时任务延迟 1~3 秒发送回调通知，模拟第三方支付平台的异步通知。
 * </p>
 * <p>
 * 保留 @Scheduled 的原因：
 * - 此任务是高频轮询（每 5 秒），不适合 XXL-Job 调度（调度开销大于执行时间）
 * - 每个实例都有独立的待回调扫描逻辑，通过 Redis SETNX 锁保证不重复发送
 * - 如果迁移到 XXL-Job，5 秒一次的调度会 Admin 造成不必要的压力
 * </p>
 */
@Slf4j
@Component
public class PayCallbackSimulator {

    private final StringRedisTemplate redisTemplate;
    // 用 ObjectProvider 懒加载 PaymentService，避免与 PaymentService→PayCallbackSimulator 形成构造器循环依赖
    private final ObjectProvider<PaymentService> paymentServiceProvider;

    public PayCallbackSimulator(@Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate,
                                ObjectProvider<PaymentService> paymentServiceProvider) {
        this.redisTemplate = redisTemplate;
        this.paymentServiceProvider = paymentServiceProvider;
    }

    /** 待回调 Redis Key 前缀 */
    private static final String CALLBACK_PENDING_PREFIX = "myxhs:payment:callback:pending:";
    /** 回调分布式锁前缀 */
    private static final String CALLBACK_LOCK_PREFIX = "myxhs:payment:callback:simulate:";
    /** 回调模拟成功率（0~1；ITER-002 阶段⑤配置化） */
    @org.springframework.beans.factory.annotation.Value("${payment.simulator.success-rate:0.9}")
    private double successRate = 0.9;
    /** 回调模拟延迟下限（毫秒；配置化） */
    @org.springframework.beans.factory.annotation.Value("${payment.simulator.callback-delay-min-ms:1000}")
    private long callbackDelayMinMs = 1000L;
    /** 回调模拟延迟上限（毫秒；配置化） */
    @org.springframework.beans.factory.annotation.Value("${payment.simulator.callback-delay-max-ms:3000}")
    private long callbackDelayMaxMs = 3000L;

    /** 随机延迟（毫秒）：[min, max) */
    private int randomCallbackDelay() {
        long span = Math.max(1, callbackDelayMaxMs - callbackDelayMinMs);
        return (int) (callbackDelayMinMs + ThreadLocalRandom.current().nextLong(span));
    }

    /**
     * 注册待回调支付单
     * <p>
     * 在 AlipayPayStrategy / WechatPayStrategy 的 pay() 方法中调用，
     * 将支付单标记为"待回调"存入 Redis。
     * </p>
     */
    public void registerCallback(String paymentNo, Integer payType) {
        String key = CALLBACK_PENDING_PREFIX + payType + ":" + paymentNo;
        // TTL 24 小时，避免支付服务连续故障超过短扫描窗口后丢失模拟回调
        redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()),
                java.time.Duration.ofHours(24));
        log.info("[回调模拟] 注册待回调: paymentNo={}, payType={}", paymentNo, payType);
    }

    /**
     * Mock 渠道真值模拟（ITER-002 阶段④：超时处置/对账核查的"查渠道"）
     * <p>
     * 语义：待回调标记存在 = 渠道交易**未决**（回调未送达 → 视为未支付）→ 返回 0；
     * 标记不存在 = **无法判定**（如 Redis 丢失）→ 返回 null（调用方"不处置"——"不知道"≠"没付"）。
     * 真实渠道接入时替换为渠道查询接口（alipay.trade.query / orderquery）。
     * </p>
     */
    public Integer querySimulatedChannelStatus(String paymentNo) {
        try (var cursor = redisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(CALLBACK_PENDING_PREFIX + "*")
                        .count(100)
                        .build())) {
            String suffix = ":" + paymentNo;
            while (cursor.hasNext()) {
                if (cursor.next().endsWith(suffix)) {
                    return 0; // 交易未决（未支付）
                }
            }
        } catch (Exception e) {
            log.warn("[回调模拟] 渠道真值查询失败(按不可达处理): paymentNo={}", paymentNo, e);
            return null;
        }
        return null; // 无法判定
    }

    /**
     * 取消待回调注册（ITER-002 深 Review：渠道交易关闭后不再发送模拟回调）
     * <p>
     * 由 mock 策略的 close() 调用——"关闭交易 = 不再有回调"，保持 mock 世界语义自洽
     * （否则关闭后仍可能触发迟到回调，命中"已关闭交易收到成功"的边界路径）。
     * </p>
     */
    public void cancel(String paymentNo) {
        try (var cursor = redisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(CALLBACK_PENDING_PREFIX + "*")
                        .count(100)
                        .build())) {
            String suffix = ":" + paymentNo;
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (key.endsWith(suffix)) {
                    redisTemplate.delete(key);
                    log.info("[回调模拟] 已取消待回调(渠道交易关闭): paymentNo={}, key={}", paymentNo, key);
                }
            }
        } catch (Exception e) {
            log.warn("[回调模拟] 取消待回调失败(不影响关闭): paymentNo={}", paymentNo, e);
        }
    }

    /** T-075（2026-08-14）：异步渠道退款回调模拟注册（原仅支付有模拟器，退款永不回调 → 退款单卡"退款中"） */
    private static final String REFUND_CALLBACK_PENDING_PREFIX = "myxhs:payment:refund-callback:pending:";

    public void registerRefundCallback(String refundNo) {
        String key = REFUND_CALLBACK_PENDING_PREFIX + refundNo;
        redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()),
                java.time.Duration.ofMinutes(5));
        log.info("[回调模拟] 注册待退款回调: refundNo={}", refundNo);
    }

    /**
     * 定时扫描并发送模拟回调（高频任务，保留 @Scheduled）
     * <p>
     * 每 5 秒扫描一次 Redis 中的待回调支付单，
     * 对每个支付单延迟 1~3 秒后发送回调。
     * </p>
     * <p>
     * 分布式考虑：
     * - 使用 Redis SETNX 分布式锁保证只有一个实例发送回调
     * - 键：payment:callback:simulate:{paymentNo}
     * </p>
     */
    @Scheduled(fixedDelay = 5000)
    public void simulateCallback() {
        // 使用 SCAN 代替 keys("*")，避免大数据量时阻塞 Redis
        var keys = new java.util.HashSet<String>();
        try (var cursor = redisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(CALLBACK_PENDING_PREFIX + "*")
                        .count(100)
                        .build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            // 定时线程无入站 traceId：每单开启新链路，保证回调→MQ→订单/通知全链可追
            com.myxhs.common.trace.TraceContextHolder.startNewTrace();
            try {
                // 分布式锁：防止多实例重复发送回调
                String lockKey = CALLBACK_LOCK_PREFIX + key.substring(CALLBACK_PENDING_PREFIX.length());
                Boolean locked = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "1", java.time.Duration.ofSeconds(30));
                if (Boolean.FALSE.equals(locked)) {
                    continue;
                }

                // 解析 key 获取 paymentNo 和 payType
                // key 格式：payment:callback:pending:{payType}:{paymentNo}
                String suffix = key.substring(CALLBACK_PENDING_PREFIX.length());
                String[] parts = suffix.split(":", 2);
                if (parts.length != 2) {
                    log.warn("[回调模拟] key 格式错误: {}", key);
                    continue;
                }
                Integer payType = Integer.parseInt(parts[0]);
                String paymentNo = parts[1];

                // 随机延迟（配置化：默认 1~3 秒）
                int delay = randomCallbackDelay();
                Thread.sleep(delay);

                // 模拟回调（配置化成功率：默认 90%）
                boolean success = ThreadLocalRandom.current().nextDouble() < successRate;
                String tradeNo = success ? "MOCK_TRADE_" + System.currentTimeMillis() : null;

                log.info("[回调模拟] 发送回调: paymentNo={}, payType={}, success={}, delay={}ms",
                        paymentNo, payType, success, delay);

                // 先处理回调，成功后再删除待回调标记；失败保留 key 等下一轮重试
                paymentServiceProvider.getObject().handlePayCallback(paymentNo, tradeNo, success);
                redisTemplate.delete(key);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[回调模拟] 被中断");
                break;
            } catch (Exception e) {
                log.error("[回调模拟] 回调发送失败: key={}", key, e);
            } finally {
                com.myxhs.common.trace.TraceContextHolder.clear();
            }
        }
    }

    /** T-075：退款回调模拟（每 5s 扫描，与支付回调同机制：90% 成功、1-3s 延迟） */
    @Scheduled(fixedDelay = 5000)
    public void simulateRefundCallback() {
        try {
            java.util.Set<String> keys = new java.util.HashSet<>();
            var scanOptions = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(REFUND_CALLBACK_PENDING_PREFIX + "*").count(100).build();
            try (var cursor = redisTemplate.scan(scanOptions)) {
                cursor.forEachRemaining(keys::add);
            }
            if (keys == null || keys.isEmpty()) {
                return;
            }
            for (String key : keys) {
                // 定时线程无入站 traceId：每单开启新链路（退款回调→MQ→订单回补/通知）
                com.myxhs.common.trace.TraceContextHolder.startNewTrace();
                try {
                    String refundNo = key.substring(REFUND_CALLBACK_PENDING_PREFIX.length());
                    String lockKey = "myxhs:payment:refund-callback:simulate:" + refundNo;
                    Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", java.time.Duration.ofSeconds(30));
                    if (!Boolean.TRUE.equals(locked)) {
                        continue;
                    }
                    int delay = randomCallbackDelay();
                    Thread.sleep(delay);
                    boolean success = ThreadLocalRandom.current().nextDouble() < successRate;
                    log.info("[回调模拟] 发送退款回调: refundNo={}, success={}, delay={}ms", refundNo, success, delay);
                    paymentServiceProvider.getObject().handleRefundCallback(refundNo, success);
                    redisTemplate.delete(key);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[回调模拟] 退款回调被中断");
                    break;
                } catch (Exception e) {
                    log.error("[回调模拟] 退款回调发送失败: key={}", key, e);
                } finally {
                    com.myxhs.common.trace.TraceContextHolder.clear();
                }
            }
        } catch (Exception e) {
            log.error("[回调模拟] 退款回调扫描异常", e);
        }
    }
}
