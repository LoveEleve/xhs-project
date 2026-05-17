package com.myxhs.cart.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 加入购物车请求
 */
@Data
public class CartAddRequest {

    /** SKU ID */
    @NotNull(message = "skuId不能为空")
    private Long skuId;

    /** 数量（默认1，上限99） */
    @Min(value = 1, message = "数量至少为1")
    @Max(value = 99, message = "单次加购数量上限为99")
    private Integer quantity = 1;
}
