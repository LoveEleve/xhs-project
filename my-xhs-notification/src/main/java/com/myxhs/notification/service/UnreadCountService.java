package com.myxhs.notification.service;

import com.myxhs.notification.dto.UnreadCountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 未读计数服务
 * <p>
 * 核心设计：
 * 1. 总未读数：Redis String + INCR/DECR 原子操作
 * 2. 分类未读数：Redis Hash（field=type, value=count）
 * 3. DECR 防负数：Lua 脚本保证 DECR 后不小于 0
 * 4. 对账兜底：定时任务比较 Redis vs MySQL COUNT，以 DB 为准修复
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnreadCountService {

    private final StringRedisTemplate stringRedisTemplate;

    // 键加 hash tag（{u:userId}）：total 与 type 两键落同一 slot，
    // 使"双键 Lua"（ATOMIC_INCR / SAFE_DECR_BOTH）在 Redis Cluster 下不再 CROSSSLOT
    private static final String UNREAD_TOTAL_KEY = "myxhs:notification:unread:{u:";
    private static final String UNREAD_TYPE_KEY = "myxhs:notification:unread:type:{u:";

    private static String totalKey(Long userId) {
        return UNREAD_TOTAL_KEY + userId + "}";
    }

    private static String typeKey(Long userId) {
        return UNREAD_TYPE_KEY + userId + "}";
    }

    /**
     * Lua 脚本：原子 INCR total + HINCRBY type
     * 两个 Redis 操作在同一个 Lua 脚本中原子执行，避免中间状态不一致
     */
    private static final String ATOMIC_INCR_SCRIPT =
            "redis.call('INCR', KEYS[1]) " +
            "local v = redis.call('HINCRBY', KEYS[2], ARGV[1], 1) " +
            // 键无 TTL 会永久堆积（用户量×活跃度线性增长）；30 天不活跃即回收，
            // 活跃用户每次写入自动续期；若因过期被回收，10 分钟对账任务按 DB 重建（可收敛）
            "redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
            "redis.call('EXPIRE', KEYS[2], ARGV[2]) " +
            "return v";

    private static final DefaultRedisScript<Long> ATOMIC_INCR_REDIS_SCRIPT =
            new DefaultRedisScript<>(ATOMIC_INCR_SCRIPT, Long.class);

    /**
     * 总计数 + 分类计数一并安全递减（ARGV[1] 为空表示无分类）
     */
    private static final String SAFE_DECR_BOTH_SCRIPT =
            "local total = redis.call('GET', KEYS[1]) " +
                    "if total ~= false and tonumber(total) > 0 then redis.call('DECR', KEYS[1]) end " +
                    "if ARGV[1] ~= '' then " +
                    "  local c = redis.call('HGET', KEYS[2], ARGV[1]) " +
                    "  if c ~= false and tonumber(c) > 0 then redis.call('HINCRBY', KEYS[2], ARGV[1], -1) end " +
                    "end " +
                    "return 1";

    private static final DefaultRedisScript<Long> SAFE_DECR_BOTH_REDIS_SCRIPT =
            new DefaultRedisScript<>(SAFE_DECR_BOTH_SCRIPT, Long.class);

    /**
     * Lua 脚本：原子性按类型重置未读计数
     * <p>
     * 为什么用 Lua？
     * 原来的实现分三步：HGET → DECRBY → HSET，三步之间有并发窗口。
     * 如果两个线程同时执行 resetUnreadByType，可能导致总未读被多减。
     * Lua 保证"读取类型计数 → 减去总未读 → 归零类型计数"是原子的。
     * </p>
     */
    private static final String RESET_BY_TYPE_SCRIPT =
            "local typeCount = redis.call('HGET', KEYS[2], ARGV[1]) " +
                    "if typeCount == false or tonumber(typeCount) <= 0 then return 0 end " +
                    "local total = redis.call('GET', KEYS[1]) " +
                    "if total == false then total = '0' end " +
                    "local newTotal = tonumber(total) - tonumber(typeCount) " +
                    "if newTotal < 0 then newTotal = 0 end " +
                    "redis.call('SET', KEYS[1], tostring(newTotal)) " +
                    "redis.call('HSET', KEYS[2], ARGV[1], '0') " +
                    "return tonumber(typeCount)";

    /**
     * 增加未读计数（新通知到达时调用）
     */
    public void incrementUnread(Long userId, Integer type) {
        // 原子操作：total +1 + type hash +1（Lua 脚本保证同步）
        stringRedisTemplate.execute(ATOMIC_INCR_REDIS_SCRIPT,
                List.of(totalKey(userId), typeKey(userId)),
                String.valueOf(type),
                String.valueOf(java.time.Duration.ofDays(30).getSeconds()));
    }

    /**
     * 减少未读计数（标记已读时调用）
     * <p>
     * 使用 Lua 脚本保证不会减为负数。
     * </p>
     */
    public void decrementUnread(Long userId, Integer type) {
        // 总计数 + 分类计数一次原子递减（原实现两次独立脚本调用，中间可插入并发重置导致漂移）
        stringRedisTemplate.execute(SAFE_DECR_BOTH_REDIS_SCRIPT,
                List.of(totalKey(userId), typeKey(userId)),
                type != null ? String.valueOf(type) : "");
    }

    /**
     * 重置未读计数（全部标记已读时调用）
     */
    public void resetUnread(Long userId) {
        stringRedisTemplate.delete(totalKey(userId));
        stringRedisTemplate.delete(typeKey(userId));
    }

    /**
     * 按类型重置未读计数（Lua 原子操作）
     */
    public void resetUnreadByType(Long userId, Integer type) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RESET_BY_TYPE_SCRIPT, Long.class);
        stringRedisTemplate.execute(script,
                java.util.List.of(totalKey(userId), typeKey(userId)),
                String.valueOf(type));
    }

    /**
     * 获取未读计数（总 + 分类）
     */
    public UnreadCountVO getUnreadCount(Long userId) {
        // 总未读
        String totalStr = stringRedisTemplate.opsForValue().get(totalKey(userId));
        int total = 0;
        if (totalStr != null) {
            try {
                total = Math.max(0, Integer.parseInt(totalStr));
            } catch (NumberFormatException e) {
                log.warn("[未读数] Redis脏值: totalStr={}", totalStr, e);
            }
        }

        // 分类未读
        Map<Object, Object> entries = stringRedisTemplate.opsForHash()
                .entries(typeKey(userId));
        Map<Integer, Integer> details = new HashMap<>();
        entries.forEach((k, v) -> {
            try {
                int count = Math.max(0, Integer.parseInt(v.toString()));
                if (count > 0) {
                    details.put(Integer.parseInt(k.toString()), count);
                }
            } catch (NumberFormatException e) {
                log.warn("[未读数] Redis脏值: k={}, v={}", k, v);
            }
        });

        return UnreadCountVO.builder().total(total).details(details).build();
    }

    /**
     * 强制设置未读计数（对账修复时使用）
     */
    public void forceSetUnread(Long userId, int total, Map<Integer, Integer> typeCountMap) {
        stringRedisTemplate.opsForValue().set(totalKey(userId), String.valueOf(total));
        String typeHashKey = typeKey(userId);
        stringRedisTemplate.delete(typeHashKey);
        if (typeCountMap != null && !typeCountMap.isEmpty()) {
            Map<String, String> hashMap = new HashMap<>();
            typeCountMap.forEach((type, count) -> hashMap.put(String.valueOf(type), String.valueOf(count)));
            stringRedisTemplate.opsForHash().putAll(typeHashKey, hashMap);
        }
    }
}
