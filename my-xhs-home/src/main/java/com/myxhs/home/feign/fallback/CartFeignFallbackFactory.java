package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.CartFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
public class CartFeignFallbackFactory implements FallbackFactory<CartFeignClient> {
    @Override
    public CartFeignClient create(Throwable cause) {
        log.warn("[降级] CartFeignClient 不可用: {}", cause.getMessage());
        return new CartFeignClient() {
            @Override
            public R<java.util.Map<String, Object>> getCartList(Long userId) {
                return R.ok(Collections.emptyMap());
            }

            @Override
            public R<java.util.Map<String, Integer>> getCartCount(Long userId) {
                return R.ok(java.util.Map.of("count", 0));
            }
        };
    }
}
