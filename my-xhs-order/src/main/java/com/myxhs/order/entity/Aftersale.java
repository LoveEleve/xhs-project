package com.myxhs.order.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后单（仅退款 / 退货退款）
 * <p>
 * 与订单号映射同库（my_xhs_order 公共库），不走分片：
 * 售后单量级小、以 aftersale_no / order_no 为主查询键；
 * 扩容路径为按 user_id 分片（与订单同规则）。
 * </p>
 */
@Data
public class Aftersale implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：待审核 */
    public static final int STATUS_APPLIED = 0;
    /** 状态：已同意 */
    public static final int STATUS_APPROVED = 1;
    /** 状态：已拒绝 */
    public static final int STATUS_REJECTED = 2;
    /** 状态：退款中 */
    public static final int STATUS_REFUNDING = 3;
    /** 状态：已完成 */
    public static final int STATUS_FINISHED = 4;
    /** 状态：已取消（用户撤销） */
    public static final int STATUS_CANCELLED = 5;
    /** 状态：退款失败（可重试） */
    public static final int STATUS_REFUND_FAILED = 6;

    /** 类型：仅退款 */
    public static final int TYPE_REFUND_ONLY = 1;
    /** 类型：退货退款 */
    public static final int TYPE_RETURN_REFUND = 2;

    private Long id;

    /** 售后单号 */
    private String aftersaleNo;

    private Long orderId;

    private String orderNo;

    private Long userId;

    private Long skuId;

    private String skuName;

    /** 1-仅退款 2-退货退款 */
    private Integer type;

    private Integer status;

    /** 申请数量 */
    private Integer applyQuantity;

    /** 申请数量对应商品金额（未扣优惠） */
    private BigDecimal itemAmount;

    /** 分摊到本明细的优惠金额 */
    private BigDecimal discountShare;

    /** 应退金额 = 商品金额 - 优惠分摊 */
    private BigDecimal refundAmount;

    private String reason;

    private String rejectReason;

    /** 退货物流单号（type=2） */
    private String returnWaybill;

    /** 支付域退款单号（预留回执位） */
    private String refundNo;

    /** 库存回补状态：0-待回补 1-已回补（跨服务回补失败的兜底依据） */
    private Integer restockStatus;

    /** 退款重试次数 */
    private Integer retryCount;

    private LocalDateTime appliedAt;

    private LocalDateTime auditedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime updatedAt;
}
