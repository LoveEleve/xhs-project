package com.myxhs.counter.service;

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

import java.time.Duration;
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

    /**
     * 原子去重 + 增减 Lua 脚本（生产级 MQ 幂等保护）
     * <p>
     * KEYS[1]: dedup key（myxhs:counter:dedup:{msgId}）
     * KEYS[2]: counter key（myxhs:counter:{targetType}:{targetId}:{countType}）
     * ARGV[1]: delta（+1 或 -1）
     * ARGV[2]: dedup TTL（秒，默认 7200 = 2小时）
     * <p>
     * 返回 List [status, currentCount]：
     *   status: 1=执行成功, 0=已去重（跳过）, -1=归零保护触发
     * <p>
     * 原子性保证：去重设置 + 计数增减在同一个 Lua 执行单元中完成，
     * 消除「去重成功但计数未执行」或「计数已执行但去重未设置」的竞态窗口。
     * 归零保护触发时不删除去重标记——消息是有效的业务拒绝，不需重试。
     * </p>
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<List> INCR_WITH_DEDUP_SCRIPT;
    static {
        // O-Counter-1 修复（2026-08-13）：counterKey 续期 30 天（P2-6 遗漏 dedup 路径——
        // 评论/收藏/分享/VIEW/关注计数 key 原为永久 TTL=-1，冷 key 永不回收）
        INCR_WITH_DEDUP_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "if redis.call('EXISTS', KEYS[1]) == 1 then return {0, 0} end " +
                "redis.call('SET', KEYS[1], '1', 'EX', ARGV[2]) " +
                "local delta = tonumber(ARGV[1]) " +
                "if delta > 0 then " +
                "  redis.call('INCRBY', KEYS[2], delta) " +
                "else " +
                "  local current = tonumber(redis.call('GET', KEYS[2]) or '0') " +
                "  if current <= 0 then " +
                "    return {-1, 0} " +
                "  end " +
                "  redis.call('INCRBY', KEYS[2], delta) " +  // delta is negative, so INCRBY works
                "end " +
                // O-Counter-1 修复（2026-08-13）：counterKey 续期 30 天（P2-6 遗漏 dedup 路径）
                // 注意：EXPIRE 必须在 INCRBY 之后——key 首次创建前 EXPIRE 无效（回归发现 TTL=-1 瞬态根因）
                "redis.call('EXPIRE', KEYS[2], ARGV[3]) " +
                "return {1, tonumber(redis.call('GET', KEYS[2]))}",
                List.class);
    }

    /** MQ 去重 TTL（2 小时，覆盖 MQ 最大重试窗口） */
    private static final long DEDUP_TTL_SECONDS = Duration.ofHours(2).toSeconds();
    /** O-Counter-1：计数 key 续期 TTL（对齐 P2-6 的 30 天，dedup 路径补漏） */
    private static final long COUNTER_TTL_SECONDS = Duration.ofDays(30).toSeconds();

    /**
     * 【修复H2】Set-based Like/Unlike 原子 Lua 脚本 — 解决 MQ 乱序计数虚增
     * <p>
     * KEYS[1]: dedup key（myxhs:counter:dedup:{msgId}）
     * KEYS[2]: like set key（myxhs:like:set:{targetType}:{targetId}）
     * KEYS[3]: counter key（myxhs:counter:{targetType}:{targetId}:{countType}）
     * ARGV[1]: member（userId — SADD/SREM 的目标元素）
     * ARGV[2]: action（"ADD" 或 "REMOVE"）
     * ARGV[3]: dedup TTL
     * <p>
     * 返回 List [status, count, changed]：
     *   status: 1=执行成功, 0=已去重（跳过）
     *   count: 当前 Set 的 SCARD
     *   changed: SADD/SREM 的实际变更量（1=新增/删除, 0=成员已存在/不存在）
     * <p>
     * 为什么用 Set 替代 delta 计数：
     * - delta 方式下，UNLIKE 先到、LIKE 后到 → 计数值 +1（实际应为 0）
     * - Set 方式下，SADD/SREM 天然幂等，乱序到达不影响最终 SCARD 结果
     * </p>
     */
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<List> LIKE_SET_SCRIPT;
    static {
        LIKE_SET_SCRIPT = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                "if redis.call('EXISTS', KEYS[1]) == 1 then return {0, 0, 0} end " +
                "redis.call('SET', KEYS[1], '1', 'EX', ARGV[3]) " +
                "local changed " +
                "if ARGV[2] == 'ADD' then " +
                "  changed = redis.call('SADD', KEYS[2], ARGV[1]) " +
                "else " +
                "  changed = redis.call('SREM', KEYS[2], ARGV[1]) " +
                "end " +
                "local count = redis.call('SCARD', KEYS[2]) " +
                "redis.call('SET', KEYS[3], count) " +
                // T-035 延伸（2026-08-13）：Set-based 路径补 30 天续期（原遗漏——点赞计数 key 永久 TTL=-1）
                "redis.call('EXPIRE', KEYS[3], ARGV[4]) " +
                "return {1, count, changed}",  // changed = 0/1，反映 Set 的真实变更量
                List.class);
    }

    /** Like Set Key 前缀 */
    // counter内部的like Set(优化展示缓存), 与analytics权威Set(myxhs:like:note:/myxhs:like:comment:)独立维护
    private static final String LIKE_SET_PREFIX = "myxhs:like:set:";

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
        // P2-6: 计数 key 永久无界 → 每次写入续期 30 天（活跃 key 不丢，冷 key 自动回收）
        stringRedisTemplate.expire(redisKey, java.time.Duration.ofDays(30));

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

    /**
     * 计数 +1（带 MQ 去重，由 Consumer 调用）
     * <p>
     * 原子去重 + INCR：Lua 脚本保证「检查 msgId 是否已处理」和「INCR」
     * 在同一个 Redis 命令中完成，消除 MQ 重复消费导致的计数偏差。
     * </p>
     *
     * @param msgId     MQ 消息 ID（用作去重 Key）
     * @return true=执行成功, false=重复消息（已处理过）
     */
    public boolean incrementWithDedup(String msgId, int targetType, long targetId, int countType) {
        return incrWithDedup(msgId, targetType, targetId, countType, 1);
    }

    /**
     * O-Counter-2 修复（2026-08-13）：支持自定义 delta（UNCOMMENT 级联删除 count>1）
     */
    public boolean incrementWithDedup(String msgId, int targetType, long targetId, int countType, long delta) {
        return incrWithDedup(msgId, targetType, targetId, countType, delta);
    }

    public boolean decrementWithDedup(String msgId, int targetType, long targetId, int countType) {
        return incrWithDedup(msgId, targetType, targetId, countType, -1);
    }

    public boolean decrementWithDedup(String msgId, int targetType, long targetId, int countType, long delta) {
        return incrWithDedup(msgId, targetType, targetId, countType, -delta);
    }

    /**
     * O-Counter-2：带 delta 的 dedup 计数（Lua 已支持 ARGV[1] 任意 delta）
     */
    private boolean incrWithDedup(String msgId, int targetType, long targetId, int countType, long delta) {
        String dedupKey = buildDedupKey(msgId);
        String counterKey = buildRedisKey(targetType, targetId, countType);

        @SuppressWarnings("unchecked")
        List<Long> result = stringRedisTemplate.execute(
                INCR_WITH_DEDUP_SCRIPT,
                List.of(dedupKey, counterKey),
                String.valueOf(delta), String.valueOf(DEDUP_TTL_SECONDS), String.valueOf(COUNTER_TTL_SECONDS));

        if (result == null || result.isEmpty()) {
            throw new RuntimeException("Lua 脚本返回异常: null or empty");
        }

        long status = result.get(0);
        if (status == 0) {
            log.info("[计数-去重] 重复消息跳过: msgId={}, targetType={}, targetId={}, countType={}",
                    msgId, targetType, targetId, countType);
            return false;
        }
        if (status == -1) {
            log.warn("[计数-去重] 归零保护触发: msgId={}, targetType={}, targetId={}, countType={}",
                    msgId, targetType, targetId, countType);
            return false;
        }
        // status == 1: 变更成功，写入 Buffer
        counterBuffer.add(targetType, targetId, countType, delta);
        log.debug("[计数] 去重变更{}: msgId={}, targetType={}, targetId={}, countType={}",
                delta, msgId, targetType, targetId, countType);
        return true;
    }

    /**
     * 【修复H2】Set-based Like/Unlike 计数（带 MQ 去重）
     * <p>
     * 使用 Redis Set 替代增量计数，SADD/SREM 天然幂等，乱序到达不影响最终计数。
     * </p>
     *
     * @param msgId     MQ 消息 ID（用作去重 Key）
     * @param targetType 目标类型（1-笔记 3-评论）
     * @param targetId   目标ID（noteId 或 commentId）
     * @param userId     操作用户ID
     * @param isLike     true=点赞, false=取消点赞
     * @return true=执行成功, false=重复消息
     */
    public boolean setBasedLikeWithDedup(String msgId, int targetType, long targetId,
                                          Long userId, boolean isLike) {
        String dedupKey = buildDedupKey(msgId);
        String likeSetKey = LIKE_SET_PREFIX + targetType + ":" + targetId;
        String counterKey = buildRedisKey(targetType, targetId, 1); // countType=1 LIKE

        // 懒迁移：counter Set 为空但 counter 值 > 0（有历史数据），从 analytics 权威 Set 同步
        boolean migrated = tryLazyMigrateLikeSet(targetType, targetId, likeSetKey, counterKey);

        @SuppressWarnings("unchecked")
        List<Long> result = stringRedisTemplate.execute(
                LIKE_SET_SCRIPT,
                List.of(dedupKey, likeSetKey, counterKey),
                String.valueOf(userId), isLike ? "ADD" : "REMOVE",
                String.valueOf(DEDUP_TTL_SECONDS), String.valueOf(COUNTER_TTL_SECONDS));

        if (result == null || result.isEmpty()) {
            throw new RuntimeException("LikeSet Lua 脚本返回异常: null or empty");
        }

        long status = result.get(0);
        if (status == 0) {
            log.info("[计数-LikeSet] 重复消息跳过: msgId={}, targetType={}, targetId={}, userId={}",
                    msgId, targetType, targetId, userId);
            return false;
        }

        long newCount = result.get(1);
        long changed = result.get(2);
        // 写入 Buffer（delta = 0/±1，反映 Set 的真实变更量，changed=0表示SADD/SREM无实际变更）
        if (changed != 0) {
            counterBuffer.add(targetType, targetId, 1, isLike ? changed : -changed);
        }
        log.info("[计数-LikeSet] {}: msgId={}, targetType={}, targetId={}, userId={}, count={}, changed={}",
                isLike ? "点赞" : "取消点赞", msgId, targetType, targetId, userId, newCount, changed);
        return true;
    }

    /**
     * 懒迁移：将 analytics 权威点赞 Set 的成员同步到 counter Set
     * <p>触发条件：counter Set 为空但 counter 值 > 0（有历史数据未被 Set-based 计数覆盖）</p>
     */
    private boolean tryLazyMigrateLikeSet(int targetType, long targetId, String likeSetKey, String counterKey) {
        Long setSize = stringRedisTemplate.opsForSet().size(likeSetKey);
        if (setSize != null && setSize > 0) return false; // 已迁移

        String counterValue = stringRedisTemplate.opsForValue().get(counterKey);
        if (counterValue == null || Long.parseLong(counterValue) == 0) return false; // 无历史数据

        // 构建 analytics 权威 Set key
        String analyticsKey = RedisKeyConstants.LIKE_SET + (targetType == 1 ? "note:" : "comment:") + targetId;
        java.util.Set<String> members = stringRedisTemplate.opsForSet().members(analyticsKey);
        if (members == null || members.isEmpty()) return false;

        // 批量迁移
        String[] memberArray = members.toArray(new String[0]);
        stringRedisTemplate.opsForSet().add(likeSetKey, memberArray);
        // 用 SCARD 覆盖 counter 值
        Long actualCount = stringRedisTemplate.opsForSet().size(likeSetKey);
        if (actualCount != null) {
            stringRedisTemplate.opsForValue().set(counterKey, String.valueOf(actualCount));
            stringRedisTemplate.expire(counterKey, java.time.Duration.ofDays(30));
        }
        log.info("[计数-LikeSet] 懒迁移完成: targetType={}, targetId={}, members={}, count={}",
                targetType, targetId, memberArray.length, actualCount);
        return true;
    }

    /** 构建 MQ 消息去重 Key */
    private String buildDedupKey(String msgId) {
        return RedisKeyConstants.COUNTER_DEDUP + msgId;
    }

    /**
     * 从 analytics 权威 Set 对账修正 counter LIKE 计数
     * <p>analytics 的 myxhs:like:note:{noteId} 是业务权威数据源，
     * counter 的 like:set:{type}:{id} 是优化展示缓存——两者漂移时以 analytics 为准</p>
     * @return 修复的计数条数
     */
    public int reconcileLikeFromAnalytics() {
        int fixed = 0;
        // 覆盖笔记和评论两种 like 计数：targetType=1(note) targetType=3(comment)
        for (int targetType : new int[]{1, 3}) {
            String typeName = targetType == 1 ? "note" : "comment";
            String counterPrefix = RedisKeyConstants.COUNTER + targetType + ":";
            // 扫描所有 counter key（like countType=1），与 analytics SCARD 对比
            var cursor = stringRedisTemplate.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(counterPrefix + "*" + ":1")
                            .count(100).build());
            while (cursor.hasNext()) {
                String counterKey = cursor.next();
                try {
                    String val = stringRedisTemplate.opsForValue().get(counterKey);
                    if (val == null) continue;
                    long counterValue = Long.parseLong(val);
                    String[] parts = counterKey.split(":");
                    if (parts.length < 4) continue;
                    String targetId = parts[3];

                    // 读 analytics 权威 Set SCARD
                    String analyticsKey = RedisKeyConstants.LIKE_SET + typeName + ":" + targetId;
                    Long analyticsCount = stringRedisTemplate.opsForSet().size(analyticsKey);
                    if (analyticsCount != null && analyticsCount != counterValue) {
                        stringRedisTemplate.opsForValue().set(counterKey, String.valueOf(analyticsCount));
                        stringRedisTemplate.expire(counterKey, java.time.Duration.ofDays(30));
                        // T-113（2026-08-15）：analytics 权威修正后同步 DB——修复前只改 Redis，
                        // DB 残留漂移值（对账后 DB 与权威永久不一致）
                        try {
                            com.myxhs.counter.entity.Counter dbRow = counterMapper.selectByBusinessKey(
                                    targetType, Long.parseLong(targetId), 1);
                            if (dbRow != null) {
                                counterMapper.updateCountValue(dbRow.getId(), analyticsCount);
                                log.warn("[计数-对账] analytics权威修正同步DB: key={}, db={}→analytics={}",
                                        counterKey, dbRow.getCountValue(), analyticsCount);
                            }
                        } catch (Exception e) {
                            log.warn("[计数-对账] analytics修正同步DB失败(忽略): key={}, err={}",
                                    counterKey, e.getMessage());
                        }
                        log.warn("[计数-对账] analytics权威修正: key={}, counter={}→analytics={}",
                                counterKey, counterValue, analyticsCount);
                        fixed++;
                    }
                } catch (Exception e) {
                    log.warn("[计数-对账] 跳过无法解析的key: {}", counterKey);
                }
            }
            try { cursor.close(); } catch (Exception e) { /* ignore */ }
        }
        return fixed;
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
        stringRedisTemplate.expire(redisKey, java.time.Duration.ofDays(30));

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
                stringRedisTemplate.expire(redisKeys.get(i), java.time.Duration.ofDays(30));
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
     * <p>
     * 注意：以 DB 扫描为基准，DB 中无记录但 Redis 有值的情况不在本算法覆盖范围内——等 Buffer 刷盘自然解。
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
                        stringRedisTemplate.expire(redisKey, java.time.Duration.ofDays(30));
                        log.warn("[对账修复] Redis恢复: key={}, redis=0, db={}", redisKey, dbCount);
                    } else {
                        counterMapper.updateCountValue(dbCounter.getId(), redisCount);
                        log.warn("[对账修复] DB修正: key={}, redis={}, db={}", redisKey, redisCount, dbCount);
                    }
                    fixedCount++;
                }
            }
        } while (batch.size() == batchSize);

        // 从 analytics 权威 Set 修正 like 计数（counter 的 like:set 与 analytics 的 like:note 可能漂移）
        int likeFixed = reconcileLikeFromAnalytics();
        if (likeFixed > 0) {
            log.info("[对账修复] analytics权威修正: {} 条", likeFixed);
            fixedCount += likeFixed;
        }

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
