package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.OrderFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
public class OrderFeignFallbackFactory implements FallbackFactory<OrderFeignClient> {
    @Override
    public OrderFeignClient create(Throwable cause) {
        log.warn("[降级] OrderFeignClient 不可用: {}", cause.getMessage());
        return new OrderFeignClient() {
            @Override
            public R<java.util.Map<String, Object>> getOrderDetail(Long userId, Long orderId) {
                return R.ok(Collections.emptyMap());
            }

            @Override
            public R<java.util.List<java.util.Map<String, Object>>> getUserOrders(Long userId, Integer status) {
                return R.ok(Collections.emptyList());
            }
        };
    }
}
