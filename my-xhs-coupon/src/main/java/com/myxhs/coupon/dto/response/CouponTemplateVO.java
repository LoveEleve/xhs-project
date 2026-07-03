package com.myxhs.coupon.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板 VO（对外暴露，不含 deleted/createdAt）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponTemplateVO {

    private Long id;

    /** 优惠券名称 */
    private String name;

    /** 类型：1-满减 2-折扣 3-无门槛 */
    private Integer type;

    /** 优惠金额/折扣率 */
    private BigDecimal discountValue;

    /** 最低消费金额 */
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
}
