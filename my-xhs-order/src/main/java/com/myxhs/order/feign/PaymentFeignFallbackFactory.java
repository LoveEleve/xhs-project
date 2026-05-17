package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 支付服务 Feign 降级工厂
 * <p>
 * 订单服务调用支付服务时的熔断降级策略：
 * 1. 发起支付（pay）：降级返回失败，提示用户稍后重试
 * 2. 查询支付状态（getPaymentStatus）：降级返回 null，订单侧显示"查询中"
 * 3. 发起退款（refund）：降级返回失败，记录到退款补偿表等待重试
 * <p>
 * 关键设计：
 * - 支付操作降级返回失败（非静默成功），避免用户以为支付已完成
 * - 查询状态降级返回空结果，前端可展示"查询中"状态，不影响用户体验
 * - 退款操作降级返回失败，需要补偿任务重新发起退款
 * <p>
 * 与 payment→order 方向的 FallbackFactory 设计思路对比：
 * - payment→order：所有降级返回失败（支付是强一致场景，必须触发补偿）
 * - order→payment：支付操作返回失败，查询操作返回空结果（订单是入口，可展示中间态）
 */
@Slf4j
@Component
public class PaymentFeignFallbackFactory implements FallbackFactory<PaymentFeignClient> {

    @Override
    public PaymentFeignClient create(Throwable cause) {
        log.error("[订单→支付] Feign调用降级: {}", cause.getMessage());

        return new PaymentFeignClient() {
            @Override
            public R<Object> pay(PaymentFeignClient.PayCreateRequest request, Long userId) {
                log.error("[订单→支付] 发起支付降级: orderId={}, userId={}, 提示用户稍后重试",
                        request.getOrderId(), userId);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "支付服务暂不可用，请稍后重试");
            }

            @Override
            public R<Object> getPaymentStatus(Long orderId) {
                log.warn("[订单→支付] 查询支付状态降级: orderId={}, 返回查询中状态", orderId);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "支付服务暂不可用，请稍后查询");
            }

            @Override
            public R<Void> refund(PaymentFeignClient.RefundCreateRequest request, Long userId) {
                log.error("[订单→支付] 发起退款降级: paymentId={}, userId={}, 需补偿重试",
                        request.getPaymentId(), userId);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "支付服务暂不可用，退款将稍后自动重试");
            }
        };
    }
}
