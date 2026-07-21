package com.myxhs.common.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CacheHelper 单元测试
 * <p>
 * 测试场景：
 * 1. 缓存命中 → 直接返回
 * 2. 缓存未命中 → 查 DB → 回填缓存
 * 3. 空值标记识别（防穿透）
 * 4. DB 未查到 → 缓存空值
 * 5. TTL 随机偏移范围验证
 * 6. 延迟双删执行验证
 * 7. deleteAfterUpdate 重试验证
 * 8. getWithCacheAsideLock 分布式锁验证
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class CacheHelperTest {

    @Mock
    private RedisOperator redisOperator;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    private CacheHelper cacheHelper;

    /** 空值占位符（与 CacheHelper 内部一致） */
    private static final String NULL_PLACEHOLDER = "\u0000__CACHE_NULL__\u0000";

    @BeforeEach
    void setUp() {
        cacheHelper = new CacheHelper(redisOperator, redissonClient);
    }

    @Test
    @DisplayName("缓存命中 - 直接返回缓存值，不查 DB")
    void testCacheHit() {
        when(redisOperator.get("user:info:1")).thenReturn("cached_user_data");

        Object result = cacheHelper.getWithCacheAside("user:info:1", () -> {
            throw new RuntimeException("不应该查 DB");
        }, 30, TimeUnit.MINUTES);

        assertThat(result).isEqualTo("cached_user_data");
        verify(redisOperator, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("缓存未命中 - 查 DB 并回填缓存")
    void testCacheMiss() {
        when(redisOperator.get("user:info:2")).thenReturn(null);

        Object result = cacheHelper.getWithCacheAside("user:info:2", () -> "db_user_data", 30, TimeUnit.MINUTES);

        assertThat(result).isEqualTo("db_user_data");

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(redisOperator).set(eq("user:info:2"), eq("db_user_data"), ttlCaptor.capture(), eq(TimeUnit.SECONDS));

        long ttl = ttlCaptor.getValue();
        long baseSeconds = 30 * 60;
        long maxOffset = baseSeconds / 6;
        assertThat(ttl).isBetween(baseSeconds, baseSeconds + maxOffset);
    }

    @Test
    @DisplayName("空值标记识别 - 返回 null 防穿透")
    void testNullPlaceholder() {
        when(redisOperator.get("user:info:999")).thenReturn(NULL_PLACEHOLDER);

        Object result = cacheHelper.getWithCacheAside("user:info:999", () -> {
            throw new RuntimeException("不应该查 DB");
        }, 30, TimeUnit.MINUTES);

        assertThat(result).isNull();
        verify(redisOperator, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("DB 未查到 - 缓存空值防穿透")
    void testDbNotFound() {
        when(redisOperator.get("user:info:404")).thenReturn(null);

        Object result = cacheHelper.getWithCacheAside("user:info:404", () -> null, 30, TimeUnit.MINUTES);

        assertThat(result).isNull();
        verify(redisOperator).set(eq("user:info:404"), eq(NULL_PLACEHOLDER), eq(2L), eq(TimeUnit.MINUTES));
    }

    @Test
    @DisplayName("延迟双删 - 第一次立即删除")
    void testDelayDoubleDelete() {
        cacheHelper.delayDoubleDelete("user:info:1");
        verify(redisOperator).delete("user:info:1");
    }

    @Test
    @DisplayName("延迟双删 - 第二次延迟删除")
    void testDelayDoubleDeleteSecondDelete() throws Exception {
        cacheHelper.delayDoubleDelete("user:info:1");
        Thread.sleep(800);
        verify(redisOperator, times(2)).delete("user:info:1");
    }

    @Test
    @DisplayName("deleteAfterUpdate - 删除成功")
    void testDeleteAfterUpdateSuccess() {
        when(redisOperator.delete("user:info:1")).thenReturn(true);

        boolean result = cacheHelper.deleteAfterUpdate("user:info:1");

        assertThat(result).isTrue();
        verify(redisOperator, times(1)).delete("user:info:1");
    }

    @Test
    @DisplayName("deleteAfterUpdate - Key不存在视为成功")
    void testDeleteAfterUpdateKeyNotExists() {
        // RedisOperator.delete() 返回 false 表示 Key 不存在（非异常）
        // CacheHelper.deleteAfterUpdate 将第一次 false 视为"Key不存在=成功"
        when(redisOperator.delete("user:info:1")).thenReturn(false);

        boolean result = cacheHelper.deleteAfterUpdate("user:info:1");

        // 第一次 false → 视为 Key 不存在 → 成功
        assertThat(result).isTrue();
        verify(redisOperator, times(1)).delete("user:info:1");
    }

    @Test
    @DisplayName("deleteAfterUpdate - 多个Key部分已删除")
    void testDeleteAfterUpdateMultipleKeysAllSucceed() {
        // key1 → true（删除成功）, key2 → false（首次=Key不存在=成功）
        when(redisOperator.delete("key1")).thenReturn(true);
        when(redisOperator.delete("key2")).thenReturn(false);

        boolean result = cacheHelper.deleteAfterUpdate("key1", "key2");

        // 两个都成功：key1 删除成功, key2 Key不存在视为成功
        assertThat(result).isTrue();
        verify(redisOperator, times(1)).delete("key1");
        verify(redisOperator, times(1)).delete("key2");
    }

    @Test
    @DisplayName("getWithCacheAsideLock - 缓存命中直接返回")
    void testCacheAsideLockHit() {
        when(redisOperator.get("hot:key")).thenReturn("cached_data");

        Object result = cacheHelper.getWithCacheAsideLock("hot:key", () -> {
            throw new RuntimeException("不应该查 DB");
        }, 30, TimeUnit.MINUTES);

        assertThat(result).isEqualTo("cached_data");
        verifyNoInteractions(redissonClient);
    }

    @Test
    @DisplayName("getWithCacheAsideLock - 获取锁成功查DB回填")
    void testCacheAsideLockAcquired() throws Exception {
        when(redisOperator.get("hot:key")).thenReturn(null);
        when(redissonClient.getLock("lock:cache:hot:key")).thenReturn(rLock);
        when(rLock.tryLock(3, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        Object result = cacheHelper.getWithCacheAsideLock("hot:key", () -> "db_data", 30, TimeUnit.MINUTES);

        assertThat(result).isEqualTo("db_data");
        verify(rLock).unlock();
    }

    @Test
    @DisplayName("默认 TTL - 使用 30 分钟")
    void testDefaultTtl() {
        when(redisOperator.get("test:key")).thenReturn(null);

        cacheHelper.getWithCacheAside("test:key", () -> "data");

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(redisOperator).set(eq("test:key"), eq("data"), ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        assertThat(ttlCaptor.getValue()).isBetween(1800L, 2100L);
    }
}
