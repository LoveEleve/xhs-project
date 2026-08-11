package com.myxhs.payment.feign;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 订单服务 Feign 降级工厂
 * <p>
 * 支付服务调用订单服务时的熔断降级策略：
 * 1. 支付成功通知（notifyPaySuccess）：降级返回失败，触发支付侧补偿任务重新通知
 * 2. 支付失败通知（notifyPayFail）：降级返回失败，订单超时会自动关闭
 * 3. 退款成功通知（notifyRefundSuccess）：降级返回失败，触发退款补偿任务重新通知
 * 4. 退款失败通知（notifyRefundFail）：降级返回失败，记录日志人工处理
 * 5. 查询支付金额（getOrderPayAmount）：降级返回 null，支付侧拒绝支付
 * </p>
 * <p>
 * 关键设计：所有降级方法都返回失败（R.fail），而不是静默成功。
 * 原因：支付和订单是强一致场景，降级后必须触发补偿任务，不能静默丢弃。
 * 如果返回成功，调用方认为操作已完成，不会触发补偿，导致数据不一致。
 * </p>
 * <p>
 * 补偿机制：
 * - 支付成功通知失败 → PaymentCompensateJob 定时重试
 * - 退款成功通知失败 → RefundCompensateJob 定时重试
 * - 订单超时 → OrderCloseJob 自动关闭
 * </p>
 */
@Slf4j
@Component
public class OrderFeignFallbackFactory implements FallbackFactory<OrderFeignClient> {

    @Override
    public OrderFeignClient create(Throwable cause) {
        log.error("[支付→订单] Feign调用降级: {}", cause.getMessage());

        return new OrderFeignClient() {
            @Override
            public R<Void> notifyPaySuccess(Long orderId, String tradeNo) {
                log.error("[支付→订单] 支付成功通知降级: orderId={}, tradeNo={}, 触发补偿任务", orderId, tradeNo);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "订单服务不可用，等待补偿任务重试");
            }

            @Override
            public R<Void> notifyPayFail(Long orderId) {
                log.error("[支付→订单] 支付失败通知降级: orderId={}, 订单将自动超时关闭", orderId);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "订单服务不可用，订单将自动超时关闭");
            }

            @Override
            public R<Void> notifyRefundSuccess(Long orderId, String refundNo) {
                log.error("[支付→订单] 退款成功通知降级: orderId={}, refundNo={}, 触发补偿任务", orderId, refundNo);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "订单服务不可用，等待补偿任务重试");
            }

            @Override
            public R<Void> notifyRefundFail(Long orderId, String refundNo) {
                log.error("[支付→订单] 退款失败通知降级: orderId={}, refundNo={}, 需人工处理", orderId, refundNo);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "订单服务不可用，需人工处理");
            }

            @Override
            public R<BigDecimal> getOrderPayAmount(Long orderId) {
                log.error("[支付→订单] 查询支付金额降级: orderId={}, 拒绝支付", orderId);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "订单服务不可用，无法验证支付金额");
            }

            @Override
            public R<Integer> getOrderStatus(Long orderId) {
                log.error("[支付→订单] 查询订单状态降级: orderId={}, 拒绝支付", orderId);
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, "订单服务不可用，无法校验订单状态");
            }
        };
    }
}
