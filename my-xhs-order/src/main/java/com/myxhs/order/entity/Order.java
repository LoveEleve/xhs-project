package com.myxhs.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表实体
 * <p>
 * 状态流转：0(待付款) → 1(已付款) → 2(已发货) → 3(已完成)
 *                    ↘ 4(已取消)       ↘ 5(已退款)
 * </p>
 */
@Data
@TableName("t_order")
public class Order implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String orderNo;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private BigDecimal discountAmount;
    private Long couponId;

    /** 状态：0-待付款 1-已付款 2-已发货 3-已完成 4-已取消 5-已退款 */
    private Integer status;

    /** 收货地址快照(JSON) */
    private String addressSnapshot;
    private String remark;
    private LocalDateTime paidAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
