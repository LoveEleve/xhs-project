package com.myxhs.inventory.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.mapper.InventoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Outbox 兜底发送任务
 * <p>
 * 扫描 t_inventory_outbox 中未发送的事件，重新投递到 MQ。
 * 职责：syncSend 异常/非 SEND_OK/未及标记的事件一律保留在表内，由本任务补发至成功
 * （失败指数退避重试，记录不丢弃）——"Redis 已扣但 MySQL 未落"的最终送达保障。
 * 【C-008 修正】原注释"解决 syncSend 超时回滚导致的'券已入账但库存退回'双花"为复制残留
 * （"券"字）；且消费端已有逻辑事件幂等（L2 inbox），补发重复不双扣。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryOutboxSenderJob {

    private final InventoryMapper inventoryMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final RedissonClient redissonClient;
    private final com.myxhs.common.metrics.BusinessMetrics businessMetrics; // C-008 F9：指标

    private static final String LOCK_KEY = "myxhs:lock:job:inventory:outbox"; // C-008 F10：前缀统一
    private static final String INVENTORY_TOPIC = "INVENTORY_TOPIC";
    private static final int BATCH_SIZE = 200;

    @Scheduled(fixedRate = 5000)
    public void sendOutboxEvents() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 4, TimeUnit.SECONDS);
            if (!acquired) return;

            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(3);
            List<Map<String, Object>> events = inventoryMapper.selectPendingOutbox(cutoff, BATCH_SIZE);
            for (Map<String, Object> row : events) {
                Long eventId = ((Number) row.get("id")).longValue();
                Long orderId = ((Number) row.get("order_id")).longValue();
                Long skuId = ((Number) row.get("sku_id")).longValue();
                String action = (String) row.get("action");
                int quantity = ((Number) row.get("quantity")).intValue();
                // 用 outbox 行创建时间作为 eventTime（保持原始业务时序，供消费者乱序版本检查）
                long eventTime = row.get("created_at") instanceof java.sql.Timestamp
                        ? ((java.sql.Timestamp) row.get("created_at")).getTime()
                        : System.currentTimeMillis();

                // 构造与消费者反序列化格式一致的 InventoryDeductEvent（camelCase），
                // 不能直接序列化 DB 行 Map（snake_case 导致 Consumer 字段全 null → NPE → DLQ）
                com.myxhs.inventory.dto.event.InventoryDeductEvent event =
                        com.myxhs.inventory.dto.event.InventoryDeductEvent.builder()
                                .outboxId(eventId)
                                .orderId(orderId)
                                .skuId(skuId)
                                .quantity(quantity)
                                .action(action)
                                .eventTime(eventTime)
                                .build();

                try {
                    org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                            INVENTORY_TOPIC + ":" + action,
                            MqTraceHelper.wrapWithTraceId(
                                    MessageBuilder.withPayload(objectMapper.writeValueAsString(event)).build()),
                            3000);
                    // 只有真正发送成功才标记，防止发送失败却标记导致事件丢失
                    if (sendResult.getSendStatus() == org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                        inventoryMapper.markOutboxSent(orderId, skuId, action); // C-008 F2：按业务键匹配
                        businessMetrics.recordInventoryAction("outbox_resend_ok"); // C-008 F9
                        log.debug("[Outbox] 补发成功: eventId={}, orderId={}, skuId={}, action={}", eventId, orderId, skuId, action);
                    } else {
                        log.warn("[Outbox] 补发状态异常(延后重试): orderId={}, skuId={}, action={}, status={}",
                                orderId, skuId, action, sendResult.getSendStatus());
                        scheduleRetry(eventId, row);
                    }
                } catch (Exception e) {
                    log.warn("[Outbox] 补发失败(延后重试): orderId={}, skuId={}, action={}", orderId, skuId, action, e);
                    scheduleRetry(eventId, row);
                }
            }
        } catch (Exception e) {
            log.error("[Outbox] 扫描异常", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 补发失败：指数退避（30s→480s 封顶）+ ±20% 抖动，写回 next_retry_time（P2/2026-09-27）。
     * 连续失败 >10 次打 WARN；记录不丢弃——库存事件必须最终送达（对账是另一层兜底）。
     */
    private void scheduleRetry(Long eventId, java.util.Map<String, Object> row) {
        try {
            int attempt = ((Number) row.getOrDefault("retry_count", 0)).intValue() + 1;
            long delaySeconds = com.myxhs.common.mq.RetryBackoffUtils.exponentialSeconds(attempt, 30, 480, 0.2);
            inventoryMapper.markOutboxRetry(eventId, java.time.LocalDateTime.now().plusSeconds(delaySeconds));
            if (attempt > 10) {
                log.warn("[Outbox] 连续补发失败 {} 次: eventId={}, nextRetry={}s", attempt, eventId, delaySeconds);
            }
        } catch (Exception e) {
            log.warn("[Outbox] 写回重试时间失败(下轮仍会重试): eventId={}, error={}", eventId, e.getMessage());
        }
    }
}
