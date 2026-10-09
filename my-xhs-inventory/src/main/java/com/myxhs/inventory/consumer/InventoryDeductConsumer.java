package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.metrics.BusinessMetrics;
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
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
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
    private final RocketMQTemplate rocketMQTemplate;
    private final BusinessMetrics businessMetrics;

    private static final String BIZ_TYPE = "inventory:deduct";
    private static final long IDEMPOTENT_TTL_SECONDS = 86400; // 24 小时
    private static final String EVENT_VERSION_PREFIX = "inventory:event:version:";

    /** 库存失败事件 Topic（订单侧收口：未支付自动关单 / 已支付人工介入） */
    public static final String INVENTORY_FAILED_TOPIC = "INVENTORY_FAILED_TOPIC";

    /**
     * 原子版本检查+更新 Lua 脚本（GET+比较+SET 单原子操作，消除并发双过窗口）
     * 脏数据（非数字）视为无版本，直接覆盖
     */
    /**
     * 预留版本（原子 check-and-set）：返回 -1=旧事件（调用方跳过）；>=0=成功预留并返回"预留前的版本"（0 表示无）
     */
    private final org.springframework.data.redis.core.script.DefaultRedisScript<Long> versionReserveScript
            = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1]) " +
            "local currentNum = tonumber(current) or 0 " +
            "local newVersion = tonumber(ARGV[1]) " +
            "if currentNum >= newVersion then return -1 end " +
            "redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]) " +
            "return currentNum", Long.class);

    /**
     * 条件回滚版本：仅当当前值仍是本次预留值时才恢复为预留前的值（并发期间已被更新则不动）
     */
    private final org.springframework.data.redis.core.script.DefaultRedisScript<Long> versionRestoreScript
            = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local current = tonumber(redis.call('get', KEYS[1]) or '0') " +
            "local mine = tonumber(ARGV[1]) " +
            "if current ~= mine then return 0 end " +
            "local prev = tonumber(ARGV[2]) " +
            "if prev <= 0 then redis.call('del', KEYS[1]) else redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3]) end " +
            "return 1", Long.class);

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
                case "REFUND_RESTORE" -> handleRefundRestore(event);
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
        withEventVersion(event, () -> doHandlePreDeduct(event));
    }

    private void doHandlePreDeduct(InventoryDeductEvent event) {
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
                    Thread.sleep(com.myxhs.common.mq.RetryBackoffUtils.jitter(50L * (i + 1), 0.2)); // 50/100ms +抖动
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                log.info("[库存L2] 预扣减MySQL重试: skuId={}, qty={}, attempt={}",
                        event.getSkuId(), event.getQuantity(), i + 2);
            }
        }
        // 重试耗尽仍失败：发失败事件给订单侧收口（未支付关单/已支付人工），L3 对账继续兜底
        log.warn("[库存L2] 预扣减MySQL失败({}次重试后仍库存不足): skuId={}, qty={}（已发失败事件，等待L3对账修复）",
                maxRetry, event.getSkuId(), event.getQuantity());
        businessMetrics.recordInventoryFailure("PREDUCT_FAILED");
        publishFailureEvent(event, "PREDUCT_FAILED");
    }

    /**
     * 确认扣减：locked_stock 减少（库存正式扣除）
     * <p>时间戳防护：仅当 eventTime > 已处理版本时执行，防止 MQ 乱序</p>
     */
    private void handleConfirm(InventoryDeductEvent event) {
        withEventVersion(event, () -> {
            int affected = inventoryMapper.confirmDeduct(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 确认扣减MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
        } else {
            log.warn("[库存L2] 确认扣减MySQL失败: skuId={}, qty={}（已发失败事件，等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
            businessMetrics.recordInventoryFailure("CONFIRM_FAILED");
            publishFailureEvent(event, "CONFIRM_FAILED");
            }
        });
    }

    /**
     * 释放库存：locked_stock 回退到 available_stock
     * <p>时间戳防护：仅当 eventTime > 已处理版本时执行，防止 MQ 乱序</p>
     */
    private void handleRelease(InventoryDeductEvent event) {
        withEventVersion(event, () -> {
            int affected = inventoryMapper.releaseStock(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 释放库存MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
        } else {
            log.warn("[库存L2] 释放库存MySQL失败: skuId={}, qty={}（等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
            }
        });
    }

    /** T-071：退款回补（MySQL available_stock +qty——独立语义，非 locked→available） */
    private void handleRefundRestore(InventoryDeductEvent event) {
        withEventVersion(event, () -> {
            int affected = inventoryMapper.refundRestoreStock(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 退款回补MySQL成功: skuId={}, qty={}", event.getSkuId(), event.getQuantity());
        } else {
            log.warn("[库存L2] 退款回补MySQL失败: skuId={}, qty={}（等待L3对账修复）",
                    event.getSkuId(), event.getQuantity());
            }
        });
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
    /**
     * 预留事件版本（原子 check-and-set）
     * <p>
     * 语义：同 (orderId, skuId) 只允许版本严格递增的事件执行；本方法先原子占位再执行 DB。
     * 与"只读比较 + 成功后推进"相比保留了**并发顺序保证**（否则新旧事件可并发穿过检查）；
     * 与"检查即推进"相比，DB 异常时会由 {@link #restoreEventVersion} 回滚预留，
     * 修复"异常重投被当成旧事件永久跳过、locked_stock 虚高"的问题。
     * </p>
     *
     * @return -1=旧事件（跳过）；>=0=预留成功（返回值=预留前版本，用于失败回滚）
     */
    private long reserveEventVersion(InventoryDeductEvent event) {
        if (event.getEventTime() == null) return 0L; // 无版本号不拦截
        String versionKey = EVENT_VERSION_PREFIX + event.getOrderId() + ":" + event.getSkuId();
        Long prev = stringRedisTemplate.execute(versionReserveScript,
                java.util.List.of(versionKey),
                String.valueOf(event.getEventTime()),
                String.valueOf(IDEMPOTENT_TTL_SECONDS));
        if (prev == null || prev < 0) {
            log.debug("[库存L2] 跳过旧版本事件: orderId={}, skuId={}, action={}, eventTime={}",
                    event.getOrderId(), event.getSkuId(), event.getAction(), event.getEventTime());
            return -1L;
        }
        return prev;
    }

    /** DB 失败时回滚预留（仅当未被并发更新时），使 MQ 重投可以再次处理同一事件 */
    private void restoreEventVersion(InventoryDeductEvent event, long prev) {
        if (event.getEventTime() == null || prev < 0) return;
        try {
            String versionKey = EVENT_VERSION_PREFIX + event.getOrderId() + ":" + event.getSkuId();
            stringRedisTemplate.execute(versionRestoreScript,
                    java.util.List.of(versionKey),
                    String.valueOf(event.getEventTime()),
                    String.valueOf(prev),
                    String.valueOf(IDEMPOTENT_TTL_SECONDS));
        } catch (Exception e) {
            log.error("[库存L2] 版本回滚失败(可能漏处理, 依赖对账): orderId={}, skuId={}, eventTime={}",
                    event.getOrderId(), event.getSkuId(), event.getEventTime(), e);
        }
    }

    /**
     * 统一的"预留-执行-失败回滚"包装
     */
    private void withEventVersion(InventoryDeductEvent event, Runnable action) {
        long prev = reserveEventVersion(event);
        if (prev < 0) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            restoreEventVersion(event, prev);
            throw e;
        }
    }

    /**
     * 发布"库存失败"事件（订单侧收口：未支付自动关单 / 已支付人工介入）。
     * <p>
     * 尽力而为：发送失败只记日志与指标，不影响主消费（订单侧 30 分钟超时关单 + L3 对账仍在兜底）。
     * 仅对 PRE_DEDUCT/CONFIRM 两类资金相关失败发布；RELEASE/REFUND_RESTORE 失败只影响
     * MySQL 镜像（对账可修），不触发订单侧动作。
     * </p>
     */
    private void publishFailureEvent(InventoryDeductEvent source, String reason) {
        try {
            String payload = objectMapper.writeValueAsString(java.util.Map.of(
                    "orderId", source.getOrderId(),
                    "skuId", source.getSkuId(),
                    "quantity", source.getQuantity(),
                    "action", source.getAction(),
                    "reason", reason,
                    "failedAt", System.currentTimeMillis()));
            org.apache.rocketmq.client.producer.SendResult result = rocketMQTemplate.syncSend(
                    INVENTORY_FAILED_TOPIC,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    3000);
            if (result.getSendStatus() != org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                log.error("[库存L2] 失败事件发送状态异常: orderId={}, reason={}, status={}",
                        source.getOrderId(), reason, result.getSendStatus());
                businessMetrics.recordInventoryFailure(reason + "_PUBLISH_NOT_OK");
            }
        } catch (Exception e) {
            log.error("[库存L2] 失败事件发送异常: orderId={}, reason={}", source.getOrderId(), reason, e);
            businessMetrics.recordInventoryFailure(reason + "_PUBLISH_EXCEPTION");
        }
    }
}
