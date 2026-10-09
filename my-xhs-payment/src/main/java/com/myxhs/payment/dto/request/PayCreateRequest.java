package com.myxhs.payment.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 创建支付请求
 */
@Data
public class PayCreateRequest {

    /** 订单ID */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 用户ID（由网关透传，不需要前端传） */
    private Long userId;

    /** 支付金额 */
    @NotNull(message = "支付金额不能为空")
    /** 允许 0：全额优惠券抵扣的零元订单需发起支付（渠道不可调用，由支付域直接记账成功） */
    @jakarta.validation.constraints.DecimalMin(value = "0.00", message = "支付金额不能为负")
    private BigDecimal amount;

    /** 支付方式：1-支付宝(Mock) 2-微信(Mock) */
    @NotNull(message = "支付方式不能为空")
    @Min(value = 1, message = "支付方式最小值为1")
    @Max(value = 99, message = "支付方式最大值为99")
    private Integer payType;
}
