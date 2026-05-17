package com.myxhs.cart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 修改购物车数量请求
 */
@Data
public class CartUpdateQuantityRequest {

    /** SKU ID */
    @NotNull(message = "skuId不能为空")
    private Long skuId;

    /** 新数量 */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为1")
    @Max(value = 99, message = "单品数量上限为99")
    private Integer quantity;
}
