package com.myxhs.payment.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 结算对账差异（挂账）
 */
@Data
public class SettlementDiff implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 差异类型：本地有渠道无 */
    public static final int TYPE_LOCAL_ONLY = 1;
    /** 差异类型：渠道有本地无（长款） */
    public static final int TYPE_CHANNEL_ONLY = 2;
    /** 差异类型：金额不一致（本地-渠道） */
    public static final int TYPE_AMOUNT_MISMATCH = 3;

    /** 状态：待处理 */
    public static final int STATUS_PENDING = 0;
    /** 状态：已处理 */
    public static final int STATUS_HANDLED = 1;
    /** 状态：已忽略（对账自动收敛） */
    public static final int STATUS_IGNORED = 2;

    /** 业务类型：支付 */
    public static final int BIZ_PAY = 1;
    /** 业务类型：退款 */
    public static final int BIZ_REFUND = 2;

    private Long id;

    private LocalDate billDate;

    private Integer channel;

    private Integer bizType;

    private Integer diffType;

    private String localNo;

    private String channelNo;

    private BigDecimal localAmount;

    private BigDecimal channelAmount;

    /** 差异金额 = 本地 - 渠道 */
    private BigDecimal diffAmount;

    private Integer status;

    private String handleRemark;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
