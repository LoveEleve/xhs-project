package com.myxhs.common.cache;

import com.myxhs.common.exception.RedisUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 操作封装
 * <p>
 * 对 RedisTemplate 的常用操作进行封装，提供类型安全的泛型方法。
 * </p>
 * <p>
 * 异常处理策略：
 * - Redis 连接不可用（RedisConnectionFailureException）→ 抛出 RedisUnavailableException，供上层降级
 * - 其他异常（序列化等）→ 记录日志，降级返回默认值
 * - Key 不存在 → 返回 null/false/0（正常业务语义，非异常）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOperator {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 判断是否为 Redis 连接不可用异常
     */
    private boolean isConnectionFailure(Throwable e) {
        return e instanceof RedisConnectionFailureException
                || e instanceof org.springframework.data.redis.RedisSystemException
                || e instanceof io.lettuce.core.RedisConnectionException
                // 2026-09-23 review：命令超时同样是"Redis 不可用"（类注释承诺连接不可用抛 RedisUnavailableException），
                // 原实现超时被当"其他异常"吞掉 → 调用方无法感知故障（写路径静默丢数据、降级逻辑不触发）
                || e instanceof io.lettuce.core.RedisCommandTimeoutException
                || (e.getCause() != null && isConnectionFailure(e.getCause()));
    }

    // ==================== String 操作 ====================

    /**
     * 设置值
     */
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis set 失败: key=" + key, e);
            }
            log.error("[Redis] set 失败, key={}", key, e);
        }
    }

    /**
     * 设置值并指定过期时间
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        try {
            redisTemplate.opsForValue().set(key, value, timeout, unit);
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis set 失败: key=" + key, e);
            }
            log.error("[Redis] set 失败, key={}, timeout={}{}", key, timeout, unit, e);
        }
    }

    /**
     * SET NX（不存在则设置，原子操作）
     *
     * @return true=设置成功（Key不存在），false=设置失败（Key已存在）
     * @throws RedisUnavailableException Redis 连接不可用
     */
    public boolean setIfAbsent(String key, Object value, long timeout, TimeUnit unit) {
        try {
            Boolean result = redisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis setIfAbsent 失败: key=" + key, e);
            }
            log.error("[Redis] setIfAbsent 失败, key={}", key, e);
            return false;
        }
    }

    /**
     * 获取值
     * <p>
     * 返回值语义：
     * - null = Key 不存在（缓存未命中，正常业务语义）
     * - Redis 连接不可用 → 抛出 RedisUnavailableException（供上层降级）
     * </p>
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        try {
            return (T) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis get 失败: key=" + key, e);
            }
            log.error("[Redis] get 失败, key={}", key, e);
            return null;
        }
    }

    /**
     * 获取 String 值
     * @throws RedisUnavailableException Redis 连接不可用
     */
    public String getString(String key) {
        try {
            String v = stringRedisTemplate.opsForValue().get(key);
            // T-019: 兼容 Jackson 序列化写入的带引号值（set 走 GenericJackson2JsonRedisSerializer）
            if (v != null && v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                v = v.substring(1, v.length() - 1);
            }
            return v;
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis getString 失败: key=" + key, e);
            }
            log.error("[Redis] getString 失败, key={}", key, e);
            return null;
        }
    }

    /**
     * 自增
     * @throws RedisUnavailableException Redis 连接不可用
     */
    public Long increment(String key) {
        try {
            return stringRedisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis increment 失败: key=" + key, e);
            }
            log.error("[Redis] increment 失败, key={}", key, e);
            return null;
        }
    }

    /**
     * 自增指定步长
     * @throws RedisUnavailableException Redis 连接不可用
     */
    public Long increment(String key, long delta) {
        try {
            return stringRedisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis increment 失败: key=" + key, e);
            }
            log.error("[Redis] increment 失败, key={}, delta={}", key, delta, e);
            return null;
        }
    }

    // ==================== Key 操作 ====================

    /**
     * 删除 Key
     * <p>
     * 返回值语义：
     * - true = 删除成功（Key 存在并被删除）
     * - false = Key 不存在（正常业务语义，非异常）
     * - Redis 连接不可用 → 抛出 RedisUnavailableException
     * </p>
     */
    public boolean delete(String key) {
        try {
            Boolean result = redisTemplate.delete(key);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis delete 失败: key=" + key, e);
            }
            log.error("[Redis] delete 失败, key={}", key, e);
            return false;
        }
    }

    /**
     * 批量删除 Key
     */
    public Long delete(Collection<String> keys) {
        try {
            return redisTemplate.delete(keys);
        } catch (Exception e) {
            if (isConnectionFailure(e)) {
                throw new RedisUnavailableException("Redis batch delete 失败: keys=" + keys, e);
            }
            log.error("[Redis] batch delete 失败, keys={}", keys, e);
            return 0L;
        }
    }

    /**
     * 判断 Key 是否存在
     */
    public boolean hasKey(String key) {
        try {
            Boolean result = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("[Redis] hasKey 失败, key={}", key, e);
            return false;
        }
    }

    /**
     * 设置过期时间
     */
    public boolean expire(String key, long timeout, TimeUnit unit) {
        try {
            Boolean result = redisTemplate.expire(key, timeout, unit);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("[Redis] expire 失败, key={}", key, e);
            return false;
        }
    }

    /**
     * 获取过期时间（秒）
     */
    public Long getExpire(String key) {
        try {
            return redisTemplate.getExpire(key, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[Redis] getExpire 失败, key={}", key, e);
            return -2L;
        }
    }

    // ==================== Hash 操作 ====================

    /**
     * Hash 设置值
     */
    public void hSet(String key, String field, Object value) {
        try {
            redisTemplate.opsForHash().put(key, field, value);
        } catch (Exception e) {
            log.error("[Redis] hSet 失败, key={}, field={}", key, field, e);
        }
    }

    /**
     * Hash 批量设置
     */
    public void hSetAll(String key, Map<String, Object> map) {
        try {
            redisTemplate.opsForHash().putAll(key, map);
        } catch (Exception e) {
            log.error("[Redis] hSetAll 失败, key={}", key, e);
        }
    }

    /**
     * Hash 获取值
     */
    @SuppressWarnings("unchecked")
    public <T> T hGet(String key, String field) {
        try {
            return (T) redisTemplate.opsForHash().get(key, field);
        } catch (Exception e) {
            log.error("[Redis] hGet 失败, key={}, field={}", key, field, e);
            return null;
        }
    }

    /**
     * Hash 获取所有字段
     */
    public Map<Object, Object> hGetAll(String key) {
        try {
            return redisTemplate.opsForHash().entries(key);
        } catch (Exception e) {
            log.error("[Redis] hGetAll 失败, key={}", key, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Hash 删除字段
     */
    public Long hDelete(String key, Object... fields) {
        try {
            return redisTemplate.opsForHash().delete(key, fields);
        } catch (Exception e) {
            log.error("[Redis] hDelete 失败, key={}", key, e);
            return 0L;
        }
    }

    /**
     * Hash 自增
     */
    public Long hIncrement(String key, String field, long delta) {
        try {
            return redisTemplate.opsForHash().increment(key, field, delta);
        } catch (Exception e) {
            log.error("[Redis] hIncrement 失败, key={}, field={}", key, field, e);
            return null;
        }
    }

    // ==================== Set 操作 ====================

    /**
     * Set 添加元素
     */
    public Long sAdd(String key, Object... values) {
        try {
            return redisTemplate.opsForSet().add(key, values);
        } catch (Exception e) {
            log.error("[Redis] sAdd 失败, key={}", key, e);
            return 0L;
        }
    }

    /**
     * Set 移除元素
     */
    public Long sRemove(String key, Object... values) {
        try {
            return redisTemplate.opsForSet().remove(key, values);
        } catch (Exception e) {
            log.error("[Redis] sRemove 失败, key={}", key, e);
            return 0L;
        }
    }

    /**
     * Set 判断元素是否存在
     */
    public boolean sIsMember(String key, Object value) {
        try {
            Boolean result = redisTemplate.opsForSet().isMember(key, value);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("[Redis] sIsMember 失败, key={}", key, e);
            return false;
        }
    }

    /**
     * Set 获取所有元素
     */
    public Set<Object> sMembers(String key) {
        try {
            return redisTemplate.opsForSet().members(key);
        } catch (Exception e) {
            log.error("[Redis] sMembers 失败, key={}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * Set 获取大小
     */
    public Long sSize(String key) {
        try {
            return redisTemplate.opsForSet().size(key);
        } catch (Exception e) {
            log.error("[Redis] sSize 失败, key={}", key, e);
            return 0L;
        }
    }

    // ==================== ZSet 操作 ====================

    /**
     * ZSet 添加元素
     */
    public boolean zAdd(String key, Object value, double score) {
        try {
            Boolean result = redisTemplate.opsForZSet().add(key, value, score);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.error("[Redis] zAdd 失败, key={}", key, e);
            return false;
        }
    }

    /**
     * ZSet 移除元素
     */
    public Long zRemove(String key, Object... values) {
        try {
            return redisTemplate.opsForZSet().remove(key, values);
        } catch (Exception e) {
            log.error("[Redis] zRemove 失败, key={}", key, e);
            return 0L;
        }
    }

    /**
     * ZSet 按分数范围查询
     */
    public Set<Object> zRangeByScore(String key, double min, double max) {
        try {
            return redisTemplate.opsForZSet().rangeByScore(key, min, max);
        } catch (Exception e) {
            log.error("[Redis] zRangeByScore 失败, key={}", key, e);
            return Collections.emptySet();
        }
    }

    /**
     * ZSet 按分数范围移除
     */
    public Long zRemoveRangeByScore(String key, double min, double max) {
        try {
            return redisTemplate.opsForZSet().removeRangeByScore(key, min, max);
        } catch (Exception e) {
            log.error("[Redis] zRemoveRangeByScore 失败, key={}", key, e);
            return 0L;
        }
    }

    /**
     * ZSet 获取大小
     */
    public Long zSize(String key) {
        try {
            return redisTemplate.opsForZSet().zCard(key);
        } catch (Exception e) {
            log.error("[Redis] zSize 失败, key={}", key, e);
            return 0L;
        }
    }

    // ==================== List 操作 ====================

    /**
     * List 左推入
     */
    public Long lLeftPush(String key, Object value) {
        try {
            return redisTemplate.opsForList().leftPush(key, value);
        } catch (Exception e) {
            log.error("[Redis] lLeftPush 失败, key={}", key, e);
            return 0L;
        }
    }

    /**
     * List 右推入
     */
    public Long lRightPush(String key, Object value) {
        try {
            return redisTemplate.opsForList().rightPush(key, value);
        } catch (Exception e) {
            log.error("[Redis] lRightPush 失败, key={}", key, e);
            return 0L;
        }
    }

    /**
     * List 范围查询
     */
    public List<Object> lRange(String key, long start, long end) {
        try {
            return redisTemplate.opsForList().range(key, start, end);
        } catch (Exception e) {
            log.error("[Redis] lRange 失败, key={}", key, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取底层 RedisTemplate（用于执行 Lua 脚本等高级操作）
     */
    public RedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }

    /**
     * 获取底层 StringRedisTemplate
     */
    public StringRedisTemplate getStringRedisTemplate() {
        return stringRedisTemplate;
    }
}
