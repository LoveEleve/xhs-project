package com.myxhs.cart.feign;

import com.myxhs.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品服务 Feign 降级工厂
 * <p>
 * 商品服务不可用时的降级处理：
 * - SKU 查询降级：返回 null（购物车列表中标记为"商品信息获取失败"）
 * - 不影响购物车核心操作（加购/删购/改数量仅操作 Redis，不依赖商品服务）
 * </p>
 */
@Slf4j
@Component
public class ProductFeignFallbackFactory implements FallbackFactory<ProductFeignClient> {

    @Override
    public ProductFeignClient create(Throwable cause) {
        log.error("[Feign降级] 商品服务调用失败: {}", cause.getMessage());
        return new ProductFeignClient() {
            @Override
            public R<SkuDTO> getSkuDetail(Long skuId) {
                log.warn("[Feign降级] getSkuDetail 降级, skuId={}", skuId);
                return R.fail("商品服务暂不可用");
            }

            @Override
            public R<List<SkuDTO>> batchGetSkuDetails(List<Long> skuIds) {
                log.warn("[Feign降级] batchGetSkuDetails 降级, skuIds={}", skuIds);
                return R.fail("商品服务暂不可用");
            }
        };
    }
}
