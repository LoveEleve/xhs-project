package com.myxhs.cart.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 匿名购物车合并请求
 */
@Data
public class CartMergeRequest {

    /** 匿名购物车项列表 */
    @NotNull(message = "items不能为空")
    private List<MergeItem> items;

    @Data
    public static class MergeItem {
        /** SKU ID */
        private Long skuId;
        /** 数量 */
        private Integer quantity;
    }
}
