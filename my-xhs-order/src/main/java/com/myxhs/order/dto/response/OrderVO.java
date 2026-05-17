package com.myxhs.order.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情 VO
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

    /** 订单明细 */
    private List<OrderItemVO> items;

    @Data
    @Builder
    public static class OrderItemVO {
        private Long skuId;
        private String skuName;
        private String skuImage;
        private BigDecimal price;
        private Integer quantity;
        private BigDecimal totalAmount;
    }
}
