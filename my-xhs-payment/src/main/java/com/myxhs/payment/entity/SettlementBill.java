package com.myxhs.payment.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 结算日账单（日切）
 * <p>
 * 一个渠道一天一张，唯一键 (bill_date, channel) 保证幂等重跑。
 * </p>
 */
@Data
public class SettlementBill implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：初始 */
    public static final int STATUS_INIT = 0;
    /** 状态：已生成（可重跑覆盖） */
    public static final int STATUS_GENERATED = 1;
    /** 状态：已对账且无差异 */
    public static final int STATUS_RECONCILED = 2;
    /** 状态：已对账但有差异（挂账） */
    public static final int STATUS_HAS_DIFF = 3;
    /** 状态：作废 */
    public static final int STATUS_VOID = 4;

    private Long id;

    private LocalDate billDate;

    /** 渠道：1-支付宝 2-微信 99-Mock */
    private Integer channel;

    private Integer payCount;

    private BigDecimal payAmount;

    private Integer refundCount;

    private BigDecimal refundAmount;

    /** 净额 = 收款 - 退款 */
    private BigDecimal netAmount;

    private BigDecimal feeRate;

    private BigDecimal feeAmount;

    /** 应结算 = 净额 - 手续费 */
    private BigDecimal settleAmount;

    private Integer status;

    /** 重跑次数 */
    private Integer runNo;

    private Integer diffCount;

    private BigDecimal diffAmount;

    private LocalDateTime generatedAt;

    private LocalDateTime reconciledAt;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
