package com.myxhs.inventory.job;

import com.myxhs.inventory.dto.request.ConfirmDeductRequest;
import com.myxhs.inventory.dto.request.StockItem;
import com.myxhs.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 预扣超时回退任务（C-008：per-sku 索引扫描 + per-(sku,order) 释放/补确认）
 * <p>
 * 每 1 分钟扫描 Redis 中 per-sku 预扣索引（inventory:{skuId}:prededuct:index），
 * 对已到期的记录执行库存回退（ZSet score=到期时间戳ms，物理 TTL=到期+缓冲）。
 * </p>
 * <p>
 * 分布式安全保证：
 * 1. Redisson 分布式锁保证多实例部署时只有一个实例执行
 * 2. release.lua 脚本原子回退保证与用户主动释放不会双重回退
 * 3. Redis 回退成功后，RELEASE 事件同步发 MQ 并保留 Outbox，避免 MySQL locked_stock 永久掉队
 * 4. 【04B 收尾】释放前检查"确认标记"（inventory:confirmed:{orderId}）：
 *    已付款订单的迟到预扣记录改判"补确认"而非释放——防已付款订单库存被放回（超卖方向）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreDeductTimeoutJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> releaseScript;
    private final RedissonClient redissonClient;
    private final org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.myxhs.inventory.mapper.InventoryMapper inventoryMapper;
    private final com.myxhs.inventory.service.InventoryService inventoryService;

    /** per-sku 索引扫描模式（C-008：全局索引 → per-sku 索引；键格式必须与 InventoryService 一致：tag-first） */
    private static final String INDEX_SCAN_PATTERN = "inventory:*:prededuct:index";
    private static final String STOCK_KEY_TPL = "inventory:{%d}:stock";
    private static final String PREDEDUCT_KEY_TPL = "inventory:{%d}:prededuct:%d";
    private static final String INDEX_KEY_TPL = "inventory:{%d}:prededuct:index";

    private static String stockKey(Long skuId) { return String.format(STOCK_KEY_TPL, skuId); }
    private static String predeductKey(Long skuId, Long orderId) { return String.format(PREDEDUCT_KEY_TPL, skuId, orderId); }
    private static String indexKey(Long skuId) { return String.format(INDEX_KEY_TPL, skuId); }

    private static final String LOCK_KEY = "myxhs:lock:job:inventory:prededuct-timeout"; // C-008 F10：前缀统一

    /**
     * 每 1 分钟扫描过期预扣记录，自动回退库存
     * <p>
     * 分布式锁保证多实例只有一个执行。tryLock(0, ...) 表示不等待——
     * 如果另一个实例正在执行，当前实例直接跳过本轮。
     * leaseTime=40s，小于调度间隔 1 分钟，保证下一轮不会被锁阻塞。
     * </p>
     */
    @Scheduled(fixedRate = 60_000) // 每 1 分钟（测试环境快速验证）
    public void releaseExpiredPreDeductions() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 40, TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("[预扣超时] 其他实例正在执行，跳过本轮");
                return;
            }

            doReleaseExpiredPreDeductions();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[预扣超时] 获取锁被中断");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 实际的扫描和回退逻辑
     * <p>
     * C-008：SCAN per-sku 索引键（键数 ≈ 在途 SKU 数，有界），对每个索引键
     * ZRANGEBYSCORE 取真正到期的 (sku, orderId) 对，逐对回退/补确认。
     * </p>
     */
    private void doReleaseExpiredPreDeductions() {
        log.debug("[预扣超时] 开始扫描...");
        int releaseCount = 0;

        try {
            // 只回退"已到期"的预扣记录：物理 TTL 已加 10 分钟缓冲，到期后回退不再依赖"抢在 TTL 前"
            long cutoffMs = System.currentTimeMillis();

            java.util.List<String> indexKeys = new java.util.ArrayList<>();
            try (var cursor = stringRedisTemplate.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(INDEX_SCAN_PATTERN).count(500).build())) {
                cursor.forEachRemaining(indexKeys::add);
            }

            for (String indexKeyStr : indexKeys) {
                Long skuId = parseSkuIdFromIndexKey(indexKeyStr);
                if (skuId == null) {
                    continue;
                }
                Set<String> expiringOrderIds = stringRedisTemplate.opsForZSet()
                        .rangeByScore(indexKeyStr, 0, cutoffMs);
                if (expiringOrderIds == null || expiringOrderIds.isEmpty()) {
                    continue;
                }

                for (String orderIdStr : expiringOrderIds) {
                    Long orderId;
                    try {
                        orderId = Long.parseLong(orderIdStr);
                    } catch (NumberFormatException e) {
                        log.warn("[预扣超时] 索引 member 非法(跳过并清理): index={}, member={}", indexKeyStr, orderIdStr);
                        stringRedisTemplate.opsForZSet().remove(indexKeyStr, orderIdStr);
                        continue;
                    }
                    // 04B 收尾：已"确认"过的订单（支付确认早于预扣落）→ 迟到记录是孤儿，补确认而非释放
                    if (settleAsConfirmedIfMarked(skuId, orderId)) {
                        continue;
                    }
                    int released = releasePreDeduct(skuId, orderId);
                    if (released == 0) {
                        // 预扣记录已不存在（惰性过期/已释放/已确认），清理索引中的陈旧 member
                        stringRedisTemplate.opsForZSet().remove(indexKeyStr, orderIdStr);
                    }
                    releaseCount += released;
                }
            }

            if (releaseCount > 0) {
                log.info("[预扣超时] 扫描完成: 回退{}条预扣记录", releaseCount);
            }
        } catch (Exception e) {
            log.error("[预扣超时] 扫描异常", e);
        }
    }

    /** 从索引键 inventory:{skuId}:prededuct:index 解析 skuId */
    private Long parseSkuIdFromIndexKey(String indexKeyStr) {
        try {
            int l = indexKeyStr.indexOf('{');
            int r = indexKeyStr.indexOf('}');
            if (l < 0 || r < 0) {
                return null;
            }
            return Long.valueOf(indexKeyStr.substring(l + 1, r));
        } catch (NumberFormatException e) {
            log.warn("[预扣超时] 索引键格式异常(跳过): {}", indexKeyStr);
            return null;
        }
    }

    /**
     * 04B 收尾：若订单已"确认"过（确认标记存在——支付确认早于预扣落时由 confirmDeduct 写入），
     * 则本记录不是弃单：改判为"补确认"（删记录，库存保持扣减），绝不释放（释放即超卖方向）。
     *
     * @return true = 已按补确认处理（调用方跳过释放）
     */
    private boolean settleAsConfirmedIfMarked(Long skuId, Long orderId) {
        if (!inventoryService.hasConfirmedMarker(orderId)) {
            return false;
        }
        try {
            // C-008：per-(sku,order) 补确认——构造单项 items 请求
            ConfirmDeductRequest request = new ConfirmDeductRequest();
            request.setOrderId(orderId);
            StockItem item = new StockItem();
            item.setSkuId(skuId);
            request.setItems(List.of(item));
            inventoryService.confirmDeduct(request);
            log.warn("[预扣超时] 订单已支付确认(确认早于预扣落), 改判补确认(不释放): orderId={}, skuId={}", orderId, skuId);
        } catch (Exception e) {
            // 补确认失败（如 Redis 抖动）：本轮不释放（释放即超卖方向），下轮重试
            log.error("[预扣超时] 补确认失败(本轮不释放, 下轮重试): orderId={}, skuId={}", orderId, skuId, e);
        }
        return true;
    }

    /**
     * 回退单个 (sku, orderId) 预扣记录（release.lua 原子：回退库存 + 删记录 + 索引移除）
     *
     * @return 回退的数量（0=记录不存在）
     */
    private int releasePreDeduct(Long skuId, Long orderId) {
        // Lua 原子回退（KEYS 同 {skuId} tag）
        Long result = stringRedisTemplate.execute(
                releaseScript,
                List.of(stockKey(skuId), predeductKey(skuId, orderId), indexKey(skuId)),
                String.valueOf(orderId)
        );

        if (result != null && result > 0) {
            log.info("[预扣超时] 回退库存: orderId={}, skuId={}, qty={}", orderId, skuId, result);
            // Redis 回退成功后发 RELEASE MQ 事件，解锁 MySQL locked_stock
            sendReleaseEvent(orderId, skuId, result.intValue());
            return 1;
        }
        return 0;
    }

    /**
     * 发送 RELEASE 事件到 MQ，解锁 MySQL locked_stock（与 order 服务取消订单的释放链路一致）
     */
    private void sendReleaseEvent(Long orderId, Long skuId, int quantity) {
        long eventId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long eventTime = System.currentTimeMillis();
        try {
            inventoryMapper.insertOutboxEvent(eventId, orderId, skuId, quantity, "RELEASE");
            com.myxhs.inventory.dto.event.InventoryDeductEvent event =
                    com.myxhs.inventory.dto.event.InventoryDeductEvent.builder()
                            .outboxId(eventId)
                            .orderId(orderId)
                            .skuId(skuId)
                            .quantity(quantity)
                            .action("RELEASE")
                            .eventTime(eventTime)
                            .build();
            org.apache.rocketmq.client.producer.SendResult sendResult = rocketMQTemplate.syncSend(
                    "INVENTORY_TOPIC:RELEASE",
                    MessageBuilder.withPayload(objectMapper.writeValueAsString(event)).build(),
                    3000);
            if (sendResult.getSendStatus() == org.apache.rocketmq.client.producer.SendStatus.SEND_OK) {
                inventoryMapper.markOutboxSent(orderId, skuId, "RELEASE"); // C-008 F2：按业务键匹配
                log.debug("[预扣超时] RELEASE事件发送成功: orderId={}, skuId={}", orderId, skuId);
            } else {
                log.error("[预扣超时] RELEASE事件发送状态异常(等待Outbox补发): orderId={}, skuId={}, status={}",
                        orderId, skuId, sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.error("[预扣超时] RELEASE事件发送失败(等待Outbox补发): orderId={}, skuId={}", orderId, skuId, e);
        }
    }
}
