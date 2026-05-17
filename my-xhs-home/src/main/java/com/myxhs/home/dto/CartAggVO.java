package com.myxhs.home.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 购物车聚合 VO（BFF 层返回给前端）
 * <p>
 * 聚合来源：
 * - 购物车列表 → cart 服务
 * - 商品信息（名称/图片/价格） → product 服务
 * - 库存状态 → inventory 服务
 * - 可用优惠券 → coupon 服务
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartAggVO {

    /** 购物车商品列表（聚合后） */
    private List<CartItemAggVO> items;

    /** 选中商品总数量 */
    private Integer checkedCount;

    /** 选中商品总金额 */
    private BigDecimal checkedAmount;

    /** 购物车商品总数（品种数） */
    private Integer totalCount;

    /** 是否全选 */
    private Boolean allChecked;

    /** 可用优惠券数量 */
    private Integer availableCouponCount;

    /** 可用优惠券列表（简要信息） */
    private List<Map<String, Object>> availableCoupons;

    /**
     * 购物车商品聚合项
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItemAggVO {
        private Long skuId;
        private Long spuId;
        private String skuName;
        private String skuImage;
        private BigDecimal price;
        private Integer quantity;
        private Boolean checked;
        private BigDecimal totalAmount;

        /** 库存状态 */
        private Integer availableStock;
        private Boolean hasStock;

        /** 商品状态（是否下架） */
        private Boolean onSale;
    }
}
