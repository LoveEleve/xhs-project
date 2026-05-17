package com.myxhs.cart.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 勾选/取消勾选请求
 */
@Data
public class CartCheckRequest {

    /** SKU ID */
    @NotNull(message = "skuId不能为空")
    private Long skuId;

    /** 是否选中：true-选中 false-取消选中 */
    @NotNull(message = "checked不能为空")
    private Boolean checked;
}
