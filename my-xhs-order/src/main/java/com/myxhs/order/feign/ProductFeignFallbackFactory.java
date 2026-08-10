package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import com.myxhs.order.dto.SkuInfoDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 商品服务降级工厂
 * <p>
 * 商品服务不可用时返回空列表，调用方（OrderTransactionService）
 * 收到空结果应拒绝创建订单，不继续使用 mock 数据。
 * </p>
 */
@Slf4j
@Component
public class ProductFeignFallbackFactory implements FallbackFactory<ProductFeignClient> {
    @Override
    public ProductFeignClient create(Throwable cause) {
        log.error("[降级] ProductFeignClient 不可用: {}", cause.getMessage());
        return skuIds -> {
            log.error("[降级] 批量查 SKU 失败, skuIds={}", skuIds);
            return R.fail(503, "商品服务不可用，无法创建订单");
        };
    }
}
