package com.myxhs.common.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 补偿消息 DTO（MQ 消息体）
 * <p>
 * 当 Feign 调用失败时由 OrderService 发送到 ORDER_COMPENSATION_TOPIC，
 * 由 OrderCompensationConsumer 消费并重试关单。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompensationMessage {

    /** 补偿操作类型：RELEASE_STOCK / RETURN_COUPON */
    private String action;

    /** 订单ID */
    private Long orderId;

    /** 失败原因 */
    private String failReason;

    /** 消息时间戳 */
    private Long timestamp;
}
