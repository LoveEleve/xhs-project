package com.myxhs.payment.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 渠道流水（对账文件落地；也用于承载"本地流水"做逐笔比对）
 */
@Data
public class ChannelFlow implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型：支付 */
    public static final int BIZ_PAY = 1;
    /** 业务类型：退款 */
    public static final int BIZ_REFUND = 2;

    private Long id;

    private LocalDate billDate;

    private Integer channel;

    /** 渠道流水号（对账单内唯一） */
    private String channelNo;

    private Integer bizType;

    /** 渠道回传的商户单号（我方支付/退款单号） */
    private String localNo;

    private BigDecimal amount;

    private LocalDateTime tradeTime;

    /** 来源：import-导入 simulate-演练生成 */
    private String source;
}
