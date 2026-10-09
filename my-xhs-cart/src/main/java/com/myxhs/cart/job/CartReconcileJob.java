package com.myxhs.cart.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.cart.entity.CartItem;
import com.myxhs.cart.mapper.CartItemMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 购物车对账修复任务（XXL-Job 分布式调度）
 * <p>
 * 每天凌晨 4 点，Redis ↔ MySQL 对比，以 Redis 为准修复。
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 * <p>
 * 修复策略（Redis 为权威数据源）：
 * 1. Redis 有 + MySQL 无 → INSERT MySQL（MQ 消息丢失导致）
 * 2. Redis 有 + MySQL 有 + 数量不一致 → UPDATE MySQL（以 Redis 为准）
 * 3. Redis 无 + MySQL 有 → DELETE MySQL（用户已删除但 MQ 消息丢失）
 * </p>
 * <p>
 * 为什么以 Redis 为准？
 * - Redis 是权威数据源，所有用户操作直接写 Redis
 * - MySQL 只是异步持久化的兜底存储
 * - 如果 Redis 数据丢失（主从切换/重启），则以 MySQL 为准恢复 Redis
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartReconcileJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final CartItemMapper cartItemMapper;
    private final com.myxhs.cart.mapper.CartEventMapper cartEventMapper;
    private final IdGeneratorUtil idGeneratorUtil;

    /**
     * 对账水位线（秒）：MySQL 侧存在"最近 N 秒内写入"的用户视为在途（消息已发、落库未完成），
     * 本轮跳过、留给下一轮对账。避免对账与在线写竞争，把"正常在途差异"误判成数据不一致。
     */
    @Value("${myxhs.cart.reconcile.watermark-seconds:300}")
    private long watermarkSeconds;

    /** 本轮因水位线跳过的用户数（每次执行开始重置）；Atomic 因为管理端点可并发调用 reconcileUser */
    private final java.util.concurrent.atomic.AtomicInteger watermarkSkipped =
            new java.util.concurrent.atomic.AtomicInteger();

    private static final String KEY_PREFIX = "myxhs:cart:{";
    private static final String ITEMS_KEY_SUFFIX = "}:items";
    private static final String CHECKED_KEY_SUFFIX = "}:checked";
    private static final String CLEARED_KEY_SUFFIX = "}:cleared";

    private static String itemsKey(Long userId) { return KEY_PREFIX + userId + ITEMS_KEY_SUFFIX; }
    private static String checkedKey(Long userId) { return KEY_PREFIX + userId + CHECKED_KEY_SUFFIX; }
    private static String clearedMarkerKey(Long userId) { return KEY_PREFIX + userId + CLEARED_KEY_SUFFIX; }

    /**
     * 购物车对账修复（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 4 * * ?（每天凌晨 4 点）
     */
    @XxlJob("cartReconcileJob")
    public void reconcile() {
        // 分布式锁: 防定时任务+手动端点并发对账(XXL-Job多实例/广播模式)
        // RV30：锁值用随机 token 并以 Lua 比对删除，避免误删他人重新获取的锁
        String lockKey = "myxhs:lock:cart:reconcile";
        String token = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, 600, java.util.concurrent.TimeUnit.SECONDS);
        if (locked == null || !locked) {
            log.info("[购物车对账] 已有实例执行中，跳过");
            return;
        }
        try {
            doReconcile();
            purgeStaleEvents();
        } finally {
            stringRedisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class), java.util.List.of(lockKey), token);
        }
    }

    /**
     * 带分布式锁的单用户对账（管理端点复用）
     * <p>
     * 原管理端点直接调 {@link #reconcileUser(Long)}，绕过对账锁 → 可与定时全量/其他手动调用并发，
     * 双方对同一用户做"读-判断-写"时可能互相覆盖（例如一方按缺失补录、另一方按差异删除）。
     * 这里复用与定时任务同一把锁（token + Lua 比对释放）。
     * </p>
     */
    public int reconcileUserWithLock(Long userId) {
        String lockKey = "myxhs:lock:cart:reconcile";
        String token = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, token, 600, java.util.concurrent.TimeUnit.SECONDS);
        if (locked == null || !locked) {
            throw new com.myxhs.common.exception.BizException(
                    com.myxhs.common.response.ResultCode.RATE_LIMIT_REJECT, "已有对账任务执行中，请稍后再试");
        }
        try {
            return reconcileUser(userId);
        } finally {
            stringRedisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class), java.util.List.of(lockKey), token);
        }
    }

    private void doReconcile() {
        log.info("[购物车对账] 开始执行...");
        long startTime = System.currentTimeMillis();
        watermarkSkipped.set(0);
        int repairCount = 0;
        int userCount = 0;
        int batchSize = 1000;  // C-16: 游标分页，每批 1000 用户

        try {
            long lastUserId = 0;
            while (true) {
                List<Long> batch = cartItemMapper.selectDistinctUserIdsByCursor(lastUserId, batchSize);
                if (batch.isEmpty()) break;

                for (Long userId : batch) {
                    repairCount += reconcileUser(userId);
                }
                userCount += batch.size();
                lastUserId = batch.get(batch.size() - 1);
                log.debug("[购物车对账] 进度: 已对账 {} 用户", userCount);
            }

            // P2-8: 纯 Redis 用户（MySQL 无记录，MQ 落库丢失）不在 MySQL 枚举源内 → 补充扫描 Redis 用户集
            java.util.Set<String> redisUsers = new java.util.HashSet<>();
            var scanOpts = org.springframework.data.redis.core.ScanOptions.scanOptions()
                    .match(KEY_PREFIX + "*" + ITEMS_KEY_SUFFIX).count(500).build();
            try (var cursor = stringRedisTemplate.scan(scanOpts)) {
                cursor.forEachRemaining(redisUsers::add);
            }
            int redisOnlyCount = 0;
            for (String key : redisUsers) {
                String userIdStr = key.substring(KEY_PREFIX.length(), key.length() - ITEMS_KEY_SUFFIX.length());
                try {
                    Long userId = Long.valueOf(userIdStr);
                    repairCount += reconcileUser(userId);
                    redisOnlyCount++;
                } catch (NumberFormatException ignored) {
                    log.debug("[购物车对账] 跳过异常 key: {}", key);
                }
            }
            userCount += redisOnlyCount;

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("[购物车对账] 完成: 对账{}个用户, 修复{}条记录, 水位线跳过{}个用户, 耗时{}ms",
                    userCount, repairCount, watermarkSkipped.get(), elapsed);
            XxlJobHelper.handleSuccess("对账完成，修复 " + repairCount + " 条记录，水位线跳过 " + watermarkSkipped.get() + " 个用户");
        } catch (Exception e) {
            log.error("[购物车对账] 执行异常", e);
            XxlJobHelper.handleFail("购物车对账异常: " + e.getMessage());
        }
    }

    /**
     * 对账单个用户的购物车（C-01: 改为 public 供管理端点调用）
     *
     * @return 修复的记录数
     */
    public int reconcileUser(Long userId) {
        String itemsKey = itemsKey(userId);
        int repairCount = 0;

        // 获取 MySQL 中的购物车数据
        List<CartItem> mysqlItems = cartItemMapper.selectList(
                new LambdaQueryWrapper<CartItem>().eq(CartItem::getUserId, userId));

        // 【对账水位线】MySQL 侧最近有写入 → 该用户存在在途操作（MQ 在途/事务未提交），
        // 本轮跳过，避免与在线写竞争把正常在途差异误判为不一致；下一轮该用户静止后再对账。
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(watermarkSeconds);
        boolean inFlight = mysqlItems.stream()
                .anyMatch(item -> item.getUpdatedAt() != null && item.getUpdatedAt().isAfter(cutoff));
        if (inFlight) {
            watermarkSkipped.incrementAndGet();
            log.debug("[购物车对账] 命中水位线跳过(近 {}s 内有写入): userId={}", watermarkSeconds, userId);
            return 0;
        }

        // 【防双份全丢】检测 itemsKey 是否存在：
        // key 不存在有两种可能——(a)用户清空购物车（clearCart 删除 key）；(b)Redis 故障丢数据（failover/重启无AOF）。
        // 若为(b)，场景3（Redis无+MySQL有→DELETE MySQL）会把 MySQL 兜底数据也删掉 → 双份全丢。
        // 保守策略：key 不存在时跳过场景3。代价是丢失 CLEAR 事件时的陈旧 MySQL 行残留，
        // 但读取以 Redis 为准所以陈旧行对用户不可见，仅占用存储。
        Boolean keyExists = stringRedisTemplate.hasKey(itemsKey);
        boolean cleared = Boolean.TRUE.equals(stringRedisTemplate.hasKey(clearedMarkerKey(userId)));
        boolean skipDeleteScenario = !Boolean.TRUE.equals(keyExists) && !cleared;
        if (skipDeleteScenario && !mysqlItems.isEmpty()) {
            log.warn("[购物车对账] itemsKey不存在且无cleared标记, 跳过删除场景(防Redis故障时误删MySQL兜底): userId={}, mysqlItems={}",
                    userId, mysqlItems.size());
        }

        // 获取 Redis 中的购物车数据
        Map<Object, Object> redisItems = stringRedisTemplate.opsForHash().entries(itemsKey);
        Set<String> redisChecked = stringRedisTemplate.opsForSet().members(checkedKey(userId));

        // 场景 1：Redis 有 + MySQL 无 → INSERT MySQL（MQ 消息丢失导致）
        for (Map.Entry<Object, Object> entry : redisItems.entrySet()) {
            String skuIdStr = entry.getKey().toString();
            int redisQty;
            try {
                redisQty = Integer.parseInt(entry.getValue().toString());
            } catch (NumberFormatException e) {
                log.warn("[购物车对账] 跳过脏数据(数量非数字): userId={}, skuId={}, value={}",
                        userId, skuIdStr, entry.getValue());
                continue;
            }
            Long skuId;
            try {
                skuId = Long.parseLong(skuIdStr);
            } catch (NumberFormatException e) {
                log.warn("[购物车对账] 跳过脏数据(skuId非数字): userId={}, key={}",
                        userId, skuIdStr);
                continue;
            }

            boolean existsInMysql = mysqlItems.stream()
                    .anyMatch(item -> item.getSkuId().equals(skuId));

            if (!existsInMysql) {
                try {
                    boolean isChecked = redisChecked != null && redisChecked.contains(skuIdStr);
                    CartItem item = new CartItem();
                    item.setId(idGeneratorUtil.nextId());
                    item.setUserId(userId);
                    item.setSkuId(skuId);
                    item.setQuantity(redisQty);
                    item.setChecked(isChecked ? 1 : 0);
                    cartItemMapper.insert(item);
                    repairCount++;
                    log.info("[购物车对账] 补录MySQL: userId={}, skuId={}, qty={}",
                            userId, skuId, redisQty);
                } catch (Exception e) {
                    log.warn("[购物车对账] 补录失败(可能已存在): userId={}, skuId={}",
                            userId, skuId);
                }
            }
        }

        // 场景 2：Redis 有 + MySQL 有 + 数量/选中状态不一致 → UPDATE MySQL
        for (CartItem mysqlItem : mysqlItems) {
            Object redisQtyObj = redisItems.get(String.valueOf(mysqlItem.getSkuId()));
            if (redisQtyObj != null) {
                int redisQty;
                try {
                    redisQty = Integer.parseInt(redisQtyObj.toString());
                } catch (NumberFormatException e) {
                    log.warn("[购物车对账] 跳过脏数据(数量非数字): userId={}, skuId={}, value={}",
                            userId, mysqlItem.getSkuId(), redisQtyObj);
                    continue;
                }
                boolean isChecked = redisChecked != null
                        && redisChecked.contains(String.valueOf(mysqlItem.getSkuId()));
                int redisCheckedVal = isChecked ? 1 : 0;

                if (redisQty != mysqlItem.getQuantity() || redisCheckedVal != mysqlItem.getChecked()) {
                    int oldQty = mysqlItem.getQuantity();
                    int oldChecked = mysqlItem.getChecked();
                    mysqlItem.setQuantity(redisQty);
                    mysqlItem.setChecked(redisCheckedVal);
                    cartItemMapper.updateById(mysqlItem);
                    repairCount++;
                    log.info("[购物车对账] 修复: userId={}, skuId={}, qty: {}→{}, checked: {}→{}",
                            userId, mysqlItem.getSkuId(), oldQty, redisQty, oldChecked, redisCheckedVal);
                }
            }
        }

        // 场景 3：Redis 无 + MySQL 有 → DELETE MySQL（用户已删除）
        // 仅在 itemsKey 存在时执行（key 不存在可能是 Redis 故障丢数据，此时删除会摧毁 MySQL 兜底）
        if (!skipDeleteScenario) {
            for (CartItem mysqlItem : mysqlItems) {
                boolean existsInRedis = redisItems.containsKey(String.valueOf(mysqlItem.getSkuId()));
                if (!existsInRedis) {
                    cartItemMapper.deleteById(mysqlItem.getId());
                    repairCount++;
                    log.info("[购物车对账] 删除MySQL残留: userId={}, skuId={}",
                            userId, mysqlItem.getSkuId());
                }
            }
        }

        return repairCount;
    }

    /**
     * 事件流水保留策略：清理 30 天前（append-only 表必须配保留策略，否则无限增长）
     */
    private void purgeStaleEvents() {
        // 有界排空：本任务每天 4 点跑一次，单批 5000 追不上日增量 → 循环到"清空或触顶"
        final int batch = 5000;
        final int maxBatches = 20;   // 单次最多 10 万行/日，超出部分次日继续（会打 WARN）
        int total = 0;
        try {
            for (int i = 0; i < maxBatches; i++) {
                int purged = cartEventMapper.deleteStaleEvents(
                        java.time.LocalDateTime.now().minusDays(30), batch);
                total += purged;
                if (purged < batch) {
                    break;
                }
                if (i == maxBatches - 1) {
                    log.warn("[购物车对账] 事件流水清理触顶({} 行)，剩余留待次日（检查日增量是否超 10 万）", total);
                }
            }
            if (total > 0) {
                log.info("[购物车对账] 清理 30 天前事件流水 {} 行", total);
            }
        } catch (Exception e) {
            log.error("[购物车对账] 事件流水清理失败(已清 {} 行, 下轮重试)", total, e);
        }
    }
}
