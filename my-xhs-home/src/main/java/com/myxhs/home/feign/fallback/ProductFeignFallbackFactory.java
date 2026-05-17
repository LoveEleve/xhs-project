package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.ProductFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
public class ProductFeignFallbackFactory implements FallbackFactory<ProductFeignClient> {
    @Override
    public ProductFeignClient create(Throwable cause) {
        log.warn("[降级] ProductFeignClient 不可用: {}", cause.getMessage());
        return new ProductFeignClient() {
            @Override
            public R<java.util.Map<String, Object>> getSpuDetail(Long spuId) {
                return R.ok(Collections.emptyMap());
            }

            @Override
            public R<java.util.Map<String, Object>> getSkuDetail(Long skuId) {
                return R.ok(Collections.emptyMap());
            }
        };
    }
}
