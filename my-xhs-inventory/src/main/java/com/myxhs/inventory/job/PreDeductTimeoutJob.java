package com.myxhs.inventory.job;

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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 预扣超时回退任务
 * <p>
 * 每 5 分钟扫描 Redis 中的预扣记录（inventory:prededuct:*），
 * 对 TTL 已过期或即将过期的记录执行库存回退。
 * </p>
 * <p>
 * 分布式安全保证：
 * 1. Redisson 分布式锁保证多实例部署时只有一个实例执行（防止重复扫描浪费资源）
     * 2. release.lua 脚本原子回退保证与用户主动释放不会双重回退
     * 3. Redis 回退成功后，RELEASE 事件同步发 MQ 并保留 Outbox，避免 MySQL locked_stock 永久掉队
     * </p>

 * <p>
 * 为什么需要主动扫描？
 * Redis Key 过期是惰性删除 + 定期删除，不保证精确过期。
 * 如果 Key 过期后没有被访问，可能长时间不被删除，导致库存被"幽灵锁定"。
 * 主动扫描 + 原子回退保证库存及时释放。
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

    private static final String PREDEDUCT_KEY_PREFIX = "inventory:prededuct:";
    private static final String PREDEDUCT_INDEX_KEY = "inventory:prededuct:index";
    private static final String TOTAL_KEY_TPL = "inventory:{%d}:total";
    private static final String BUCKET_KEY_TPL = "inventory:{%d}:bucket:";

    private static String totalKey(Long skuId) { return String.format(TOTAL_KEY_TPL, skuId); }
    private static String bucketKey(Long skuId, int bucketNo) { return String.format(BUCKET_KEY_TPL, skuId) + bucketNo; }
    private static final String LOCK_KEY = "lock:job:inventory:prededuct-timeout";

    /**
     * 每 5 分钟扫描过期预扣记录，自动回退库存
     * <p>
     * 分布式锁保证多实例只有一个执行。tryLock(0, ...) 表示不等待——
     * 如果另一个实例正在执行，当前实例直接跳过本轮。
     * leaseTime=240s（4分钟），小于调度间隔 5 分钟，保证下一轮不会被锁阻塞。
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
     * 使用 ZSet 二级索引（inventory:prededuct:index, score=过期时间戳ms）
     * ZRANGEBYSCORE 直接查询即将过期的预扣记录，替代全库 SCAN（O(N) → O(logN+M)）。
     * </p>
     */
    private void doReleaseExpiredPreDeductions() {
        log.debug("[预扣超时] 开始扫描...");
        int releaseCount = 0;

        try {
            // 查询 60 秒内即将过期的预扣记录（主动提前回退）
            long cutoffMs = System.currentTimeMillis() + 60000;
            java.util.Set<String> expiringOrderIds = stringRedisTemplate.opsForZSet()
                    .rangeByScore(PREDEDUCT_INDEX_KEY, 0, cutoffMs);

            if (expiringOrderIds == null || expiringOrderIds.isEmpty()) {
                return;
            }

            for (String orderIdStr : expiringOrderIds) {
                String key = PREDEDUCT_KEY_PREFIX + orderIdStr;
                int released = releasePreDeduct(key);
                if (released == 0) {
                    // 预扣记录已不存在（惰性过期/已释放/已确认），清理索引中的陈旧 member
                    stringRedisTemplate.opsForZSet().remove(PREDEDUCT_INDEX_KEY, orderIdStr);
                }
                releaseCount += released;
            }

            if (releaseCount > 0) {
                log.info("[预扣超时] 扫描完成: 回退{}条预扣记录", releaseCount);
            }
        } catch (Exception e) {
            log.error("[预扣超时] 扫描异常", e);
        }
    }

    /**
     * 回退单个预扣记录（使用 Lua 脚本保证原子性）
     * <p>
     * 为什么必须用 Lua 脚本而不是分步操作？
     * 如果用分步操作（先 HGETALL → 逐个 INCRBY → DEL），在 HGETALL 和 DEL 之间，
     * 用户可能主动调用 releaseStock 也在释放同一个 orderId 的库存，
     * 导致双重回退——库存凭空增加。
     * Lua 脚本的 HGET + HDEL 是原子的，保证只有一方能成功回退。
     * </p>
     *
     * @param predeductKey inventory:prededuct:{orderId}
     * @return 回退的 SKU 数量
     */
    private int releasePreDeduct(String predeductKey) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(predeductKey);
        if (entries.isEmpty()) {
            return 0;
        }

        // 从 key 提取 orderId（inventory:prededuct:{orderId}），用于 MQ RELEASE 事件
        Long orderId;
        try {
            orderId = Long.parseLong(predeductKey.substring(predeductKey.lastIndexOf(':') + 1));
        } catch (NumberFormatException e) {
            log.warn("[预扣超时] key 格式异常无法提取 orderId, 跳过: {}", predeductKey);
            return 0;
        }

        int count = 0;

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String fieldName = entry.getKey().toString();
            // 过滤 :bucket 辅助字段（只处理 skuId 字段）
            if (fieldName.contains(":bucket")) {
                continue;
            }

            Long skuId;
            int quantity;
            try {
                skuId = Long.parseLong(fieldName);
                quantity = Integer.parseInt(entry.getValue().toString());
            } catch (NumberFormatException e) {
                log.warn("[预扣超时] 跳过脏数据(skuId/数量非数字): key={}, field={}, value={}",
                        predeductKey, fieldName, entry.getValue());
                continue;
            }

            // 获取来源桶号（release.lua 需要桶 Key 作为 KEYS[3]）
            Object bucketNoObj = stringRedisTemplate.opsForHash().get(predeductKey, fieldName + ":bucket");
            int bucketNo = 0;
            if (bucketNoObj != null) {
                try {
                    bucketNo = Integer.parseInt(bucketNoObj.toString());
                } catch (NumberFormatException e) {
                    log.warn("[预扣超时] 桶号脏数据按0处理: key={}, field={}", predeductKey, fieldName);
                }
            }

            String totalKeyStr = totalKey(skuId);
            String bucketKeyStr = bucketKey(skuId, bucketNo);

            // 使用 release.lua 原子回退（传入 totalKey + predeductKey + bucketKey + indexKey）
            Long result = stringRedisTemplate.execute(
                    releaseScript,
                    List.of(totalKeyStr, predeductKey, bucketKeyStr, PREDEDUCT_INDEX_KEY),
                    fieldName, String.valueOf(orderId)
            );

            if (result != null && result > 0) {
                log.info("[预扣超时] 回退库存: predeductKey={}, skuId={}, qty={}, bucket={}",
                        predeductKey, skuId, result, bucketNo);
                count++;
                // Redis 回退成功后发 RELEASE MQ 事件，解锁 MySQL locked_stock
                sendReleaseEvent(orderId, skuId, quantity);
            }
        }

        return count;
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
                inventoryMapper.markOutboxSent(eventId);
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
