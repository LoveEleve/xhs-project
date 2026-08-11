package com.myxhs.payment.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 订单服务 Feign 客户端
 * <p>
 * 支付服务通过 Feign 远程调用订单服务，实现：
 * 1. 支付成功后通知订单服务更新订单状态
 * 2. 退款成功后通知订单服务恢复库存+退优惠券
 * </p>
 * <p>
 * Feign 集成说明：
 * - 熔断降级：可选配置 fallback/fallbackFactory
 * - 超时配置：通过 feign.client.config.default.connect-timeout/read-timeout
 * - 重试：默认不重试（幂等接口才适合重试）
 * </p>
 */
@FeignClient(name = "my-xhs-order",
        fallbackFactory = OrderFeignFallbackFactory.class,
        configuration = InternalCallFeignConfig.class)
public interface OrderFeignClient {

    /**
     * 支付成功通知
     * <p>
     * 支付服务在支付成功后调用此接口，通知订单服务更新订单状态为"已支付"。
     * 订单服务收到后执行：
     * 1. 乐观锁更新订单状态（WHERE status = 待支付）
     * 2. 预扣库存确认（库存由订单服务管理）
     * </p>
     *
     * @param orderId 订单ID
     * @param tradeNo 第三方交易号
     */
    @PostMapping("/api/order/pay-success")
    R<Void> notifyPaySuccess(@RequestParam("orderId") Long orderId,
                             @RequestParam("tradeNo") String tradeNo);

    /**
     * 支付失败通知
     *
     * @param orderId 订单ID
     */
    @PostMapping("/api/order/pay-fail")
    R<Void> notifyPayFail(@RequestParam("orderId") Long orderId);

    /**
     * 退款成功通知
     * <p>
     * 支付服务在退款成功后调用此接口，通知订单服务：
     * 1. 释放库存（恢复预扣减的库存）
     * 2. 退还优惠券（如果使用了优惠券）
     * 3. 更新订单状态为"已退款"
     * </p>
     *
     * @param orderId 订单ID
     * @param refundNo 退款单号
     */
    @PostMapping("/api/order/refund-success")
    R<Void> notifyRefundSuccess(@RequestParam("orderId") Long orderId,
                                @RequestParam("refundNo") String refundNo);

    /**
     * 退款失败/关闭通知
     *
     * @param orderId 订单ID
     * @param refundNo 退款单号
     */
    @PostMapping("/api/order/refund-fail")
    R<Void> notifyRefundFail(@RequestParam("orderId") Long orderId,
                             @RequestParam("refundNo") String refundNo);

    /**
     * 查询订单支付金额
     * <p>
     * 用于验证支付金额与订单金额是否匹配。
     * </p>
     */
    @GetMapping("/api/order/pay-amount")
    R<BigDecimal> getOrderPayAmount(@RequestParam("orderId") Long orderId);

    /**
     * 查询订单状态（P1-1：支付前回查订单是否待付款，防对已取消/已支付订单发起支付）
     *
     * @return 0=待付款；订单不存在返回 null
     */
    @GetMapping("/api/order/status")
    R<Integer> getOrderStatus(@RequestParam("orderId") Long orderId);
}
