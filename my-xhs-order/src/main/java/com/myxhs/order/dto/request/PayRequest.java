package com.myxhs.order.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 支付请求
 */
@Data
public class PayRequest {

    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 支付方式：1-支付宝(Mock) 2-微信(Mock) */
    @NotNull(message = "支付方式不能为空")
    @jakarta.validation.constraints.Min(value = 1, message = "支付方式最小值为1")
    @jakarta.validation.constraints.Max(value = 99, message = "支付方式最大值为99")
    private Integer payType;
}
