package com.myxhs.cart.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 匿名购物车合并请求
 */
@Data
public class CartMergeRequest {

    /** 匿名购物车项列表（最多 50 条，与购物车上限一致） */
    @NotNull(message = "items不能为空")
    @Size(max = 50, message = "一次最多合并50个商品")
    @Valid
    private List<MergeItem> items;

    @Data
    public static class MergeItem {
        /** SKU ID */
        @NotNull(message = "skuId不能为空")
        @Min(value = 1, message = "skuId必须为正数")
        private Long skuId;
        /** 数量（1-99，与单品数量上限一致） */
        @NotNull(message = "quantity不能为空")
        @Min(value = 1, message = "quantity最小为1")
        @Max(value = 99, message = "quantity最大为99")
        private Integer quantity;
    }
}
