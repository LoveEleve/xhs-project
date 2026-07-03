package com.myxhs.inventory.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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

    private static final String PREDEDUCT_KEY_PATTERN = "inventory:prededuct:*";
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
    @Scheduled(fixedRate = 300000)
    public void releaseExpiredPreDeductions() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 240, TimeUnit.SECONDS);
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
     */
    private void doReleaseExpiredPreDeductions() {
        log.debug("[预扣超时] 开始扫描...");
        int releaseCount = 0;

        try {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(PREDEDUCT_KEY_PATTERN)
                    .count(100)
                    .build();

            try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);

                    // TTL <= 0 表示已过期（但还未被 Redis 惰性删除）或没有设置过期时间
                    // TTL = -2 表示 Key 不存在（已被删除）
                    // TTL = -1 表示没有过期时间（异常情况）
                    if (ttl != null && ttl <= 0) {
                        releaseCount += releasePreDeduct(key);
                    }
                }
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

        int count = 0;

        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String fieldName = entry.getKey().toString();
            // 过滤 :bucket 辅助字段（只处理 skuId 字段）
            if (fieldName.contains(":bucket")) {
                continue;
            }

            Long skuId = Long.parseLong(fieldName);

            // 获取来源桶号（release.lua 需要桶 Key 作为 KEYS[3]）
            Object bucketNoObj = stringRedisTemplate.opsForHash().get(predeductKey, fieldName + ":bucket");
            int bucketNo = bucketNoObj != null ? Integer.parseInt(bucketNoObj.toString()) : 0;

            String totalKeyStr = totalKey(skuId);
            String bucketKeyStr = bucketKey(skuId, bucketNo);

            // 使用 release.lua 原子回退（传入 totalKey + predeductKey + bucketKey）
            Long result = stringRedisTemplate.execute(
                    releaseScript,
                    List.of(totalKeyStr, predeductKey, bucketKeyStr),
                    fieldName
            );

            if (result != null && result > 0) {
                log.info("[预扣超时] 回退库存: predeductKey={}, skuId={}, qty={}, bucket={}",
                        predeductKey, skuId, result, bucketNo);
                count++;
            }
        }

        return count;
    }
}
