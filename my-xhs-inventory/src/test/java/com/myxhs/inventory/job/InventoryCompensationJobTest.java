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
 * InventoryCompensationJob 单元测试（C-008：单键家族补偿）
 * <p>
 * 补偿按 (sku, orderId) 原子回退（原 F-037"来源桶读取"随分桶移除——单键下不存在扣错桶问题）。
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

    private InventoryCompensationJob job;

    @BeforeEach
    void setUp() throws InterruptedException {
        job = new InventoryCompensationJob(inventoryMapper, inventoryService, stringRedisTemplate, releaseScript, redissonClient,
                mock(com.myxhs.common.metrics.BusinessMetrics.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0L, 25, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    private Map<String, Object> compensationRow(Long id, Long orderId, Long skuId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("order_id", orderId);
        row.put("sku_id", skuId);
        return row;
    }

    @Test
    @DisplayName("C-008：补偿按 (sku, orderId) 单键原子回退（KEYS 同 {skuId} tag）")
    void shouldReleaseBySkuOrderKey() {
        Long orderId = 200L;
        Long skuId = 30001L;

        Map<String, Object> row = compensationRow(1L, orderId, skuId);
        when(inventoryMapper.selectPendingCompensation(any(LocalDateTime.class), eq(3), eq(50)))
                .thenReturn(List.of(row));
        when(stringRedisTemplate.execute(eq(releaseScript), anyList(), eq(String.valueOf(orderId))))
                .thenReturn(1L);

        assertThatCode(() -> job.retryCompensations()).doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(
                eq(releaseScript),
                argThat(keys -> keys.get(0).equals("inventory:{30001}:stock")
                        && keys.get(1).equals("inventory:{30001}:prededuct:200")
                        && keys.get(2).equals("inventory:{30001}:prededuct:index")),
                eq(String.valueOf(orderId)));
        verify(inventoryMapper).markCompensationResolved(1L);
    }

    @Test
    @DisplayName("C-008：release.lua 返回 0（记录不存在）时增加重试次数不标记完成")
    void shouldIncrementRetryWhenReleaseReturnsZero() {
        Long orderId = 202L;
        Long skuId = 30003L;

        Map<String, Object> row = compensationRow(3L, orderId, skuId);
        when(inventoryMapper.selectPendingCompensation(any(LocalDateTime.class), eq(3), eq(50)))
                .thenReturn(List.of(row));
        when(stringRedisTemplate.execute(eq(releaseScript), anyList(), eq(String.valueOf(orderId))))
                .thenReturn(0L);

        assertThatCode(() -> job.retryCompensations()).doesNotThrowAnyException();

        verify(inventoryMapper, never()).markCompensationResolved(anyLong());
        verify(inventoryMapper).incrementCompensationRetry(3L, 3);
    }
}
