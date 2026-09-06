package com.myxhs.payment.job;

import com.myxhs.common.response.R;
import com.myxhs.payment.feign.OrderFeignClient;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 支付成功通知补偿任务（XXL-Job 分布式调度）
 * <p>
 * 问题场景：支付服务标记支付成功后，通过 MQ 通知订单服务更新状态。
 * 但 MQ 消息可能因以下原因丢失，导致"支付成功但订单仍为待支付"：
 * 1. RocketMQ Broker 宕机，消息未持久化
 * 2. 订单服务消费失败（DB 主键冲突、超时等）且重试次数耗尽
 * 3. 网络分区导致消息无法投递
 * <p>
 * 补偿策略：
 * 1. 扫描支付成功（status=1）且 paid_at 超过 5 分钟的支付记录
 * 2. 通过 Feign 调用订单服务查询订单状态
 * 3. 如果订单仍为"待支付"，重新发送 MQ 消息
 * 4. 记录通知重试次数，超过 10 次告警人工处理
 * <p>
 * 幂等性保证：
 * - 订单服务的 notifyPaySuccess 使用乐观锁（WHERE status = 待支付）
 * - 重复通知不会导致数据异常，只是多做了一次 DB 更新
 * - MQ 消息的 key 包含 orderId，RocketMQ 支持幂等消费
 * <p>
 * 分布式安全：
 * - XXL-Job 保证只有一个实例执行
 * - Redis 通知计数器保证重试次数准确（多实例共享计数）
 * - JdbcTemplate 查询无副作用
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentNotifyCompensateJob {

    private final JdbcTemplate paymentJdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final OrderFeignClient orderFeignClient;

    /** 通知计数器 Redis Key 前缀 */
    private static final String NOTIFY_COUNT_PREFIX = "myxhs:payment:notify:count:";
    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 10;
    /** 通知计数器 TTL（7 天后自动清除） */
    private static final Duration NOTIFY_COUNT_TTL = Duration.ofDays(7);
    /** 补偿窗口：支付成功后超过 5 分钟才开始补偿 */
    private static final int COMPENSATE_DELAY_MINUTES = 5;
    /** 单次扫描最大记录数 */
    private static final int BATCH_SIZE = 100;

    /**
     * 支付成功通知补偿（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0/2 * * * ?（每 2 分钟）
     */
    @XxlJob("paymentNotifyCompensateJob")
    public void compensatePaymentNotify() {
        log.info("[补偿任务] 支付成功通知补偿开始");
        try {
            int compensated = doCompensate();
            String msg = String.format("补偿完成: 重新通知=%d", compensated);
            XxlJobHelper.handleSuccess(msg);
        } catch (Exception e) {
            log.error("[补偿任务] 支付成功通知补偿异常", e);
            XxlJobHelper.handleFail("补偿异常: " + e.getMessage());
        }
    }

    private int doCompensate() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(COMPENSATE_DELAY_MINUTES);

        // 查询支付成功且超过补偿窗口的记录
        List<Long> orderIds = paymentJdbcTemplate.query(
                "SELECT order_id FROM t_payment " +
                        "WHERE status = 1 AND deleted = 0 AND paid_at < ? " +
                        "ORDER BY paid_at ASC LIMIT ?",
                (rs, rowNum) -> rs.getLong("order_id"),
                threshold, BATCH_SIZE
        );

        if (orderIds.isEmpty()) {
            log.debug("[补偿任务] 无需补偿的支付记录");
            return 0;
        }

        log.info("[补偿任务] 扫描到 {} 条支付成功记录，开始补偿", orderIds.size());
        int compensated = 0;

        for (Long orderId : orderIds) {
            try {
                String countKey = NOTIFY_COUNT_PREFIX + orderId;
                String countStr = stringRedisTemplate.opsForValue().get(countKey);
                int retryCount = 0;
                if (countStr != null) {
                    try {
                        retryCount = Integer.parseInt(countStr);
                    } catch (NumberFormatException nfe) {
                        log.warn("[补偿任务] Redis计数非法值: orderId={}, value={}", orderId, countStr);
                    }
                }

                if (retryCount >= MAX_RETRY_COUNT) {
                    log.warn("[补偿任务] 订单 {} 已达最大重试次数 {}，需人工处理", orderId, MAX_RETRY_COUNT);
                    continue;
                }

                // 已通知检查: 防无限循环(notifyPaySuccess本身幂等,但有标记后跳过更高效)
                String notifiedKey = "myxhs:payment:notified:" + orderId;
                if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(notifiedKey))) {
                    log.debug("[补偿任务] 订单 {} 已通知过，跳过", orderId);
                    continue;
                }

                // 通过 Feign 查询订单支付金额，间接判断订单状态：
                // - 返回成功且 data 非空：订单仍为待支付（只有待支付订单才返回支付金额）→ 需要补偿
                // - 返回成功但 data 为空/null：订单已不是待支付（已支付/已取消/已关闭）→ 无需补偿
                // - 返回失败或不可达（code=503 或超时）：订单服务不可用 → 稍后重试
                R<BigDecimal> payAmountResult = orderFeignClient.getOrderPayAmount(orderId);

                if (payAmountResult != null && payAmountResult.isSuccess()) {
                    BigDecimal payAmount = payAmountResult.getData();
                    if (payAmount != null && payAmount.compareTo(BigDecimal.ZERO) > 0) {
                        // 订单仍为待支付状态，查询 tradeNo 并重新通知
                        String tradeNo = paymentJdbcTemplate.queryForObject(
                                "SELECT payment_no FROM t_payment WHERE order_id = ? AND status = 1 AND deleted = 0 LIMIT 1",
                                String.class, orderId
                        );

                        R<Void> notifyResult = orderFeignClient.notifyPaySuccess(orderId, tradeNo);
                        if (notifyResult != null && notifyResult.isSuccess()) {
                            log.info("[补偿任务] 订单 {} 通知成功", orderId);
                            stringRedisTemplate.delete(countKey);
                            stringRedisTemplate.opsForValue().set(notifiedKey, "1", Duration.ofHours(1));
                            compensated++;
                        } else {
                            log.warn("[补偿任务] 订单 {} 通知失败: {}", orderId,
                                    notifyResult != null ? notifyResult.getMessage() : "null response");
                            incrementRetryCount(countKey);
                        }
                    } else {
                        // 订单已不是待支付（已支付/已取消/已关闭等），无需补偿
                        log.debug("[补偿任务] 订单 {} 已不是待支付，无需补偿", orderId);
                        stringRedisTemplate.delete(countKey);
                    }
                } else if (payAmountResult == null || payAmountResult.getCode() == 503) {
                    // 订单服务不可达，稍后重试
                    log.warn("[补偿任务] 订单服务不可达，跳过订单 {}", orderId);
                    incrementRetryCount(countKey);
                } else {
                    // 订单服务返回其他错误（如订单已删除），无需补偿
                    log.debug("[补偿任务] 订单 {} 查询返回错误(code={})，无需补偿", orderId,
                            payAmountResult != null ? payAmountResult.getCode() : -1);
                    stringRedisTemplate.delete(countKey);
                }
            } catch (Exception e) {
                log.error("[补偿任务] 处理订单 {} 补偿异常", orderId, e);
                String countKey = NOTIFY_COUNT_PREFIX + orderId;
                String countStr = stringRedisTemplate.opsForValue().get(countKey);
                int retryCount = 0;
                if (countStr != null) {
                    try {
                        retryCount = Integer.parseInt(countStr);
                    } catch (NumberFormatException nfe) {
                        log.warn("[补偿任务] Redis计数非法值: orderId={}, value={}", orderId, countStr);
                    }
                }
                incrementRetryCount(countKey);
            }
        }

        log.info("[补偿任务] 支付成功通知补偿完成: 重新通知 {} 条", compensated);
        return compensated;
    }

    /**
     * 递增通知重试计数器（原子操作保证分布式安全）
     * <p>
     * 使用 SETNX + INCR 原子组合：
     * 1. 先 SETNX 设置初始值 "1"（带 TTL），如果 Key 已存在则 SETNX 返回 false
     * 2. 如果 SETNX 返回 false（Key 已存在），用 INCR 原子递增
     * <p>
     * 为什么不能先 GET 再判断 set/increment？
     * 两个实例可能同时 GET 到 count=0，都执行 set(key,"1")，导致计数丢失。
     * SETNX 是原子的，只有一个实例能成功设置初始值。
     * </p>
     */
    private void incrementRetryCount(String countKey) {
        Boolean setSuccess = stringRedisTemplate.opsForValue()
                .setIfAbsent(countKey, "1", NOTIFY_COUNT_TTL);
        if (Boolean.FALSE.equals(setSuccess)) {
            // Key 已存在，原子递增（INCR 是原子操作，TTL 不受影响）
            stringRedisTemplate.opsForValue().increment(countKey);
        }
        // SETNX 成功：初始值已设为 "1"，且带 TTL，无需额外操作
    }
}
