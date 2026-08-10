package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
        log.error("[降级] CouponFeignClient 不可用: {}", cause.getMessage());
        return new CouponFeignClient() {
            @Override
            public R<java.math.BigDecimal> getCouponDiscount(Long userId, Long userCouponId, java.math.BigDecimal orderAmount) {
                log.error("[降级] 查询券折扣失败");
                return R.fail(503, "优惠券服务不可用");
            }

            @Override
            public R<java.math.BigDecimal> useCoupon(Long userId, Map<String, Object> request) {
                log.error("[降级] 核销优惠券失败");
                return R.fail(503, "优惠券服务不可用");
            }

            @Override
            public R<Void> returnCoupon(Long userId, Map<String, Object> request) {
                log.error("[降级] 退还优惠券失败");
                return R.fail(503, "优惠券服务不可用");
            }
        };
    }
}
