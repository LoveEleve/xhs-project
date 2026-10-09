package com.myxhs.payment.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 退款单视图（内部查询用）
 * <p>
 * 时间字段统一格式化为字符串，避免跨服务 Feign 反序列化 LocalDateTime 的歧义。
 * </p>
 */
@Data
public class RefundVO {

    private String refundNo;

    private Long paymentId;

    private Long orderId;

    private BigDecimal refundAmount;

    /** 0-退款中 1-退款成功 2-退款失败 3-退款关闭 */
    private Integer status;

    private Integer refundType;

    private String reason;

    private String createdAt;

    private String successAt;
}
