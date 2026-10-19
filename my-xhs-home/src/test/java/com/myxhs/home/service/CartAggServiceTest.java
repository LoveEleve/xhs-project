package com.myxhs.home.service;

import com.myxhs.common.response.R;
import com.myxhs.home.dto.CartAggVO;
import com.myxhs.home.exception.DownstreamUnavailableException;
import com.myxhs.home.feign.CartFeignClient;
import com.myxhs.home.feign.CouponFeignClient;
import com.myxhs.home.feign.InventoryFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 * 购物车聚合单测（C-014）：F1 池隔离 / F3 超时→503（不伪装空车）/ C-14 字段回归。
 */
class CartAggServiceTest {

    private CartFeignClient cartFeignClient;
    private InventoryFeignClient inventoryFeignClient;
    private CouponFeignClient couponFeignClient;
    private ExecutorService batchFeignPool;
    private CartAggService service;

    @BeforeEach
    void setUp() {
        cartFeignClient = mock(CartFeignClient.class);
        inventoryFeignClient = mock(InventoryFeignClient.class);
        couponFeignClient = mock(CouponFeignClient.class);
        batchFeignPool = spy(Executors.newFixedThreadPool(4));
        service = new CartAggService(cartFeignClient, inventoryFeignClient, couponFeignClient, batchFeignPool);
    }

    @Test
    @DisplayName("C-014 F3：购物车列表超时（future 未完成）→ 503，不再伪装空购物车")
    void cartTimeoutDegradesTo503() {
        when(cartFeignClient.getCartList(1L)).thenAnswer(inv -> {
            Thread.sleep(5000); // 下游挂起（L1 超时 3s 先触发）
            return R.ok(Collections.emptyMap());
        });

        assertThatThrownBy(() -> service.getCartAgg(1L))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasMessageContaining("购物车服务不可用");
    }

    @Test
    @DisplayName("购物车 503-code → DownstreamUnavailable")
    void cart503() {
        when(cartFeignClient.getCartList(1L)).thenReturn(R.fail(503, "购物车服务不可用"));

        assertThatThrownBy(() -> service.getCartAgg(1L))
                .isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    @DisplayName("C-014 F1 + C-14 回归：字段映射（name/image）+ 库存判定 + 汇总计算")
    void normalAggregation() {
        when(cartFeignClient.getCartList(1L)).thenReturn(R.ok(Map.of("items", List.of(
                Map.of("skuId", "1", "spuId", "2", "name", "商品X", "image", "img",
                        "price", "9.90", "quantity", 2, "checked", true)))));
        when(couponFeignClient.getAvailableCoupons(1L)).thenReturn(R.ok(List.of(Map.of("id", 1))));
        when(inventoryFeignClient.getStock(1L)).thenReturn(R.ok(Map.of("availableStock", 5)));

        CartAggVO vo = service.getCartAgg(1L);

        assertThat(vo.getItems()).hasSize(1);
        CartAggVO.CartItemAggVO item = vo.getItems().get(0);
        assertThat(item.getSkuName()).isEqualTo("商品X"); // C-14 回归
        assertThat(item.getSkuImage()).isEqualTo("img");
        assertThat(item.getHasStock()).isTrue();
        assertThat(item.getTotalAmount()).isEqualByComparingTo(new BigDecimal("19.80"));
        assertThat(vo.getCheckedCount()).isEqualTo(2);
        assertThat(vo.getAllChecked()).isTrue();
        assertThat(vo.getAvailableCouponCount()).isEqualTo(1);
        verify(batchFeignPool, atLeastOnce()).execute(any()); // F1
    }

    @Test
    @DisplayName("空购物车：保留优惠券信息")
    void emptyCart() {
        when(cartFeignClient.getCartList(1L)).thenReturn(R.ok(Map.of("items", Collections.emptyList())));
        when(couponFeignClient.getAvailableCoupons(1L)).thenReturn(R.ok(List.of()));

        CartAggVO vo = service.getCartAgg(1L);

        assertThat(vo.getItems()).isEmpty();
        assertThat(vo.getAvailableCouponCount()).isZero();
    }
}
