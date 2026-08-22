package com.myxhs.product.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.product.cache.RedisCacheData;
import com.myxhs.product.dto.request.SpuCreateRequest;
import com.myxhs.product.dto.request.SpuUpdateRequest;
import com.myxhs.product.dto.response.SkuVO;
import com.myxhs.product.dto.response.SpuDetailVO;
import com.myxhs.product.dto.response.SpuItemVO;
import com.myxhs.product.entity.Category;
import com.myxhs.product.entity.Sku;
import com.myxhs.product.entity.Spu;
import com.myxhs.product.enums.ProductStatus;
import com.myxhs.product.mapper.CategoryMapper;
import com.myxhs.product.mapper.ProductBehaviorMapper;
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
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SpuService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：SpuMapper, SkuMapper, CategoryMapper, RedisOperator, RedissonClient, IdGeneratorUtil
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SpuServiceTest {

    @Mock
    private SpuMapper spuMapper;
    @Mock
    private SkuMapper skuMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private RedisOperator redisOperator;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private IdGeneratorUtil idGeneratorUtil;
    @Mock
    private ProductBehaviorMapper productBehaviorMapper;
    @Mock
    private RBloomFilter<Long> spuBloomFilter;

    private SpuService spuService;

    private static final Long SPU_ID = 100001L;
    private static final Long CATEGORY_ID = 10L;

    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusTableInfo() {
        // LambdaUpdateWrapper 列名解析依赖 MyBatis-Plus TableInfo 缓存，
        // 纯 Mockito 测试无 Spring 上下文需手动初始化（否则 can not find lambda cache）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                Spu.class);
    }

    @BeforeEach
    void setUp() {
        spuService = new SpuService(
                spuMapper, skuMapper, categoryMapper, redisOperator, redissonClient, idGeneratorUtil, productBehaviorMapper);

        // 通过反射注入布隆过滤器（避免调用 @PostConstruct 初始化逻辑）
        ReflectionTestUtils.setField(spuService, "spuBloomFilter", spuBloomFilter);
        // 设置布隆过滤器就绪状态
        ReflectionTestUtils.setField(spuService, "bloomFilterReady", new AtomicBoolean(false));
    }

    // ==================== 创建 SPU ====================

    @Test
    @DisplayName("创建 SPU - 正常创建成功")
    void createSpuSuccess() {
        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> mockedTs =
                     mockStatic(org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            // 捕获注册的同步器并立即执行 afterCommit（模拟事务提交）
            mockedTs.when(() -> org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        org.springframework.transaction.support.TransactionSynchronization sync =
                                invocation.getArgument(0);
                        sync.afterCommit();
                        return null;
                    });

            SpuCreateRequest request = buildCreateRequest();

            Category category = new Category();
            category.setId(CATEGORY_ID);
            category.setName("测试分类");

            when(categoryMapper.selectById(CATEGORY_ID)).thenReturn(category);
            when(idGeneratorUtil.nextId()).thenReturn(SPU_ID);
            when(spuMapper.insert(any(Spu.class))).thenReturn(1);

            Long spuId = spuService.createSpu(request);

            assertThat(spuId).isEqualTo(SPU_ID);
            verify(spuMapper).insert(any(Spu.class));
            verify(spuBloomFilter).add(SPU_ID);
        }
    }

    // ==================== 查询 SPU 详情 ====================

    @Test
    @DisplayName("查询 SPU 详情 - 正常查询并返回名称和 SKU 列表")
    void getSpuDetailSuccess() {
        // 设置布隆过滤器就绪且 SPU ID 存在
        ReflectionTestUtils.setField(spuService, "bloomFilterReady", new AtomicBoolean(true));
        when(spuBloomFilter.contains(SPU_ID)).thenReturn(true);

        // 构造缓存中的详情数据
        SpuDetailVO detailVO = new SpuDetailVO();
        detailVO.setId(SPU_ID);
        detailVO.setName("测试商品");
        detailVO.setDescription("商品描述");
        detailVO.setCategoryId(CATEGORY_ID);
        detailVO.setCategoryName("测试分类");
        detailVO.setStatus(ProductStatus.ON_SHELF.getCode());
        detailVO.setCreatedAt(LocalDateTime.now());

        SkuVO skuVO = new SkuVO();
        skuVO.setId(2001L);
        skuVO.setSpuId(SPU_ID);
        skuVO.setName("红色-XL");
        skuVO.setPrice(new BigDecimal("99.00"));
        skuVO.setStatus(ProductStatus.ON_SHELF.getCode());
        detailVO.setSkuList(Collections.singletonList(skuVO));

        RedisCacheData<SpuDetailVO> cacheData = RedisCacheData.of(detailVO, 30);
        String redisKey = RedisKeyConstants.PRODUCT_SPU + SPU_ID;
        when(redisOperator.get(redisKey)).thenReturn(cacheData);

        SpuDetailVO result = spuService.getSpuDetail(SPU_ID);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("测试商品");
        assertThat(result.getSkuList()).hasSize(1);
        assertThat(result.getSkuList().get(0).getName()).isEqualTo("红色-XL");
    }

    @Test
    @DisplayName("查询 SPU 详情 - 布隆过滤器拦截不存在的 ID 返回 null")
    void spuNotFound() {
        // 设置布隆过滤器就绪但 SPU ID 不存在
        ReflectionTestUtils.setField(spuService, "bloomFilterReady", new AtomicBoolean(true));
        when(spuBloomFilter.contains(SPU_ID)).thenReturn(false);

        SpuDetailVO result = spuService.getSpuDetail(SPU_ID);

        assertThat(result).isNull();
        // 布隆过滤器拦截后不应该访问 Redis
        verify(redisOperator, never()).get(anyString());
        verify(spuMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("查询 SPU 详情 - 未拿到加载锁时优先等待缓存回填")
    void getSpuDetailWaitsForCacheRefillWhenLoadLockNotAcquired() throws Exception {
        ReflectionTestUtils.setField(spuService, "bloomFilterReady", new AtomicBoolean(false));

        org.redisson.api.RLock loadLock = mock(org.redisson.api.RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(loadLock);
        when(loadLock.tryLock(1, 10, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);

        RedisCacheData<SpuDetailVO> cacheMiss = null;
        SpuDetailVO detailVO = new SpuDetailVO();
        detailVO.setId(SPU_ID);
        detailVO.setName("回填成功");
        RedisCacheData<SpuDetailVO> cacheHit = RedisCacheData.of(detailVO, 30);
        when(redisOperator.get(RedisKeyConstants.PRODUCT_SPU + SPU_ID)).thenReturn(cacheMiss, cacheHit);

        SpuDetailVO result = spuService.getSpuDetail(SPU_ID);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("回填成功");
        verify(spuMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("查询 SPU 详情 - SPU下架时返回null并写空值缓存")
    void getSpuDetailReturnsNullWhenSpuOffShelf() throws Exception {
        ReflectionTestUtils.setField(spuService, "bloomFilterReady", new AtomicBoolean(false));

        org.redisson.api.RLock loadLock = mock(org.redisson.api.RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(loadLock);
        when(loadLock.tryLock(1, 10, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(loadLock.isHeldByCurrentThread()).thenReturn(true);

        when(redisOperator.get(RedisKeyConstants.PRODUCT_SPU + SPU_ID)).thenReturn(null, null);
        Spu offShelf = buildSpu();
        offShelf.setStatus(ProductStatus.OFF_SHELF.getCode());
        when(spuMapper.selectById(SPU_ID)).thenReturn(offShelf);

        SpuDetailVO result = spuService.getSpuDetail(SPU_ID);

        assertThat(result).isNull();
        verify(redisOperator).set(eq(RedisKeyConstants.PRODUCT_SPU + SPU_ID), any(RedisCacheData.class), eq(5L), eq(java.util.concurrent.TimeUnit.MINUTES));
        verify(loadLock).unlock();
    }

    // ==================== 更新 SPU ====================

    @Test
    @DisplayName("更新 SPU - 更新名称和描述成功")
    void updateSpuSuccess() {
        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> mockedTs =
                     mockStatic(org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            mockedTs.when(() -> org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(any()))
                    .thenAnswer(invocation -> null);

            Spu spu = buildSpu();
            when(spuMapper.selectById(SPU_ID)).thenReturn(spu);
            // 生产代码使用 LambdaUpdateWrapper：update(null, wrapper)
            when(spuMapper.update(org.mockito.ArgumentMatchers.isNull(),
                    any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
                    .thenReturn(1);

            SpuUpdateRequest request = new SpuUpdateRequest();
            request.setName("新名称");
            request.setDescription("新描述");

            spuService.updateSpu(SPU_ID, request);

            // 验证按字段更新被调用（LambdaUpdateWrapper 不修改实体对象，只生成 SQL）
            verify(spuMapper).update(org.mockito.ArgumentMatchers.isNull(),
                    any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class));
        }
    }

    // ==================== SPU 列表 ====================

    @Test
    @DisplayName("SPU 列表 - 分页查询返回正确数量")
    void listSpusSuccess() {
        Spu spu1 = buildSpu();
        spu1.setName("商品1");
        Spu spu2 = new Spu();
        spu2.setId(SPU_ID + 1);
        spu2.setName("商品2");
        spu2.setCategoryId(CATEGORY_ID);
        spu2.setStatus(ProductStatus.ON_SHELF.getCode());

        Page<Spu> page = new Page<>(1, 10);
        page.setRecords(Arrays.asList(spu1, spu2));
        page.setTotal(2);

        when(spuMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(page);

        var result = spuService.listSpus(1, 10, null);

        assertThat(result.getRecords()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(2);
        assertThat(result.getRecords().get(0).getName()).isEqualTo("商品1");
    }

    // ==================== 辅助方法 ====================

    private SpuCreateRequest buildCreateRequest() {
        SpuCreateRequest request = new SpuCreateRequest();
        request.setName("测试商品");
        request.setCategoryId(CATEGORY_ID);
        request.setBrandId(100L);
        request.setDescription("这是一个测试商品");
        request.setImages(Arrays.asList("https://img.example.com/1.jpg", "https://img.example.com/2.jpg"));
        return request;
    }

    private Spu buildSpu() {
        Spu spu = new Spu();
        spu.setId(SPU_ID);
        spu.setName("原始名称");
        spu.setCategoryId(CATEGORY_ID);
        spu.setBrandId(100L);
        spu.setDescription("原始描述");
        spu.setImages("[\"https://img.example.com/1.jpg\"]");
        spu.setStatus(ProductStatus.ON_SHELF.getCode());
        spu.setCreatedAt(LocalDateTime.now());
        return spu;
    }
}
