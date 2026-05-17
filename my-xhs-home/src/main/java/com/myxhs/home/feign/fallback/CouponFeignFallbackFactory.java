package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.CouponFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Slf4j
@Component
public class CouponFeignFallbackFactory implements FallbackFactory<CouponFeignClient> {
    @Override
    public CouponFeignClient create(Throwable cause) {
        log.warn("[降级] CouponFeignClient 不可用: {}", cause.getMessage());
        return userId -> R.ok(Collections.emptyList());
    }
}
