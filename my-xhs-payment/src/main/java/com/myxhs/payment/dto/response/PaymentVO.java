package com.myxhs.payment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentVO {

    /** 支付单ID */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 支付流水号 */
    private String paymentNo;

    /** 支付金额 */
    private BigDecimal amount;

    /** 支付方式：1-支付宝(Mock) 2-微信(Mock) */
    private Integer payType;

    /** 状态：0-待支付 1-支付成功 2-支付失败 3-已退款 */
    private Integer status;

    /** 状态描述 */
    private String statusDesc;

    /** 支付成功时间 */
    private LocalDateTime paidAt;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
