package com.myxhs.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细实体
 */
@Data
@TableName("t_order_item")
public class OrderItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;
    /** 子订单ID（ITER-001 拆单：行归属的子单；履约/展示分组用） */
    private Long subOrderId;
    /** 用户ID（分片键，冗余存储用于 ShardingSphere 路由） */
    private Long userId;
    private Long skuId;
    /** 卖家ID（0=平台自营；冗余落库便于行级查询与对账） */
    private Long sellerId;
    private Long spuId;
    private String skuName;
    private String skuImage;
    private BigDecimal price;
    private Integer quantity;
    private BigDecimal totalAmount;
    /** 分摊优惠（下单时最大余数法落库；行实付 = totalAmount - discountShare） */
    private BigDecimal discountShare;
    private LocalDateTime createdAt;
}
