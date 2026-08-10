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
 * 解决 syncSend 超时回滚导致的"券已入账但库存退回"双花问题：
 * Outbox 在 Redis 预扣成功后立即写入（与业务操作同步），
 * MQ 发送由此任务异步执行，失败自动重试。
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

    private static final String LOCK_KEY = "lock:job:inventory:outbox";
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
                        inventoryMapper.markOutboxSent(orderId, skuId);
                        log.debug("[Outbox] 补发成功: orderId={}, skuId={}, action={}", orderId, skuId, action);
                    } else {
                        log.warn("[Outbox] 补发状态异常(不标记, 下轮重试): orderId={}, skuId={}, action={}, status={}",
                                orderId, skuId, action, sendResult.getSendStatus());
                    }
                } catch (Exception e) {
                    log.warn("[Outbox] 补发失败: orderId={}, skuId={}, action={}", orderId, skuId, action, e);
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
}
