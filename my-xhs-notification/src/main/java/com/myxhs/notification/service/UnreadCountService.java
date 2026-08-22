package com.myxhs.notification.service;

import com.myxhs.notification.dto.UnreadCountVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
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

    private static final String UNREAD_TOTAL_KEY = "myxhs:notification:unread:";
    private static final String UNREAD_TYPE_KEY = "myxhs:notification:unread:type:";

    /**
     * Lua 脚本：DECR 后不小于 0
     * <p>
     * 为什么用 Lua？
     * 并发标记已读时，多个 DECR 可能导致计数变为负数。
     * Lua 保证"读取 → 判断 → 修改"是原子的。
     * </p>
     */
    private static final String SAFE_DECR_SCRIPT =
            "local count = redis.call('GET', KEYS[1]) " +
                    "if count == false or tonumber(count) <= 0 then return 0 end " +
                    "return redis.call('DECR', KEYS[1])";

    private static final String SAFE_HDECR_SCRIPT =
            "local count = redis.call('HGET', KEYS[1], ARGV[1]) " +
                    "if count == false or tonumber(count) <= 0 then return 0 end " +
                    "return redis.call('HINCRBY', KEYS[1], ARGV[1], -1)";

    /**
     * Lua 脚本：原子 INCR total + HINCRBY type
     * 两个 Redis 操作在同一个 Lua 脚本中原子执行，避免中间状态不一致
     */
    private static final String ATOMIC_INCR_SCRIPT =
            "redis.call('INCR', KEYS[1]) " +
            "return redis.call('HINCRBY', KEYS[2], ARGV[1], 1)";

    private static final DefaultRedisScript<Long> ATOMIC_INCR_REDIS_SCRIPT =
            new DefaultRedisScript<>(ATOMIC_INCR_SCRIPT, Long.class);

    private static final DefaultRedisScript<Long> SAFE_DECR_REDIS_SCRIPT =
            new DefaultRedisScript<>(SAFE_DECR_SCRIPT, Long.class);

    private static final DefaultRedisScript<Long> SAFE_HDECR_REDIS_SCRIPT =
            new DefaultRedisScript<>(SAFE_HDECR_SCRIPT, Long.class);

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
                List.of(UNREAD_TOTAL_KEY + userId, UNREAD_TYPE_KEY + userId),
                String.valueOf(type));
    }

    /**
     * 减少未读计数（标记已读时调用）
     * <p>
     * 使用 Lua 脚本保证不会减为负数。
     * </p>
     */
    public void decrementUnread(Long userId, Integer type) {
        stringRedisTemplate.execute(SAFE_DECR_REDIS_SCRIPT, Collections.singletonList(UNREAD_TOTAL_KEY + userId));
        if (type != null) {
            stringRedisTemplate.execute(SAFE_HDECR_REDIS_SCRIPT,
                    Collections.singletonList(UNREAD_TYPE_KEY + userId),
                    String.valueOf(type));
        }
    }

    /**
     * 重置未读计数（全部标记已读时调用）
     */
    public void resetUnread(Long userId) {
        stringRedisTemplate.delete(UNREAD_TOTAL_KEY + userId);
        stringRedisTemplate.delete(UNREAD_TYPE_KEY + userId);
    }

    /**
     * 按类型重置未读计数（Lua 原子操作）
     */
    public void resetUnreadByType(Long userId, Integer type) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RESET_BY_TYPE_SCRIPT, Long.class);
        stringRedisTemplate.execute(script,
                java.util.List.of(UNREAD_TOTAL_KEY + userId, UNREAD_TYPE_KEY + userId),
                String.valueOf(type));
    }

    /**
     * 获取未读计数（总 + 分类）
     */
    public UnreadCountVO getUnreadCount(Long userId) {
        // 总未读
        String totalStr = stringRedisTemplate.opsForValue().get(UNREAD_TOTAL_KEY + userId);
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
                .entries(UNREAD_TYPE_KEY + userId);
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
        stringRedisTemplate.opsForValue().set(UNREAD_TOTAL_KEY + userId, String.valueOf(total));
        String typeKey = UNREAD_TYPE_KEY + userId;
        stringRedisTemplate.delete(typeKey);
        if (typeCountMap != null && !typeCountMap.isEmpty()) {
            Map<String, String> hashMap = new HashMap<>();
            typeCountMap.forEach((type, count) -> hashMap.put(String.valueOf(type), String.valueOf(count)));
            stringRedisTemplate.opsForHash().putAll(typeKey, hashMap);
        }
    }
}
