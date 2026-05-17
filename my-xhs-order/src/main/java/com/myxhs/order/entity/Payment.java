package com.myxhs.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录实体（存储在 my_xhs_payment 库）
 * <p>
 * 当前 MVP 阶段支付表和订单表在同一个 DataSource 中管理（简化部署）。
 * 生产环境应拆分为独立的 payment 服务。
 * </p>
 */
@Data
@TableName("t_payment")
public class Payment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;
    private Long userId;
    private String paymentNo;
    private BigDecimal amount;

    /** 支付方式：1-支付宝(Mock) 2-微信(Mock) */
    private Integer payType;

    /** 状态：0-待支付 1-支付成功 2-支付失败 3-已退款 */
    private Integer status;

    private LocalDateTime paidAt;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
