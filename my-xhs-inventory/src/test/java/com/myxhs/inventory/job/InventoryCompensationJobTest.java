package com.myxhs.inventory.job;

import com.myxhs.inventory.mapper.InventoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * InventoryCompensationJob 单元测试
 * <p>
 * F-037：验证补偿任务从 prededuct hash 读取实际 bucket 号，
 * 而非固定写死 bucket 0。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryCompensationJobTest {

    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private DefaultRedisScript<Long> releaseScript;
    @Mock
    private RedissonClient redissonClient;
    /** 2026-09-21：补偿任务新增 type=2（退款回补）分流，构造需要 InventoryService */
    @Mock
    private com.myxhs.inventory.service.InventoryService inventoryService;
    @Mock
    private RLock lock;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private InventoryCompensationJob job;

    @BeforeEach
    void setUp() throws InterruptedException {
        job = new InventoryCompensationJob(inventoryMapper, inventoryService, stringRedisTemplate, releaseScript, redissonClient);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, 25, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compensationRow(Long id, Long orderId, Long skuId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("order_id", orderId);
        row.put("sku_id", skuId);
        return row;
    }

    @Test
    @DisplayName("F-037: 预扣 bucket=2 时补偿应恢复到 bucket 2 而非 bucket 0")
    void shouldReleaseToActualBucketNotBucketZero() {
        Long orderId = 200L;
        Long skuId = 30001L;

        Map<String, Object> row = compensationRow(1L, orderId, skuId);
        when(inventoryMapper.selectPendingCompensation(any(LocalDateTime.class), eq(3), eq(50)))
                .thenReturn(List.of(row));

        when(hashOperations.get(eq("inventory:prededuct:" + orderId), eq(skuId + ":bucket")))
                .thenReturn("2");
        when(stringRedisTemplate.execute(
                eq(releaseScript), anyList(), eq(String.valueOf(skuId)), eq(String.valueOf(orderId))))
                .thenReturn(1L);

        assertThatCode(() -> job.retryCompensations()).doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(
                eq(releaseScript),
                argThat(keys -> keys.get(2).equals("inventory:{30001}:bucket:2")),
                eq(String.valueOf(skuId)), eq(String.valueOf(orderId)));
        verify(inventoryMapper).markCompensationResolved(1L);
    }

    @Test
    @DisplayName("F-037: prededuct hash 无 bucket 字段时回退到 bucket 0（兼容旧数据）")
    void shouldFallbackToBucketZeroWhenBucketFieldMissing() {
        Long orderId = 201L;
        Long skuId = 30002L;

        Map<String, Object> row = compensationRow(2L, orderId, skuId);
        when(inventoryMapper.selectPendingCompensation(any(LocalDateTime.class), eq(3), eq(50)))
                .thenReturn(List.of(row));

        when(hashOperations.get(eq("inventory:prededuct:" + orderId), eq(skuId + ":bucket")))
                .thenReturn(null);
        when(stringRedisTemplate.execute(
                eq(releaseScript), anyList(), eq(String.valueOf(skuId)), eq(String.valueOf(orderId))))
                .thenReturn(1L);

        assertThatCode(() -> job.retryCompensations()).doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(
                eq(releaseScript),
                argThat(keys -> keys.get(2).equals("inventory:{30002}:bucket:0")),
                eq(String.valueOf(skuId)), eq(String.valueOf(orderId)));
        verify(inventoryMapper).markCompensationResolved(2L);
    }

    @Test
    @DisplayName("F-037: release.lua 返回 0 时增加重试次数不标记完成")
    void shouldIncrementRetryWhenReleaseReturnsZero() {
        Long orderId = 202L;
        Long skuId = 30003L;

        Map<String, Object> row = compensationRow(3L, orderId, skuId);
        when(inventoryMapper.selectPendingCompensation(any(LocalDateTime.class), eq(3), eq(50)))
                .thenReturn(List.of(row));

        when(hashOperations.get(eq("inventory:prededuct:" + orderId), eq(skuId + ":bucket")))
                .thenReturn("1");
        when(stringRedisTemplate.execute(
                eq(releaseScript), anyList(), eq(String.valueOf(skuId)), eq(String.valueOf(orderId))))
                .thenReturn(0L);

        assertThatCode(() -> job.retryCompensations()).doesNotThrowAnyException();

        verify(inventoryMapper, never()).markCompensationResolved(anyLong());
        verify(inventoryMapper).incrementCompensationRetry(3L, 3);
    }
}
