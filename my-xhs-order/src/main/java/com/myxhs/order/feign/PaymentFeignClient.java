package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 支付服务 Feign 客户端
 * <p>
 * 订单服务通过此客户端调用独立支付服务的接口：
 * 1. 发起支付：POST /api/payment/pay
 * 2. 查询支付状态：GET /api/payment/status/{orderId}
 * 3. 发起退款：POST /api/payment/refund
 * </p>
 */
@FeignClient(name = "my-xhs-payment",
        configuration = InternalCallFeignConfig.class,
        fallbackFactory = PaymentFeignFallbackFactory.class)
public interface PaymentFeignClient {

    /**
     * 发起支付
     */
    @PostMapping("/api/payment/pay")
    R<Object> pay(@RequestBody PayCreateRequest request, @RequestHeader("X-User-Id") Long userId);

    /**
     * 查询支付状态
     */
    @GetMapping("/api/payment/status/{orderId}")
    R<Object> getPaymentStatus(@PathVariable("orderId") Long orderId);

    /**
     * 发起退款
     */
    @PostMapping("/api/payment/refund")
    R<Void> refund(@RequestBody RefundCreateRequest request, @RequestHeader("X-User-Id") Long userId);

    /**
     * 发起支付的请求体（内部类）
     */
    @lombok.Data
    class PayCreateRequest {
        private Long orderId;
        private Long userId;
        private BigDecimal amount;
        private Integer payType;
    }

    /**
     * 退款的请求体（内部类）
     */
    @lombok.Data
    class RefundCreateRequest {
        private Long paymentId;
        private Long userId;
        private BigDecimal refundAmount;
        private String reason;
        private Integer refundType;
    }
}
