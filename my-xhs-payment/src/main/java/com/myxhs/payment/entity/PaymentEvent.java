package com.myxhs.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付事件流水（append-only，可观测性）
 * <p>
 * 记录支付各阶段（创建/成功/失败/退款）与失败原因，用于"支付成功率下降"归因。
 * 表：t_payment_event
 * </p>
 */
@Data
@TableName("t_payment_event")
public class PaymentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 支付流水号 */
    private String paymentNo;

    /** 订单ID */
    private Long orderId;

    /** 用户ID */
    private Long userId;

    /** 事件类型：CREATE / PAY_SUCCESS / PAY_FAIL / REFUND / TIMEOUT */
    private String eventType;

    /** 失败原因码（如业务错误码 30009/401xx，成功事件为空） */
    private String errorCode;

    /** 失败原因描述 */
    private String errorMsg;

    /** 事件时间 */
    private LocalDateTime eventTime;

    /** 落库时间 */
    private LocalDateTime createdAt;
}
