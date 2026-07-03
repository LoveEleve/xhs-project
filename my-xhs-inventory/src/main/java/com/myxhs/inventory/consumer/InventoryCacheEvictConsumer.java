package com.myxhs.inventory.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 库存缓存失效消费者（Canal + Binlog 强一致性方案）
 * <p>
 * 消费 Canal 发送的库存变更消息，删除对应的 Redis 缓存。
 * </p>
 * <p>
 * 为什么库存模块不用延迟双删？
 * 库存是高并发强一致性场景，延迟双删存在 500ms 不一致窗口：
 * 在窗口内并发读可能读到旧值并回填缓存，导致超卖。
 * Canal 方案：监听 MySQL Binlog → 解析变更 → 主动删除 Redis 缓存，
 * 保证数据库变更后缓存一定被清除，不存在不一致窗口。
 * </p>
 * <p>
 * 消息来源：Canal 监听 MySQL my_xhs_inventory.t_inventory 表 → RocketMQ INVENTORY_CACHE_TOPIC
 * </p>
 * <p>
 * Canal 消息格式：
 * {
 *   "database": "my_xhs_inventory",
 *   "table": "t_inventory",
 *   "type": "UPDATE",
 *   "data": [{"id": "1", "sku_id": "100", "available_stock": "95", "locked_stock": "5", ...}],
 *   "old": [{"available_stock": "100"}],
 *   "es": 1715510539000,   // event sequence，严格递增
 *   "ts": 1715510539000    // timestamp
 * }
 * </p>
 * <p>
 * 消费策略：
 * 1. 只处理 t_inventory 表的 UPDATE/INSERT 事件（DELETE 事件删除所有缓存 Key）
 * 2. 从 data 中提取 sku_id，删除该 SKU 的所有 Redis 缓存 Key
 * 3. 删除的 Key 包括：总库存 Key、所有分桶 Key、分桶数量 Key
 * 4. 使用 Canal es（event sequence）做版本号，防止乱序消费
 * 5. 删除操作天然幂等（删除不存在的 Key 不会报错）
 * </p>
 * <p>
 * 版本号防乱序机制：
 * - 在 Redis 中记录每个 skuId 的最新 Canal es 版本号
 * - 收到消息时，如果消息的 es <= Redis 中记录的版本号，说明是旧消息，跳过处理
 * - 这保证了网络延迟或 MQ 重试导致的乱序消息不会使缓存状态倒退
 * </p>
 * <p>
 * 下游读请求的缓存回填（Cache-Aside）：
 * 缓存被删除后，下一个读请求（InventoryService.getStock）会发现缓存未命中，
 * 从 MySQL 读取最新数据并回填 Redis。这就是 Cache-Aside 模式的标准流程。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "INVENTORY_CACHE_TOPIC",
        consumerGroup = "inventory-cache-evict-consumer-group",
        maxReconsumeTimes = 5
)
public class InventoryCacheEvictConsumer implements RocketMQListener<MessageExt> {

    private final StringRedisTemplate stringRedisTemplate;

    /** Redis Key 前缀（与 InventoryService 保持一致，使用 {skuId} hash tag） */
    private static final String BUCKET_KEY_PREFIX = "inventory:{%d}:bucket:";
    private static final String TOTAL_KEY_PREFIX = "inventory:{%d}:total";
    private static final String BUCKET_COUNT_KEY_PREFIX = "inventory:bucket:count:";
    /** Canal 版本号 Key 前缀（用于防乱序） */
    private static final String CANAL_VERSION_PREFIX = "inventory:canal:version:";
    /** Canal 版本号 Key 的过期时间（7天，防止无限增长） */
    private static final long CANAL_VERSION_TTL_SECONDS = 7 * 24 * 3600;

    private static String totalKey(Long skuId) { return String.format(TOTAL_KEY_PREFIX, skuId); }

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JSONObject canalMsg = JSON.parseObject(body);

            if (canalMsg == null) {
                log.error("[库存缓存失效] 消息体为空，跳过: msgId={}", msg.getMsgId());
                return;
            }

