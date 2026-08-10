package com.myxhs.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款单实体
 * <p>
 * 存储在独立库 my_xhs_payment 中，与 t_payment 同库。
 * 退款单记录每笔退款详情，支持部分退款（一笔支付可多次退款，总额不超过支付金额）。
 * </p>
 * <p>
 * 状态机：
 * 0(退款中) → 1(退款成功)
 *           → 2(退款失败)
 *           → 3(退款关闭)
 * 状态只能正向流转，不可跳跃，不可逆。
 * </p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_refund")
public class Refund implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联支付单ID */
    private Long paymentId;

    /** 订单ID */
    private Long orderId;

    /** 用户ID */
    private Long userId;

    /** 退款单号（格式：REFUND_日期_流水号 (如 REFUND20260516000001，Redis INCR)） */
    private String refundNo;

    /** 退款金额 */
    private BigDecimal refundAmount;

    /** 退款原因 */
    private String reason;

    /**
     * 状态：0-退款中 1-退款成功 2-退款失败 3-退款关闭
     * <p>
     * 乐观锁保证状态流转的原子性。
     * </p>
     */
    private Integer status;

    /** 退款类型：1-仅退款 2-退货退款 */
    private Integer refundType;

    /** 退款渠道：1-原路退回 2-退到余额 */
    private Integer refundChannel;

    /** 退款成功时间 */
    private LocalDateTime successAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
