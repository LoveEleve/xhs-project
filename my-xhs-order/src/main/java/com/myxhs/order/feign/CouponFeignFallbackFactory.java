package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 优惠券服务降级工厂
 * <p>
 * 退券失败时记录日志，由定时任务补偿重试。
 * </p>
 */
@Slf4j
@Component
public class CouponFeignFallbackFactory implements FallbackFactory<CouponFeignClient> {
    @Override
    public CouponFeignClient create(Throwable cause) {
        log.error("[降级] CouponFeignClient 不可用，退券失败需补偿: {}", cause.getMessage());
        return (userId, request) -> R.fail(503, "优惠券服务不可用");
    }
}
