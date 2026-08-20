package com.myxhs.order.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 创建订单请求
 */
@Data
public class OrderCreateRequest {

    /** SKU 商品列表（T-068：补 @Valid 使嵌套校验生效——原缺失导致缺 skuId/quantity 返回 50002/500 而非 40002） */
    @NotEmpty(message = "商品列表不能为空")
    @jakarta.validation.Valid
    private List<SkuItem> skuItems;

    /** 优惠券ID（可选） */
    private Long couponId;

    /** 收货地址ID */
    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    /** 订单备注 */
    private String remark;

    /** 幂等键（前端生成的唯一标识，防重复下单） */
    @NotBlank(message = "幂等键不能为空")
    private String bizIdentifier;

    @Data
    public static class SkuItem {
        @NotNull(message = "SKU ID不能为空")
        private Long skuId;

        @NotNull(message = "数量不能为空")
        @jakarta.validation.constraints.Min(value = 1, message = "数量最少为1")
    private Integer quantity;
    }
}
