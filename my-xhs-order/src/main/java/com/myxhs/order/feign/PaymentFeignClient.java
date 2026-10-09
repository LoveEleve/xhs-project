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
     * 按订单发起退款（售后场景）：上游只持有 orderId，支付域内部解析 paymentId
     */
    @PostMapping("/api/payment/refund-by-order")
    R<String> refundByOrder(@RequestBody RefundByOrderRequest request, @RequestHeader("X-User-Id") Long userId);

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
     * 按订单查询退款单（售后恢复：核对是否已实际退款成功）
     */
    @GetMapping("/api/payment/refunds/{orderId}")
    R<java.util.List<RefundView>> listRefunds(@PathVariable("orderId") Long orderId);

    /**
     * 退款单视图（内部类，字段与支付域 RefundVO 对齐）
     */
    @lombok.Data
    class RefundView {
        private String refundNo;
        private java.math.BigDecimal refundAmount;
        /** 0-退款中 1-退款成功 2-退款失败 3-退款关闭 */
        private Integer status;
        private Integer refundType;
        private String createdAt;
        private String successAt;
    }

    /**
     * 按订单退款的请求体（内部类）
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    class RefundByOrderRequest {
        private Long orderId;
        private BigDecimal refundAmount;
        private String reason;
        private Integer refundType;
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
