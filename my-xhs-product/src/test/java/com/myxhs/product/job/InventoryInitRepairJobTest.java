package com.myxhs.product.job;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.response.R;
import com.myxhs.product.entity.Sku;
import com.myxhs.product.feign.InventoryFeignClient;
import com.myxhs.product.mapper.SkuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存初始化失败补偿 Job 单测（C-005）
 * <p>
 * 覆盖：重试成功移除 / "已初始化"幂等移除 / SKU 不存在移除 / 仍失败保留 / 非法 ID 移除 / 空队列短路。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryInitRepairJobTest {

    private static final String KEY = RedisKeyConstants.PRODUCT_INVENTORY_INIT_PENDING;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private SetOperations<String, String> setOps;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SkuMapper skuMapper;
    @Mock
    private InventoryFeignClient inventoryFeignClient;

    private InventoryInitRepairJob job;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        job = new InventoryInitRepairJob(stringRedisTemplate, redissonClient, skuMapper, inventoryFeignClient);
    }

    private Sku sku(long id, int stock) {
        Sku sku = new Sku();
        sku.setId(id);
        sku.setStock(stock);
        return sku;
    }

    @Test
    @DisplayName("重试成功 → 从队列移除")
    void retrySuccessRemoves() {
        when(setOps.members(KEY)).thenReturn(Set.of("20001"));
        when(skuMapper.selectById(20001L)).thenReturn(sku(20001L, 100));
        when(inventoryFeignClient.initStock(any())).thenReturn(R.ok());

        int repaired = job.repair();

        assertThat(repaired).isEqualTo(1);
        verify(setOps).remove(KEY, "20001");
    }

    @Test
    @DisplayName("响应已初始化（幂等）→ 移除（不计入修复数）")
    void alreadyInitializedRemoves() {
        when(setOps.members(KEY)).thenReturn(Set.of("20001"));
        when(skuMapper.selectById(20001L)).thenReturn(sku(20001L, 100));
        when(inventoryFeignClient.initStock(any())).thenReturn(R.fail(500, "库存已初始化，请勿重复"));

        int repaired = job.repair();

        assertThat(repaired).isEqualTo(0);
        verify(setOps).remove(KEY, "20001");
    }

    @Test
    @DisplayName("SKU 不存在（已删/脏数据）→ 移除，不再重试、不调库存")
    void skuMissingRemoves() {
        when(setOps.members(KEY)).thenReturn(Set.of("20001"));
        when(skuMapper.selectById(20001L)).thenReturn(null);

        job.repair();

        verify(setOps).remove(KEY, "20001");
        verify(inventoryFeignClient, never()).initStock(any());
    }

    @Test
    @DisplayName("仍失败 → 保留待下轮")
    void stillFailingKeeps() {
        when(setOps.members(KEY)).thenReturn(Set.of("20001"));
        when(skuMapper.selectById(20001L)).thenReturn(sku(20001L, 100));
        when(inventoryFeignClient.initStock(any())).thenReturn(R.fail(500, "系统繁忙"));

        int repaired = job.repair();

        assertThat(repaired).isEqualTo(0);
        verify(setOps, never()).remove(eq(KEY), eq("20001"));
    }

    @Test
    @DisplayName("调用异常 → 保留待下轮（不炸整轮）")
    void callExceptionKeeps() {
        when(setOps.members(KEY)).thenReturn(Set.of("20001"));
        when(skuMapper.selectById(20001L)).thenReturn(sku(20001L, 100));
        when(inventoryFeignClient.initStock(any())).thenThrow(new RuntimeException("feign down"));

        int repaired = job.repair();

        assertThat(repaired).isEqualTo(0);
        verify(setOps, never()).remove(eq(KEY), eq("20001"));
    }

    @Test
    @DisplayName("非法 ID → 移除")
    void invalidIdRemoved() {
        when(setOps.members(KEY)).thenReturn(Set.of("not-a-number"));

        job.repair();

        verify(setOps).remove(KEY, "not-a-number");
        verify(skuMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("空队列 → 短路（零交互）")
    void emptyQueueShortCircuits() {
        when(setOps.members(KEY)).thenReturn(Set.of());

        assertThat(job.repair()).isEqualTo(0);
        verify(skuMapper, never()).selectById(anyLong());
    }
}
