package com.myxhs.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付记录实体
 * <p>
 * 存储在独立库 my_xhs_payment 中，使用 JdbcTemplate 操作（不走 ShardingSphere）。
 * </p>
 * <p>
 * 状态机：
 * 0(待支付) → 1(支付成功) → 3(已退款)
 *              ↓
 *           2(支付失败)
 * 状态只能正向流转，不可跳跃，不可逆。
 * </p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_payment")
public class Payment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 用户ID */
    private Long userId;

    /** 支付流水号（格式：PAY_时间戳_随机数） */
    private String paymentNo;

    /** 支付金额 */
    private BigDecimal amount;

    /** 支付方式：1-支付宝(Mock) 2-微信(Mock) */
    private Integer payType;

    /**
     * 状态：0-待支付 1-支付成功 2-支付失败 3-已退款
     * <p>
     * 乐观锁保证状态流转的原子性：
     * UPDATE t_payment SET status = 目标状态 WHERE order_id = ? AND status = 当前状态
     * </p>
     */
    private Integer status;

    /** 支付成功时间 */
    private LocalDateTime paidAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
