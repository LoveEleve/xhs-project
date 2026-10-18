package com.myxhs.search.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 商家维度子单（列表项）
 * <p>
 * C-004：数据来源=ES 异构索引（seller_sub_order_index）。<b>ES 只做发现</b>——
 * 展示字段可能滞后于 DB 秒级；一切操作/终态判断以 DB + CAS 为准。
 * </p>
 */
@Data
public class SellerOrderVO {

    /** 子单号（商家履约/售后锚点） */
    private String subOrderNo;

    /** 父单号 */
    private String orderNo;

    /** 子单状态（0待支付 1已付款 2已发货 3已完成 4已取消 5已退款——与 SubOrder 状态常量一致） */
    private Integer status;

    /** 子单总金额（行小计合计） */
    private BigDecimal totalAmount;

    /** 子单优惠分摊合计 */
    private BigDecimal discountAmount;

    /** 子单实付（totalAmount - discountAmount） */
    private BigDecimal payAmount;

    /** 下单时间（yyyy-MM-dd HH:mm:ss） */
    private String createdAt;
}
