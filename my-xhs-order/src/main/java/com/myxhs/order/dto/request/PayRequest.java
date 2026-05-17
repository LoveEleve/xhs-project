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
    private Integer payType;
}
