package com.myxhs.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.product.dto.request.SkuCreateRequest;
import com.myxhs.product.dto.response.SkuVO;
import com.myxhs.product.entity.Sku;
import com.myxhs.product.entity.Spu;
import com.myxhs.product.mapper.SkuMapper;
import com.myxhs.product.mapper.SpuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkuServiceTest {

    @Mock
    private SkuMapper skuMapper;
    @Mock
    private SpuMapper spuMapper;
    @Mock
    private SpuService spuService;
    @Mock
    private IdGeneratorUtil idGeneratorUtil;
    @Mock
    private com.myxhs.product.feign.InventoryFeignClient inventoryFeignClient;
    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    @Mock
    private org.springframework.data.redis.core.SetOperations<String, String> setOps;

    private SkuService skuService;
    private ObjectMapper objectMapper;
    /** 用真实 SimpleMeterRegistry（Mock 的 registry.counter() 返回 null 会导致打点 NPE） */
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private static final Long SPU_ID = 10001L;
    private static final Long SKU_ID = 20001L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        skuService = new SkuService(skuMapper, spuMapper, spuService, idGeneratorUtil, objectMapper,
                inventoryFeignClient, meterRegistry, stringRedisTemplate);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        // 新建 SKU 后提交回调会触发库存初始化（远程调用）
        when(inventoryFeignClient.initStock(any())).thenReturn(com.myxhs.common.response.R.ok());
    }

    @Test
    @DisplayName("创建SKU成功")
    void createSkuSuccess() {
        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> mockedTs =
                     org.mockito.Mockito.mockStatic(
                             org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            // 捕获注册的同步器并立即执行 afterCommit（模拟事务提交触发缓存清除）
            mockedTs.when(() -> org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        org.springframework.transaction.support.TransactionSynchronization sync =
                                invocation.getArgument(0);
                        sync.afterCommit();
                        return null;
                    });

            Spu spu = new Spu();
            spu.setId(SPU_ID);
            spu.setName("测试SPU");
            spu.setStatus(1);
            when(spuMapper.selectById(SPU_ID)).thenReturn(spu);
            when(idGeneratorUtil.nextId()).thenReturn(SKU_ID);
            when(skuMapper.insert(any(Sku.class))).thenReturn(1);
            doNothing().when(spuService).evictSpuCache(SPU_ID);

            SkuCreateRequest request = new SkuCreateRequest();
            request.setSpuId(SPU_ID);
            request.setName("测试SKU");
            request.setPrice(new BigDecimal("99.00"));
            request.setOriginalPrice(new BigDecimal("199.00"));
            request.setStock(100);
            request.setSpecs("{\"颜色\":\"红色\"}");

            Long skuId = skuService.createSku(request);

            assertThat(skuId).isEqualTo(SKU_ID);
            verify(skuMapper).insert(any(Sku.class));
            verify(spuService).evictSpuCache(SPU_ID);
        }
    }

    @Test
    @DisplayName("C-005：价格负数 → 拒绝（服务层纵深；0 元单合法）")
    void createSkuRejectsNegativePrice() {
        Spu spu = new Spu();
        spu.setId(SPU_ID);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu);

        com.myxhs.product.dto.request.SkuCreateRequest request =
                new com.myxhs.product.dto.request.SkuCreateRequest();
        request.setSpuId(SPU_ID);
        request.setName("测试SKU");
        request.setPrice(new java.math.BigDecimal("-1"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> skuService.createSku(request))
                .isInstanceOf(com.myxhs.common.exception.BizException.class)
                .hasMessageContaining("价格不能为负数");
        verify(skuMapper, never()).insert(any(Sku.class));
    }

    @Test
    @DisplayName("C-005：价格 0 合法（零元单场景；仅拒绝负数）")
    void createSkuAllowsZeroPrice() {
        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> mockedTs =
                     org.mockito.Mockito.mockStatic(
                             org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            mockedTs.when(() -> org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        org.springframework.transaction.support.TransactionSynchronization sync =
                                invocation.getArgument(0);
                        sync.afterCommit();
                        return null;
                    });
            Spu spu = new Spu();
            spu.setId(SPU_ID);
            when(spuMapper.selectById(SPU_ID)).thenReturn(spu);
            when(idGeneratorUtil.nextId()).thenReturn(SKU_ID);
            when(skuMapper.insert(any(Sku.class))).thenReturn(1);

            com.myxhs.product.dto.request.SkuCreateRequest request =
                    new com.myxhs.product.dto.request.SkuCreateRequest();
            request.setSpuId(SPU_ID);
            request.setName("零元单SKU");
            request.setPrice(java.math.BigDecimal.ZERO);

            assertThat(skuService.createSku(request)).isEqualTo(SKU_ID);
        }
    }

    @Test
    @DisplayName("C-005：库存初始化失败 → 入补偿队列（pending set）")
    void createSkuInventoryFailureRecordsPending() {
        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> mockedTs =
                     org.mockito.Mockito.mockStatic(
                             org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            mockedTs.when(() -> org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        org.springframework.transaction.support.TransactionSynchronization sync =
                                invocation.getArgument(0);
                        sync.afterCommit();
                        return null;
                    });

            Spu spu = new Spu();
            spu.setId(SPU_ID);
            when(spuMapper.selectById(SPU_ID)).thenReturn(spu);
            when(idGeneratorUtil.nextId()).thenReturn(SKU_ID);
            when(skuMapper.insert(any(Sku.class))).thenReturn(1);
            when(inventoryFeignClient.initStock(any()))
                    .thenReturn(com.myxhs.common.response.R.fail(500, "库存服务不可用"));

            com.myxhs.product.dto.request.SkuCreateRequest request =
                    new com.myxhs.product.dto.request.SkuCreateRequest();
            request.setSpuId(SPU_ID);
            request.setName("测试SKU");
            request.setPrice(new java.math.BigDecimal("9.9"));

            skuService.createSku(request);

            verify(setOps).add(com.myxhs.common.constants.RedisKeyConstants.PRODUCT_INVENTORY_INIT_PENDING,
                    String.valueOf(SKU_ID));
        }
    }

    @Test
    @DisplayName("获取SKU详情成功")
    void getSkuDetailSuccess() {
        Sku sku = new Sku();
        sku.setId(SKU_ID);
        sku.setSpuId(SPU_ID);
        sku.setName("测试SKU");
        sku.setPrice(new BigDecimal("99.00"));
        sku.setOriginalPrice(new BigDecimal("199.00"));
        sku.setStock(100);
        sku.setSpecs("{\"颜色\":\"红色\"}");
        sku.setStatus(1);
        Spu spu = new Spu();
        spu.setId(SPU_ID);
        spu.setStatus(1);
        spu.setImages("[\"img1\"]");
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu);
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku);

        SkuVO vo = skuService.getSkuDetail(SKU_ID);

        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(SKU_ID);
        assertThat(vo.getName()).isEqualTo("测试SKU");
        assertThat(vo.getPrice()).isEqualByComparingTo("99.00");
    }

    @Test
    @DisplayName("按SPU查询SKU列表成功")
    void getSkuListBySpuIdSuccess() {
        Spu spu = new Spu();
        spu.setId(SPU_ID);
        spu.setStatus(1);
        spu.setImages("[\"img1\"]");
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu);
        when(spuMapper.selectBatchIds(any())).thenReturn(List.of(spu));

        Sku sku1 = new Sku();
        sku1.setId(20001L);
        sku1.setSpuId(SPU_ID);
        sku1.setName("SKU-1");
        sku1.setPrice(new BigDecimal("99.00"));
        sku1.setStock(50);
        sku1.setStatus(1);

        Sku sku2 = new Sku();
        sku2.setId(20002L);
        sku2.setSpuId(SPU_ID);
        sku2.setName("SKU-2");
        sku2.setPrice(new BigDecimal("129.00"));
        sku2.setStock(30);
        sku2.setStatus(1);

        when(skuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Arrays.asList(sku1, sku2));

        List<SkuVO> list = skuService.listSkusBySpuId(SPU_ID);

        assertThat(list).hasSize(2);
        assertThat(list.get(0).getName()).isEqualTo("SKU-1");
        assertThat(list.get(1).getName()).isEqualTo("SKU-2");
        assertThat(list.get(0).getSpuStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("SPU下架时不返回SKU列表")
    void getSkuListBySpuIdReturnsEmptyWhenSpuOffShelf() {
        Spu spu = new Spu();
        spu.setId(SPU_ID);
        spu.setStatus(0);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu);

        List<SkuVO> list = skuService.listSkusBySpuId(SPU_ID);

        assertThat(list).isEmpty();
        verify(skuMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("C-005：getSkuDetail 复用已查 SPU（单查，无重复 selectById）")
    void getSkuDetailQueriesSpuOnce() {
        Sku sku = new Sku();
        sku.setId(SKU_ID);
        sku.setSpuId(SPU_ID);
        sku.setStatus(1);
        Spu spu = new Spu();
        spu.setId(SPU_ID);
        spu.setStatus(1);
        spu.setImages("[\"img1\"]");
        when(skuMapper.selectById(SKU_ID)).thenReturn(sku);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu);

        SkuVO vo = skuService.getSkuDetail(SKU_ID);

        assertThat(vo.getImage()).isEqualTo("img1");
        verify(spuMapper, times(1)).selectById(SPU_ID);
    }

    @Test
    @DisplayName("C-005：listSkusBySpuId 复用已查 SPU（不再 selectBatchIds 重复查）")
    void listSkusBySpuIdQueriesSpuOnce() {
        Spu spu = new Spu();
        spu.setId(SPU_ID);
        spu.setStatus(1);
        when(spuMapper.selectById(SPU_ID)).thenReturn(spu);
        Sku sku = new Sku();
        sku.setId(SKU_ID);
        sku.setSpuId(SPU_ID);
        sku.setStatus(1);
        when(skuMapper.selectList(any())).thenReturn(java.util.List.of(sku));

        java.util.List<SkuVO> list = skuService.listSkusBySpuId(SPU_ID);

        assertThat(list).hasSize(1);
        verify(spuMapper, times(1)).selectById(SPU_ID);
        verify(spuMapper, never()).selectBatchIds(any());
    }
}
