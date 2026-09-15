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
 * 解决 syncSend 超时回滚导致的"券已入账但 Redis 已退回"双花问题。
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
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    private final CouponService couponService;

    private static final String LOCK_KEY = "myxhs:lock:job:coupon:outbox";
    private static final String COUPON_CLAIM_TOPIC = "COUPON_CLAIM_TOPIC";
    private static final String RETURN_REPAIR_FALLBACK_KEY = "myxhs:coupon:return:repair:pending";
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
                    }
                } catch (Exception e) {
                    log.warn("[CouponOutbox] 补发失败: userId={}, templateId={}, claimNo={}",
                            userId, templateId, claimNo, e);
                }
            }
            replayReturnRepairFallback();
        } catch (Exception e) {
            log.error("[CouponOutbox] 扫描异常", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void replayReturnRepairFallback() {
        try {
            java.util.Set<String> pending = stringRedisTemplate.opsForSet().members(RETURN_REPAIR_FALLBACK_KEY);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            for (String member : pending) {
                String[] parts = member.split(":", 2);
                if (parts.length != 2) {
                    stringRedisTemplate.opsForSet().remove(RETURN_REPAIR_FALLBACK_KEY, member);
                    continue;
                }
                try {
                    Long templateId = Long.valueOf(parts[0]);
                    Long userId = Long.valueOf(parts[1]);
                    couponService.repairReturnCouponRedis(userId, templateId);
                    stringRedisTemplate.opsForSet().remove(RETURN_REPAIR_FALLBACK_KEY, member);
                    log.info("[CouponOutbox] 退券Redis兜底重放成功: {}", member);
                } catch (Exception e) {
                    log.warn("[CouponOutbox] 退券Redis兜底重放失败: {}", member, e);
                }
            }
        } catch (Exception e) {
            log.error("[CouponOutbox] 退券Redis兜底扫描异常", e);
        }
    }
}
