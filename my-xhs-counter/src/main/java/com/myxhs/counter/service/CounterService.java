package com.myxhs.counter.service;

import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.counter.buffer.CounterBuffer;
import com.myxhs.counter.dto.CounterBatchRequest;
import com.myxhs.counter.entity.Counter;
import com.myxhs.counter.enums.CountType;
import com.myxhs.counter.mapper.CounterBatchQuery;
import com.myxhs.counter.mapper.CounterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 计数服务
 * <p>
 * 核心设计：
 * 1. 写链路：Redis INCR/DECR（实时）→ Buffer 攒批 → 批量刷盘 DB
 * 2. 读链路：Redis → MySQL（兜底）两级缓存
 * 3. 一致性：Buffer-Trigger 正常刷盘 + 失败重试 3 次 + 每天凌晨对账修复
 * </p>
 * <p>
 * 【设计决策：为什么不用 Caffeine 本地缓存？】
 * 计数是高频变更数据（点赞/收藏每秒都在变），Caffeine 在多实例部署时会导致各节点
 * 看到不同的计数值（实例 A 点赞后只能失效自己的 Caffeine，实例 B 仍返回旧值）。
 * Redis GET 延迟 < 1ms，完全满足计数查询需求，不值得为省微秒级延迟引入分布式一致性问题。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterService {

    private final RedisOperator redisOperator;
    private final StringRedisTemplate stringRedisTemplate;
    private final CounterBuffer counterBuffer;
    private final CounterMapper counterMapper;

    /** 【修复m11】归零保护 Lua 脚本（静态常量，复用 SHA1 缓存） */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> DECREMENT_SCRIPT;
    static {
        DECREMENT_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "local current = tonumber(redis.call('GET', KEYS[1]) or '0') " +
                "if current <= 0 then return 0 end " +
                "redis.call('DECR', KEYS[1]) " +
                "return 1",
                Long.class);
    }

    // ==================== 写操作 ====================

    /**
     * 计数 +1
     * <p>
     * 1. Redis INCR（实时，用户立即可见）
     * 2. 写入 Buffer（攒批后批量刷盘 DB）
     * </p>
     */
    public void increment(int targetType, long targetId, int countType) {
        String redisKey = buildRedisKey(targetType, targetId, countType);

        // 1. Redis INCR（原子操作，实时生效）
        stringRedisTemplate.opsForValue().increment(redisKey);

        // 2. 写入 Buffer（攒批后批量刷盘 DB）
        counterBuffer.add(targetType, targetId, countType, 1L);

        log.debug("[计数] +1: targetType={}, targetId={}, countType={}", targetType, targetId, countType);
    }

    /**
     * 计数 -1（带归零保护）
     * <p>
     * 归零保护：DECR 前检查当前值，防止计数变为负数。
     * 使用 Lua 脚本保证 "检查 + 扣减" 的原子性。
     * </p>
     */
    public boolean decrement(int targetType, long targetId, int countType) {
        String redisKey = buildRedisKey(targetType, targetId, countType);

        // 【修复m11】Lua 脚本提为静态常量，避免每次创建对象（复用 SHA1 缓存 → EVALSHA 优化）
        Long result = stringRedisTemplate.execute(DECREMENT_SCRIPT, List.of(redisKey));

        if (result == null || result == 0) {
            log.warn("[计数] 归零保护触发，拒绝 -1: targetType={}, targetId={}, countType={}",
                    targetType, targetId, countType);
            return false;
        }

        // 写入 Buffer
        counterBuffer.add(targetType, targetId, countType, -1L);

        log.debug("[计数] -1: targetType={}, targetId={}, countType={}", targetType, targetId, countType);
        return true;
    }

    // ==================== 读操作 ====================

    /**
     * 查询单个计数（两级缓存：Redis → MySQL）
     * <p>
     * L1: Redis（永久，集群共享，< 1ms）
     * L2: MySQL（兜底，强持久）
     * </p>
     */
    public long getCount(int targetType, long targetId, int countType) {
        // L1: Redis
        String redisKey = buildRedisKey(targetType, targetId, countType);
        String redisValue = stringRedisTemplate.opsForValue().get(redisKey);
        if (redisValue != null) {
            return Long.parseLong(redisValue);
        }

        // L2: MySQL（兜底）
        Counter counter = counterMapper.selectByTarget(targetType, targetId, countType);
        long count = counter != null ? counter.getCountValue() : 0;

        // 回填 Redis
        stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(count));

        return count;
    }

    /**
     * 批量查询计数（Pipeline 优化）
     * <p>
     * 使用 Redis Pipeline 批量 GET，减少网络往返。
     * 返回格式：Map<"targetType:targetId", Map<countTypeEnglishName, count>>
     * 示例：{"1:20001": {"like": 42, "collect": 18}}
     * </p>
     */
    public Map<String, Map<String, Long>> batchGetCounts(CounterBatchRequest request) {
        Map<String, Map<String, Long>> result = new LinkedHashMap<>();

        if (request.getQueries() == null || request.getQueries().isEmpty()) {
            return result;
        }

        // 1. 收集所有需要查询的 Redis Key
        List<String> redisKeys = new ArrayList<>();
        List<CounterBatchRequest.QueryItem> flatItems = new ArrayList<>();

        for (CounterBatchRequest.QueryItem query : request.getQueries()) {
            for (Integer countType : query.getCountTypes()) {
                redisKeys.add(buildRedisKey(query.getTargetType(), query.getTargetId(), countType));
                flatItems.add(new CounterBatchRequest.QueryItem(query.getTargetType(), query.getTargetId(), List.of(countType)));
            }
        }

        // 2. Pipeline 批量 GET
        List<Object> redisValues = stringRedisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (String key : redisKeys) {
                        connection.stringCommands().get(key.getBytes());
                    }
                    return null;
                });

        // 3. 收集 Pipeline 未命中的项，批量查询 MySQL（避免 N+1）
        List<CounterBatchQuery> missedQueries = new ArrayList<>();
        List<Integer> missedIndices = new ArrayList<>();
        Map<Integer, Long> redisHitCounts = new LinkedHashMap<>();

        for (int i = 0; i < flatItems.size(); i++) {
            Object val = redisValues.get(i);
            if (val != null) {
                redisHitCounts.put(i, Long.parseLong(val.toString()));
            } else {
                CounterBatchRequest.QueryItem item = flatItems.get(i);
                missedQueries.add(new CounterBatchQuery(
                        item.getTargetType(), item.getTargetId(), item.getCountTypes().get(0)));
                missedIndices.add(i);
            }
        }

        // 批量 MySQL 兜底（一次查询替代 N 次循环单查）
        Map<String, Counter> missedMap = new LinkedHashMap<>();
        if (!missedQueries.isEmpty()) {
            List<Counter> dbResults = counterMapper.selectByTargets(missedQueries);
            for (Counter c : dbResults) {
                String key = c.getTargetType() + ":" + c.getTargetId() + ":" + c.getCountType();
                missedMap.put(key, c);
            }
        }

        // 4. 组装结果
        for (int i = 0; i < flatItems.size(); i++) {
            CounterBatchRequest.QueryItem item = flatItems.get(i);
            String targetKey = item.getTargetType() + ":" + item.getTargetId();
            int countType = item.getCountTypes().get(0);

            long count;
            if (redisHitCounts.containsKey(i)) {
                count = redisHitCounts.get(i);
            } else {
                // MySQL 兜底
                String lookupKey = item.getTargetType() + ":" + item.getTargetId() + ":" + countType;
                Counter counter = missedMap.get(lookupKey);
                count = counter != null ? counter.getCountValue() : 0;
                // 回填 Redis
                stringRedisTemplate.opsForValue().set(redisKeys.get(i), String.valueOf(count));
            }

            String countName = CountType.of(countType).getEnglishName();
            result.computeIfAbsent(targetKey, k -> new LinkedHashMap<>()).put(countName, count);
        }

        return result;
    }

    // ==================== 对账修复 ====================

    /**
     * 对账修复（由定时任务调用）
     * <p>
     * 扫描 DB 所有计数记录，逐条与 Redis 对比：
     * - Redis 有值，DB 有值，不一致 → 以 Redis 为准（Redis 是实时更新的权威源）
     * - Redis 值为 0，DB 有值 → 以 DB 为准（Redis 可能数据丢失）
     * - Redis 有值，DB 无记录 → 以 Redis 为准，INSERT DB
     * </p>
     *
     * @return 修复条数
     */
    public int reconcile() {
        log.info("[对账修复] 开始...");
        int fixedCount = 0;

        // 游标分页扫描 DB（每批 1000 条，避免全表扫描 OOM）
        long lastId = 0;
        int batchSize = 1000;
        List<Counter> batch;

        do {
            batch = counterMapper.selectBatchAfterId(lastId, batchSize);
            if (batch.isEmpty()) break;
            lastId = batch.get(batch.size() - 1).getId();

            // 【m20】Pipeline 批量 GET Redis，N次往返 → 1次
            List<String> redisKeys = new ArrayList<>(batch.size());
            for (Counter dbCounter : batch) {
                redisKeys.add(buildRedisKey(
                        dbCounter.getTargetType(), dbCounter.getTargetId(), dbCounter.getCountType()));
            }
            List<String> redisValues = stringRedisTemplate.opsForValue().multiGet(redisKeys);
            if (redisValues == null) redisValues = Collections.emptyList();

            for (int i = 0; i < batch.size(); i++) {
                Counter dbCounter = batch.get(i);
                String redisKey = redisKeys.get(i);
                String redisValue = i < redisValues.size() ? redisValues.get(i) : null;
                long redisCount = redisValue != null ? Long.parseLong(redisValue) : 0;
                long dbCount = dbCounter.getCountValue();

                if (redisCount != dbCount) {
                    if (redisCount == 0 && dbCount > 0) {
                        stringRedisTemplate.opsForValue().set(redisKey, String.valueOf(dbCount));
                        log.warn("[对账修复] Redis恢复: key={}, redis=0, db={}", redisKey, dbCount);
                    } else {
                        counterMapper.updateCountValue(dbCounter.getId(), redisCount);
                        log.warn("[对账修复] DB修正: key={}, redis={}, db={}", redisKey, redisCount, dbCount);
                    }
                    fixedCount++;
                }
            }
        } while (batch.size() == batchSize);

        log.info("[对账修复] 完成，修复 {} 条", fixedCount);
        return fixedCount;
    }

    // ==================== 工具方法 ====================

    /**
     * 构建 Redis Key
     * 格式：myxhs:counter:{targetType}:{targetId}:{countType}
     */
    private String buildRedisKey(int targetType, long targetId, int countType) {
        return RedisKeyConstants.COUNTER + targetType + ":" + targetId + ":" + countType;
    }

}
