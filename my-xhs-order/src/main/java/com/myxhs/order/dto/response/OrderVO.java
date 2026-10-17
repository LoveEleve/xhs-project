package com.myxhs.order.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情 VO
 * <p>
 * ITER-001 拆单：详情按三层返回——父单（本对象）+ 子单列表（subOrders，含各自的行 items）。
 * 顶层 items 保留（全部行平铺，向后兼容）；子单维度供履约/售后/展示分组使用。
 * </p>
 */
@Data
@Builder
public class OrderVO {

    private Long orderId;
    private String orderNo;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private BigDecimal discountAmount;
    private Integer status;
    private String statusDesc;
    private String remark;
    private String addressSnapshot;
    private LocalDateTime createdAt;
    private LocalDateTime paidAt;

    /** 订单明细（全部行平铺，向后兼容） */
    private List<OrderItemVO> items;

    /** 子订单列表（按商家拆单；履约/售后展示锚点） */
    private List<SubOrderVO> subOrders;

    @Data
    @Builder
    public static class OrderItemVO {
        private Long skuId;
        private String skuName;
        private String skuImage;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal totalAmount;
        /** 行分摊优惠（ITER-001：行实付 = totalAmount - discountShare） */
        private BigDecimal discountShare;
        /** 行实付（totalAmount - discountShare） */
        private BigDecimal payAmount;
        /** 归属子订单ID */
        private Long subOrderId;
    }

    /** 子订单 VO（状态：0待付款/1待发货/2已发货/3已完成/4已取消/5已退款） */
    @Data
    @Builder
    public static class SubOrderVO {
        private Long subOrderId;
        private String subOrderNo;
        private Long sellerId;
        private Integer status;
        private String statusDesc;
        private BigDecimal totalAmount;
        private BigDecimal discountAmount;
        private BigDecimal payAmount;
        private LocalDateTime deliveredAt;
        private LocalDateTime completedAt;
        /** 子单下的行 */
        private List<OrderItemVO> items;
    }
}
