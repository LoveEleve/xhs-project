package com.myxhs.payment.simulator;

import com.myxhs.payment.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
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
    private final PaymentService paymentService;

    public PayCallbackSimulator(@Qualifier("stringRedisTemplate") StringRedisTemplate redisTemplate,
                                PaymentService paymentService) {
        this.redisTemplate = redisTemplate;
        this.paymentService = paymentService;
    }

    /** 待回调 Redis Key 前缀 */
    private static final String CALLBACK_PENDING_PREFIX = "myxhs:payment:callback:pending:";
    /** 回调分布式锁前缀 */
    private static final String CALLBACK_LOCK_PREFIX = "myxhs:payment:callback:simulate:";
    /** 回调模拟成功率（0~1） */
    private static final double SUCCESS_RATE = 0.9;

    /**
     * 注册待回调支付单
     * <p>
     * 在 AlipayPayStrategy / WechatPayStrategy 的 pay() 方法中调用，
     * 将支付单标记为"待回调"存入 Redis。
     * </p>
     */
    public void registerCallback(String paymentNo, Integer payType) {
        String key = CALLBACK_PENDING_PREFIX + payType + ":" + paymentNo;
        // TTL 5 分钟（足够模拟器扫描并发送回调）
        redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()),
                java.time.Duration.ofMinutes(5));
        log.info("[回调模拟] 注册待回调: paymentNo={}, payType={}", paymentNo, payType);
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

                // 随机延迟 1~3 秒
                int delay = 1000 + ThreadLocalRandom.current().nextInt(2000);
                Thread.sleep(delay);

                // 删除待回调标记
                redisTemplate.delete(key);

                // 模拟回调：90% 成功率
                boolean success = ThreadLocalRandom.current().nextDouble() < SUCCESS_RATE;
                String tradeNo = success ? "MOCK_TRADE_" + System.currentTimeMillis() : null;

                log.info("[回调模拟] 发送回调: paymentNo={}, payType={}, success={}, delay={}ms",
                        paymentNo, payType, success, delay);

                // 通过 PaymentService 处理回调（内部会走完整的支付成功/失败逻辑）
                paymentService.handlePayCallback(paymentNo, tradeNo, success);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[回调模拟] 被中断");
                break;
            } catch (Exception e) {
                log.error("[回调模拟] 回调发送失败: key={}", key, e);
            }
        }
    }
}
