package com.myxhs.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板实体
 * <p>
 * 对应表 t_coupon_template。
 * 类型：1-满减（订单金额 >= minAmount 时减 discountValue）
 *       2-折扣（订单金额 * discountValue / 10）
 *       3-无门槛（直接减 discountValue）
 * </p>
 */
@Data
@TableName("t_coupon_template")
public class CouponTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 优惠券名称 */
    private String name;

    /** 类型：1-满减 2-折扣 3-无门槛 */
    private Integer type;

    /** 优惠金额/折扣率（折扣类型时为折扣值，如 8.5 表示 85 折） */
    private BigDecimal discountValue;

    /** 最低消费金额（无门槛券为 0） */
    private BigDecimal minAmount;

    /** 发放总量 */
    private Integer totalCount;

    /** 剩余数量 */
    private Integer remainCount;

    /** 每人限领 */
    private Integer perUserLimit;

    /** 有效期开始 */
    private LocalDateTime validStart;

    /** 有效期结束 */
    private LocalDateTime validEnd;

    /** 状态：0-禁用 1-启用 */
    private Integer status;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
