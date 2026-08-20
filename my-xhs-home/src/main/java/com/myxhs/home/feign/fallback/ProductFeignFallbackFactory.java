package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.home.feign.ProductFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProductFeignFallbackFactory implements FallbackFactory<ProductFeignClient> {
    @Override
    public ProductFeignClient create(Throwable cause) {
        log.warn("[降级] ProductFeignClient 不可用: {}", cause.getMessage());
        return new ProductFeignClient() {
            @Override
            public R<java.util.Map<String, Object>> getSpuDetail(Long spuId) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "商品服务不可用");
            }

            @Override
            public R<java.util.Map<String, Object>> getSkuDetail(Long skuId) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "商品服务不可用");
            }
        };
    }
}
