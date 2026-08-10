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

    private SkuService skuService;

    private static final Long SPU_ID = 10001L;
    private static final Long SKU_ID = 20001L;

    @BeforeEach
    void setUp() {
        skuService = new SkuService(skuMapper, spuMapper, spuService, idGeneratorUtil);
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
    }
}
