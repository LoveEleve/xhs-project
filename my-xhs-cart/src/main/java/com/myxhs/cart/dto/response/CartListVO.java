package com.myxhs.cart.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车列表响应
 */
@Data
@Builder
public class CartListVO {

    /** 购物车商品列表 */
    private List<CartItemVO> items;

    /** 选中商品总数量 */
    private Integer checkedCount;

    /** 选中商品总金额 */
    private BigDecimal checkedAmount;

    /** 购物车商品总数（品种数） */
    private Integer totalCount;

    /** 是否全选 */
    private Boolean allChecked;
}
