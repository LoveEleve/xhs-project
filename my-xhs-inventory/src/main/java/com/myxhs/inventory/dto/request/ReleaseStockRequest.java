package com.myxhs.inventory.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 释放库存请求（取消订单/超时未支付）
 */
@Data
public class ReleaseStockRequest {

    /** 订单ID */
    @NotNull(message = "orderId不能为空")
    private Long orderId;
}
