package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.home.feign.CartFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CartFeignFallbackFactory implements FallbackFactory<CartFeignClient> {
    @Override
    public CartFeignClient create(Throwable cause) {
        log.warn("[降级] CartFeignClient 不可用: {}", cause.getMessage());
        return new CartFeignClient() {
            @Override
            public R<java.util.Map<String, Object>> getCartList(Long userId) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "购物车服务不可用");
            }

            @Override
            public R<java.util.Map<String, Integer>> getCartCount(Long userId) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "购物车服务不可用");
            }
        };
    }
}
