package com.myxhs.inventory.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 预扣减请求
 */
@Data
public class PreDeductRequest {

    /** 订单ID（用于关联预扣记录） */
    @NotNull(message = "orderId不能为空")
    private Long orderId;

    /** SKU ID */
    @NotNull(message = "skuId不能为空")
    private Long skuId;

    /** 扣减数量（单次最多扣减999件，防止恶意请求） */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为1")
    @Max(value = 999, message = "单次扣减数量上限为999")
    private Integer quantity;

    /** 用户ID（用于分桶路由） */
    @NotNull(message = "userId不能为空")
    private Long userId;
}
