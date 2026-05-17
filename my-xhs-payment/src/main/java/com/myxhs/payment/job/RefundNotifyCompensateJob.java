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
import java.util.List;

/**
 * 退款成功通知补偿任务（XXL-Job 分布式调度）
 * <p>
 * 问题场景：退款成功后，通过 MQ 通知订单服务释放库存 + 退优惠券 + 更新状态。
 * 但 MQ 消息可能丢失，导致"退款成功但订单仍为已支付/已退款未确认"：
 * 1. RocketMQ Broker 宕机
 * 2. 订单服务消费失败且重试耗尽
 * 3. 网络分区
 * <p>
 * 补偿策略：
 * 1. 扫描退款成功（status=1）且 success_at 超过 5 分钟的退款记录
 * 2. 通过 Feign 调用订单服务重新通知退款成功
 * 3. 记录通知重试次数，超过 10 次告警人工处理
 * <p>
 * 幂等性保证：
 * - 订单服务的 notifyRefundSuccess 使用乐观锁，重复通知不会导致数据异常
 * - 退款成功是终态，不会因为重复通知而回退
 * <p>
 * 分布式安全：
 * - XXL-Job 保证只有一个实例执行
 * - Redis 通知计数器保证重试次数准确（多实例共享计数）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundNotifyCompensateJob {

    private final JdbcTemplate paymentJdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final OrderFeignClient orderFeignClient;

    /** 通知计数器 Redis Key 前缀 */
    private static final String NOTIFY_COUNT_PREFIX = "payment:refund:notify:count:";
    /** 最大重试次数 */
    private static final int MAX_RETRY_COUNT = 10;
    /** 通知计数器 TTL（7 天后自动清除） */
    private static final Duration NOTIFY_COUNT_TTL = Duration.ofDays(7);
    /** 补偿窗口：退款成功后超过 5 分钟才开始补偿 */
    private static final int COMPENSATE_DELAY_MINUTES = 5;
    /** 单次扫描最大记录数 */
    private static final int BATCH_SIZE = 100;

    /**
     * 退款成功通知补偿（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0/3 * * * ?（每 3 分钟）
     */
    @XxlJob("refundNotifyCompensateJob")
    public void compensateRefundNotify() {
        log.info("[补偿任务] 退款成功通知补偿开始");
        try {
            int compensated = doCompensate();
            String msg = String.format("补偿完成: 重新通知=%d", compensated);
            XxlJobHelper.handleSuccess(msg);
        } catch (Exception e) {
            log.error("[补偿任务] 退款成功通知补偿异常", e);
            XxlJobHelper.handleFail("补偿异常: " + e.getMessage());
        }
    }

    private int doCompensate() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(COMPENSATE_DELAY_MINUTES);

        // 查询退款成功且超过补偿窗口的记录
        List<RefundRecord> refunds = paymentJdbcTemplate.query(
                "SELECT order_id, refund_no FROM t_refund " +
                        "WHERE status = 1 AND deleted = 0 AND success_at < ? " +
                        "ORDER BY success_at ASC LIMIT ?",
                (rs, rowNum) -> new RefundRecord(
                        rs.getLong("order_id"),
                        rs.getString("refund_no")
                ),
                threshold, BATCH_SIZE
        );

        if (refunds.isEmpty()) {
            log.debug("[补偿任务] 无需补偿的退款记录");
            return 0;
        }

        log.info("[补偿任务] 扫描到 {} 条退款成功记录，开始补偿", refunds.size());
        int compensated = 0;

        for (RefundRecord refund : refunds) {
            try {
                String countKey = NOTIFY_COUNT_PREFIX + refund.orderId;
                String countStr = redisTemplate.opsForValue().get(countKey);
                int retryCount = countStr != null ? Integer.parseInt(countStr) : 0;

                if (retryCount >= MAX_RETRY_COUNT) {
                    log.warn("[补偿任务] 退款订单 {} 已达最大重试次数 {}，需人工处理", refund.orderId, MAX_RETRY_COUNT);
                    continue;
                }

                // 通过 Feign 查询订单支付金额，间接判断订单是否仍为"已支付"状态：
                // - 返回成功且 data 非空：订单仍为待支付（只有待支付订单才返回支付金额）
                //   但对于退款场景，订单应该是"已支付"状态而非"待支付"
                //   如果返回了金额，说明数据不一致——支付记录显示已支付触发退款，但订单仍是待支付
                //   这种情况下记录告警，但仍尝试通知退款成功（订单服务内部用乐观锁保证幂等）
                // - 返回成功但 data 为空/null：订单已不是待支付 → 已支付/已退款/已取消
                //   对于退款场景，这才是正常路径（订单已支付，可以退款）
                // - 返回失败或不可达：订单服务不可用 → 稍后重试
                R<BigDecimal> payAmountResult = orderFeignClient.getOrderPayAmount(refund.orderId);

                if (payAmountResult == null || !payAmountResult.isSuccess()) {
                    // 订单服务不可达
                    int code = payAmountResult != null ? payAmountResult.getCode() : -1;
                    if (code == 503) {
                        log.warn("[补偿任务] 订单服务不可达，跳过退款订单 {}", refund.orderId);
                    } else {
                        log.error("[补偿任务] 订单服务返回异常: orderId={}, code={}", refund.orderId, code);
                    }
incrementRetryCount(countKey);
                    continue;
                }

                BigDecimal payAmount = payAmountResult.getData();
                if (payAmount != null && payAmount.compareTo(BigDecimal.ZERO) > 0) {
                    // 异常场景：订单仍为待支付状态，但退款记录显示已支付
                    // 数据不一致：可能是支付成功通知丢失导致订单未更新
                    log.error("[补偿任务] 数据不一致：退款记录存在但订单仍为待支付, orderId={}, refundNo={}, payAmount={}",
                            refund.orderId, refund.refundNo, payAmount);
                    // 仍然尝试通知退款成功，订单服务内部乐观锁会判断订单状态
                    // 如果订单不是已支付状态，notifyRefundSuccess 会返回失败
                }

                // 订单服务可达，尝试重新通知退款成功
                // 订单服务内部会用乐观锁保证幂等（WHERE status=1 已支付）
                // 如果订单已不是已支付状态（已退款/已取消），订单服务会返回失败
                R<Void> notifyResult = orderFeignClient.notifyRefundSuccess(refund.orderId, refund.refundNo);

                if (notifyResult != null && notifyResult.isSuccess()) {
                    log.info("[补偿任务] 退款订单 {} 通知成功", refund.orderId);
                    redisTemplate.delete(countKey);
                    compensated++;
                } else {
                    // 通知失败：可能是订单已不是已支付状态，或订单服务暂时不可用
                    log.warn("[补偿任务] 退款订单 {} 通知失败: {}", refund.orderId,
                            notifyResult != null ? notifyResult.getMessage() : "null response");
incrementRetryCount(countKey);
                }
            } catch (Exception e) {
                log.error("[补偿任务] 处理退款订单 {} 补偿异常", refund.orderId, e);
                String countKey = NOTIFY_COUNT_PREFIX + refund.orderId;
                String countStr = redisTemplate.opsForValue().get(countKey);
                int retryCount = countStr != null ? Integer.parseInt(countStr) : 0;
incrementRetryCount(countKey);
            }
        }

        log.info("[补偿任务] 退款成功通知补偿完成: 重新通知 {} 条", compensated);
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
        Boolean setSuccess = redisTemplate.opsForValue()
                .setIfAbsent(countKey, "1", NOTIFY_COUNT_TTL);
        if (Boolean.FALSE.equals(setSuccess)) {
            // Key 已存在，原子递增（INCR 是原子操作，TTL 不受影响）
            redisTemplate.opsForValue().increment(countKey);
        }
        // SETNX 成功：初始值已设为 "1"，且带 TTL，无需额外操作
    }

    /**
     * 退款记录内部 DTO（避免外部依赖）
     */
    private record RefundRecord(Long orderId, String refundNo) {
    }
}