            // 校验 Canal 消息格式
            if (!canalMsg.containsKey("database") || !canalMsg.containsKey("data")) {
                log.error("[库存缓存失效] 消息格式无法识别，跳过: msgId={}", msg.getMsgId());
                return;
            }

            handleCanalMessage(canalMsg, msg.getMsgId(), msg.getReconsumeTimes());

        } catch (Exception e) {
            log.error("[库存缓存失效] 处理异常: msgId={}, reconsumeTimes={}",
                    msg.getMsgId(), msg.getReconsumeTimes(), e);
            throw new RuntimeException("库存缓存失效处理失败（可重试）", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * 处理 Canal 消息
     * <p>
     * 只处理 t_inventory 表的变更。对于其他表（如未来新增的表），忽略。
     * </p>
     */
    private void handleCanalMessage(JSONObject canalMsg, String msgId, int reconsumeTimes) {
        String table = canalMsg.getString("table");
        String type = canalMsg.getString("type");
        JSONArray dataArray = canalMsg.getJSONArray("data");

        // 只处理 t_inventory 表
        if (!"t_inventory".equals(table)) {
            log.debug("[库存缓存失效] 忽略非库存表变更: table={}", table);
            return;
        }

        if (dataArray == null || dataArray.isEmpty()) {
            log.warn("[库存缓存失效] Canal 消息 data 为空: msgId={}", msgId);
            return;
        }

        // 优先使用 es（event sequence，严格递增），降级使用 ts
        long canalVersion = canalMsg.getLongValue("es", 0);
        if (canalVersion == 0) {
            canalVersion = canalMsg.getLongValue("ts", System.currentTimeMillis());
        }

        for (int i = 0; i < dataArray.size(); i++) {
            JSONObject row = dataArray.getJSONObject(i);
            Long skuId = row.getLong("sku_id");
            if (skuId == null) {
                log.warn("[库存缓存失效] 行数据缺少 sku_id 字段，跳过: msgId={}", msgId);
                continue;
            }

            switch (type) {
                case "INSERT", "UPDATE" -> evictCache(skuId, canalVersion, type);
                case "DELETE" -> evictCacheCompletely(skuId, canalVersion);
                default -> log.debug("[库存缓存失效] 忽略事件类型: type={}", type);
            }
        }
    }

    /**
     * 删除 SKU 的 Redis 缓存（INSERT/UPDATE 事件）
     * <p>
     * 删除范围：
     * 1. 总库存 Key：inventory:{skuId}:total
     * 2. 所有分桶 Key：inventory:{skuId}:bucket:*
     * 3. 分桶数量 Key：inventory:bucket:count:{skuId}
     * </p>
     * <p>
     * 版本号防乱序：
     * 如果当前消息的 Canal 版本号 <= Redis 中记录的版本号，说明是旧消息，跳过。
     * 使用 Lua 脚本保证版本号比较和更新的原子性。
     * </p>
     * <p>
     * 为什么不直接更新 Redis 中的库存值？
     * 1. Canal 消息可能乱序（网络延迟、MQ 重试），直接 SET 可能将新值覆盖为旧值
     * 2. 删除缓存 + Cache-Aside 回填是更安全的做法：
     *    - 删除是幂等的，不会导致数据错误
     *    - 回填时从 MySQL 读取的是最新值
     *    - 即使回填时有新变更，Canal 会再次触发删除
     * </p>
     */
    private void evictCache(Long skuId, long canalVersion, String eventType) {
        String versionKey = CANAL_VERSION_PREFIX + skuId;

        // 原子版本号检查：只有当新版本 > 已记录版本时才执行删除
        // Lua 脚本：GET currentVersion → 如果 canalVersion > currentVersion → SET newVersion → 返回 1；否则返回 0
        String versionCheckScript = """
                local currentVersion = tonumber(redis.call('GET', KEYS[1]) or '0')
                local newVersion = tonumber(ARGV[1])
                if newVersion > currentVersion then
                    redis.call('SETEX', KEYS[1], tonumber(ARGV[2]), tostring(newVersion))
                    return 1
                else
                    return 0
                end
                """;

        Long versionResult = stringRedisTemplate.execute(
                new org.springframework.data.redis.core.script.DefaultRedisScript<>(versionCheckScript, Long.class),
                java.util.List.of(versionKey),
                String.valueOf(canalVersion),
                String.valueOf(CANAL_VERSION_TTL_SECONDS)
        );

        if (versionResult == null || versionResult == 0) {
            log.debug("[库存缓存失效] 跳过旧版本消息: skuId={}, canalVersion={}, eventType={}",
                    skuId, canalVersion, eventType);
            return;
        }

        // 执行缓存删除
        int deletedCount = doEvictCache(skuId);

        log.info("[库存缓存失效] 删除完成: skuId={}, canalVersion={}, eventType={}, deletedKeys={}",
                skuId, canalVersion, eventType, deletedCount);
    }

    /**
     * 完全删除 SKU 的 Redis 缓存（DELETE 事件 — 记录被删除）
     * <p>
     * 与 evictCache 不同的是：不需要版本号检查（记录都被删了，没有更新的消息了）。
     * 但仍然记录版本号，防止之前的 UPDATE 消息延迟到达后又把缓存写回来。
     * </p>
     */
    private void evictCacheCompletely(Long skuId, long canalVersion) {
        String versionKey = CANAL_VERSION_PREFIX + skuId;
        // 更新版本号为当前值（防止旧消息回填）
        stringRedisTemplate.opsForValue().set(versionKey, String.valueOf(canalVersion),
                CANAL_VERSION_TTL_SECONDS, TimeUnit.SECONDS);

        int deletedCount = doEvictCache(skuId);

        log.info("[库存缓存失效] 完全删除完成: skuId={}, canalVersion={}, deletedKeys={}",
                skuId, canalVersion, deletedCount);
    }

    /**
     * 实际执行缓存删除
     * <p>
     * 删除策略：
     * 1. 删除总库存 Key（确定性 Key，直接 DEL）
     * 2. 删除分桶数量 Key（确定性 Key，直接 DEL）
     * 3. 删除所有分桶 Key（需要 SCAN 找出所有匹配的 Key）
     * </p>
     * <p>
     * SCAN 而非 KEYS 的原因：
     * KEYS 命令会阻塞 Redis（O(N) 遍历整个 Keyspace），
     * SCAN 是增量遍历，不阻塞其他命令。
     * 生产环境必须使用 SCAN。
     * </p>
     * <p>
     * Cursor 安全管理：
     * 手动遍历 SCAN 结果并逐个删除，避免 stream 操作导致的 cursor 泄漏。
     * </p>
     *
     * @return 删除的 Key 数量
     */
    private int doEvictCache(Long skuId) {
        int deletedCount = 0;

        // 1. 删除总库存 Key
        String totalKey = totalKey(skuId);
        Boolean totalDeleted = stringRedisTemplate.delete(totalKey);
        if (Boolean.TRUE.equals(totalDeleted)) {
            deletedCount++;
        }

        // 2. 删除分桶数量 Key
        String bucketCountKey = BUCKET_COUNT_KEY_PREFIX + skuId;
        Boolean bucketCountDeleted = stringRedisTemplate.delete(bucketCountKey);
        if (Boolean.TRUE.equals(bucketCountDeleted)) {
            deletedCount++;
        }

        // 3. 删除所有分桶 Key（SCAN 增量遍历，不阻塞 Redis）
        String bucketPattern = String.format("inventory:{%d}:bucket:*", skuId);
        org.springframework.data.redis.core.Cursor<String> cursor = null;
        try {
            cursor = stringRedisTemplate.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(bucketPattern)
                            .count(64)  // 每次 SCAN 建议返回 64 条
                            .build());
            while (cursor.hasNext()) {
                String key = cursor.next();
                Boolean deleted = stringRedisTemplate.delete(key);
                if (Boolean.TRUE.equals(deleted)) {
                    deletedCount++;
                }
            }
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception e) {
                    log.warn("[库存缓存失效] SCAN cursor 关闭异常(不影响业务): skuId={}", skuId, e);
                }
            }
        }

        return deletedCount;
    }
}
