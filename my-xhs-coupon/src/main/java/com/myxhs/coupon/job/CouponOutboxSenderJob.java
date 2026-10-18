package com.myxhs.coupon.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.coupon.mapper.CouponOutboxMapper;
import com.myxhs.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Coupon Outbox 兜底发送任务
 * <p>
 * 扫描 t_coupon_outbox 中未发送的事件，重新投递到 MQ。
 * C-007 Design B（至少一次）下的职责：syncSend 异常/非 SEND_OK/未及标记的事件一律保留在表内，
 * 由本任务补发至成功（失败指数退避重试，记录不丢弃）——保证"名额锁定即成功"后券必达。
 * 【C-007 修正】旧注释"解决 syncSend 超时回滚导致的双花"已随 Design B 失效（超时不再回滚）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponOutboxSenderJob {

    /** RV32：每小时清理 7 天前已发送的 Outbox 记录，控制表增长（失败记录保留待人工） */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000L, initialDelay = 300_000L)
    public void cleanupSent() {
        try {
            int deleted = outboxMapper.deleteSentBefore(java.time.LocalDateTime.now().minusDays(7));
            if (deleted > 0) {
                log.info("[优惠券Outbox] 清理已发送记录 {} 条", deleted);
            }
        } catch (Exception e) {
            log.warn("[优惠券Outbox] 清理失败: {}", e.getMessage());
        }
    }

    private final CouponOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;

    private static final String LOCK_KEY = "myxhs:lock:job:coupon:outbox";
    private static final String COUPON_CLAIM_TOPIC = "COUPON_CLAIM_TOPIC";
    private static final int BATCH_SIZE = 200;

    @Scheduled(fixedRate = 5000)
    public void sendOutboxEvents() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 4, TimeUnit.SECONDS);
            if (!acquired) return;

            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(3);
            List<Map<String, Object>> events = outboxMapper.selectPendingOutbox(cutoff, BATCH_SIZE);
            for (Map<String, Object> row : events) {
                Long id = ((Number) row.get("id")).longValue();
                Long userId = ((Number) row.get("user_id")).longValue();
                Long templateId = ((Number) row.get("template_id")).longValue();
                String claimNo = (String) row.get("claim_no");

                try {
                    CouponService.CouponClaimEvent event = new CouponService.CouponClaimEvent(
                            userId, templateId, claimNo);
                    String payload = objectMapper.writeValueAsString(event);
                    SendResult result = rocketMQTemplate.syncSend(COUPON_CLAIM_TOPIC,
                            MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                            3000);
                    if (result.getSendStatus() == SendStatus.SEND_OK) {
                        outboxMapper.markOutboxSent(claimNo);
                        log.info("[CouponOutbox] 补发成功: userId={}, templateId={}, claimNo={}",
                                userId, templateId, claimNo);
                    } else {
                        log.warn("[CouponOutbox] 补发SendResult非SEND_OK: userId={}, templateId={}, claimNo={}, status={}",
                                userId, templateId, claimNo, result.getSendStatus());
                        scheduleRetry(id, row);
                    }
                } catch (Exception e) {
                    log.warn("[CouponOutbox] 补发失败: userId={}, templateId={}, claimNo={}",
                            userId, templateId, claimNo, e);
                    scheduleRetry(id, row);
                }
            }
            // （2026-09-23 删除）退券 Redis 补偿重放段：生产者零调用的旧语义残留，见 CouponService 注释
        } catch (Exception e) {
            log.error("[CouponOutbox] 扫描异常", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 补发失败：指数退避（30s→480s 封顶）+ ±20% 抖动，写回 next_retry_time（P2/2026-09-27）。
     * 连续失败 >10 次打 WARN（长期失败可见）；记录不丢弃——券事件必须最终送达。
     */
    private void scheduleRetry(Long id, Map<String, Object> row) {
        try {
            int attempt = ((Number) row.getOrDefault("retry_count", 0)).intValue() + 1;
            long delaySeconds = com.myxhs.common.mq.RetryBackoffUtils.exponentialSeconds(attempt, 30, 480, 0.2);
            outboxMapper.markOutboxRetry(id, java.time.LocalDateTime.now().plusSeconds(delaySeconds));
            if (attempt > 10) {
                log.warn("[CouponOutbox] 连续补发失败 {} 次: id={}, nextRetry={}s", attempt, id, delaySeconds);
            }
        } catch (Exception e) {
            log.warn("[CouponOutbox] 写回重试时间失败(下轮仍会重试): id={}, error={}", id, e.getMessage());
        }
    }

}
