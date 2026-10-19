package com.myxhs.home.service;

import com.myxhs.common.response.R;
import com.myxhs.home.dto.ProductDetailAggVO;
import com.myxhs.home.exception.DownstreamUnavailableException;
import com.myxhs.home.feign.CounterFeignClient;
import com.myxhs.home.feign.InventoryFeignClient;
import com.myxhs.home.feign.ProductFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 商品详情聚合单测（C-014）：F1 池隔离 / 503 语义 / T-112 skuName 回归。
 */
class ProductAggServiceTest {

    private ProductFeignClient productFeignClient;
    private InventoryFeignClient inventoryFeignClient;
    private CounterFeignClient counterFeignClient;
    private ExecutorService batchFeignPool;
    private ProductAggService service;

    @BeforeEach
    void setUp() {
        productFeignClient = mock(ProductFeignClient.class);
        inventoryFeignClient = mock(InventoryFeignClient.class);
        counterFeignClient = mock(CounterFeignClient.class);
        batchFeignPool = spy(Executors.newFixedThreadPool(4));
        service = new ProductAggService(productFeignClient, inventoryFeignClient,
                counterFeignClient, batchFeignPool);
    }

    @Test
    @DisplayName("C-014 F1 + T-112：L1 池隔离；skuName/库存/计数正确映射")
    void normalAggregation() {
        when(productFeignClient.getSpuDetail(1L)).thenReturn(R.ok(Map.of(
                "name", "SPU-A",
                "skuList", List.of(Map.of("id", "1", "name", "SKU-A", "price", 9.9, "image", "img")))));
        when(counterFeignClient.batchGetCounts(any())).thenReturn(R.ok(Map.of(
                "4:1", Map.of("collect", 3L, "view", 100L))));
        when(inventoryFeignClient.getStock(1L)).thenReturn(R.ok(Map.of("availableStock", 5)));

        ProductDetailAggVO vo = service.getProductDetail(1L);

        assertThat(vo.getName()).isEqualTo("SPU-A");
        assertThat(vo.getSkuList()).hasSize(1);
        assertThat(vo.getSkuList().get(0).getSkuName()).isEqualTo("SKU-A"); // T-112 回归
        assertThat(vo.getSkuList().get(0).getAvailableStock()).isEqualTo(5);
        assertThat(vo.getSkuList().get(0).getHasStock()).isTrue();
        assertThat(vo.getCollectCount()).isEqualTo(3L);
        verify(batchFeignPool, atLeastOnce()).execute(any()); // F1
    }

    @Test
    @DisplayName("SPU 503 / 依赖异常 → DownstreamUnavailable（不伪装 404）")
    void unavailableSemantics() {
        when(productFeignClient.getSpuDetail(1L)).thenReturn(R.fail(503, "商品服务不可用"));
        assertThatThrownBy(() -> service.getProductDetail(1L))
                .isInstanceOf(DownstreamUnavailableException.class);

        when(productFeignClient.getSpuDetail(1L)).thenThrow(new RuntimeException("timeout"));
        assertThatThrownBy(() -> service.getProductDetail(1L))
                .isInstanceOf(DownstreamUnavailableException.class);
    }
}
