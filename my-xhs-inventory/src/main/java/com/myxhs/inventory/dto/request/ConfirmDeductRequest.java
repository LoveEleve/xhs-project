package com.myxhs.inventory.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 确认扣减请求（支付成功后调用）
 */
@Data
public class ConfirmDeductRequest {

    /** 订单ID */
    @NotNull(message = "orderId不能为空")
    private Long orderId;
}
