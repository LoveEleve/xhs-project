package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.inventory.dto.event.InventoryDeductEvent;
import com.myxhs.inventory.mapper.InventoryMapper;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 库存扣减消费者（L2：MQ 异步扣 MySQL）
 * <p>
 * 消费 INVENTORY_TOPIC 消息，将库存变更持久化到 MySQL。
 * 三种操作：
 * - PRE_DEDUCT：预扣减（available_stock → locked_stock）
 * - CONFIRM：确认扣减（locked_stock 减少）
 * - RELEASE：释放库存（locked_stock → available_stock）
 * </p>
 * <p>
 * 幂等保证（双重）：
 * 1. 消费者层面：MessageIdempotentHelper（msgId, 24h）快速去重，避免重复消费
 * 2. MySQL 使用乐观锁（WHERE locked_stock >= quantity / available_stock >= quantity）
 * 3. 最坏情况：重复扣减导致 MySQL 库存偏低，L3 对账修复会以 Redis 为准修正
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "INVENTORY_TOPIC",
        consumerGroup = "inventory-deduct-consumer-group",
        selectorExpression = "*",
        maxReconsumeTimes = 5
)
public class InventoryDeductConsumer implements RocketMQListener<MessageExt> {

    private final InventoryMapper inventoryMapper;
    private final ObjectMapper objectMapper;
    private final MessageIdempotentHelper idempotentHelper;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String BIZ_TYPE = "inventory:deduct";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时
    private static final String EVENT_VERSION_PREFIX = "inventory:event:version:";

    /**
     * 原子版本检查+更新 Lua 脚本（GET+比较+SET 单原子操作，消除并发双过窗口）
     * 脏数据（非数字）视为无版本，直接覆盖
     */
    private final org.springframework.data.redis.core.script.DefaultRedisScript<Long> versionCheckScript
            = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1]) " +
            "local currentNum = tonumber(current) " +
            "local newVersion = tonumber(ARGV[1]) " +
            "if currentNum and currentNum >= newVersion then return 0 end " +
            "redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
            "return 1",
            Long.class);

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            // 统一幂等检查
            if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msg.getMsgId(), IDEMPOTENT_TTL_SECONDS)) {
                return;
            }

            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            InventoryDeductEvent event = objectMapper.readValue(body, InventoryDeductEvent.class);

            log.info("[库存L2] 收到消息: action={}, orderId={}, skuId={}, qty={}",
                    event.getAction(), event.getOrderId(), event.getSkuId(), event.getQuantity());

            switch (event.getAction()) {
                case "PRE_DEDUCT" -> handlePreDeduct(event);
                case "CONFIRM" -> handleConfirm(event);
                case "RELEASE" -> handleRelease(event);
                default -> log.warn("[库存L2] 未知操作类型: {}", event.getAction());
            }
        } catch (Exception e) {
            log.error("[库存L2] 消费失败: msgId={}", msg.getMsgId(), e);
            // 失败时移除幂等标记，允许 MQ 重投时重新处理（否则重投被 isFirstProcess=false 跳过，消息静默吞没）
            idempotentHelper.removeMark(BIZ_TYPE, msg.getMsgId());
            throw new RuntimeException("库存消费失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * 预扣减：available_stock 减少，locked_stock 增加
     * <p>
     * 使用乐观锁 WHERE available_stock >= quantity 防止扣成负数。
     * 【修复M7】乐观锁失败后退避重试最多 3 次，避免等 24h 对账才修复。
     * 重试失败后仍不抛异常，L3 对账兜底。
     * </p>
     */
    private void handlePreDeduct(InventoryDeductEvent event) {
        // PRE_DEDUCT 也需要版本防护：若 CONFIRM/RELEASE 已先处理（版本更高），
        // 说明本事件是乱序迟到的旧事件，跳过避免幻影 locked_stock（对账以 Redis 为准修复）
        if (!checkEventVersion(event)) return;
        int maxRetry = 3;
        for (int i = 0; i < maxRetry; i++) {
            int affected = inventoryMapper.deductStock(event.getSkuId(), event.getQuantity());
            if (affected > 0) {
                log.info("[库存L2] 预扣减MySQL成功: skuId={}, qty={}, attempt={}",
                        event.getSkuId(), event.getQuantity(), i + 1);
                return;
            }
            // 乐观锁竞争失败，退避后重试
            if (i < maxRetry - 1) {
                try {
                    Thread.sleep(50L * (i + 1)); // 50ms, 100ms 退避
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.info("[库存L2] 预扣减MySQL重试: skuId={}, qty={}, attempt={}",
                        event.getSkuId(), event.getQuantity(), i + 2);
            }
        }
        // 重试耗尽仍失败，等 L3 对账修复
        log.warn("[库存L2] 预扣减MySQL失败({}次重试后仍库存不足): skuId={}, qty={}（等待L3对账修复）",
                maxRetry, event.getSkuId(), event.getQuantity());
    }

    /**
     * 确认扣减：locked_stock 减少（库存正式扣除）
     * <p>时间戳防护：仅当 eventTime > 已处理版本时执行，防止 MQ 乱序</p>
     */
    private void handleConfirm(InventoryDeductEvent event) {
        if (!checkEventVersion(event)) return;
        int affected = inventoryMapper.confirmDeduct(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 确认扣减MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
        } else {
            log.warn("[库存L2] 确认扣减MySQL失败: skuId={}, qty={}（等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
        }
    }

    /**
     * 释放库存：locked_stock 回退到 available_stock
     * <p>时间戳防护：仅当 eventTime > 已处理版本时执行，防止 MQ 乱序</p>
     */
    private void handleRelease(InventoryDeductEvent event) {
        if (!checkEventVersion(event)) return;
        int affected = inventoryMapper.releaseStock(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 释放库存MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
        } else {
            log.warn("[库存L2] 释放库存MySQL失败: skuId={}, qty={}（等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
        }
    }

    /**
     * 时间戳版本检查（MQ 乱序防护）
     * <p>
     * 版本 key 按 orderId+skuId 统一（不按 action 分割）：
     * 防止跨 action 乱序——CONFIRM/RELEASE 先于 PRE_DEDUCT 到达时，
     * 晚到事件先记录版本，早到的 PRE_DEDUCT 被识别为旧事件跳过，由对账以 Redis 为准修复，
     * 避免乱序执行产生幻影 locked_stock。
     * </p>
     */
    private boolean checkEventVersion(InventoryDeductEvent event) {
        if (event.getEventTime() == null) return true; // 无版本号不拦截
        String versionKey = EVENT_VERSION_PREFIX + event.getOrderId() + ":" + event.getSkuId();
        Long passed = stringRedisTemplate.execute(versionCheckScript,
                java.util.List.of(versionKey),
                String.valueOf(event.getEventTime()),
                String.valueOf(IDEMPOTENT_TTL_SECONDS));
        if (passed == null || passed == 0) {
            log.debug("[库存L2] 跳过旧版本事件: orderId={}, skuId={}, action={}, eventTime={}",
                    event.getOrderId(), event.getSkuId(), event.getAction(), event.getEventTime());
            return false;
        }
        return true;
    }
}
