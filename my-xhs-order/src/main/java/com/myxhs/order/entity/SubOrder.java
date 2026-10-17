package com.myxhs.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 子订单实体（ITER-001 拆单：父单按商家拆分）
 * <p>
 * 三层模型：父单（交易/支付，t_order）→ 子单（商家/履约，本表）→ 行（SKU，t_order_item）。
 * 子单是**真正流转的主体**（发货/确认收货发生在子单维度）；父单状态 = 子单状态聚合
 * （{@link com.myxhs.order.service.SubOrderService#computeParentStatus}）。
 * 分片：与父单同分片键 user_id（分库分表路由与绑定表见 sharding-config.yaml）。
 * </p>
 */
@Data
@TableName("t_sub_order")
public class SubOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态：待付款（与父单同生共死，支付成功批量推进） */
    public static final int STATUS_PENDING_PAYMENT = 0;
    /** 状态：待发货 */
    public static final int STATUS_TO_SHIP = 1;
    /** 状态：已发货 */
    public static final int STATUS_SHIPPED = 2;
    /** 状态：已完成 */
    public static final int STATUS_COMPLETED = 3;
    /** 状态：已取消（未支付终结时全子单同事务取消） */
    public static final int STATUS_CANCELLED = 4;
    /** 状态：已退款（全退收敛时全子单置退款） */
    public static final int STATUS_REFUNDED = 5;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 父订单ID */
    private Long orderId;

    /** 父订单号 */
    private String orderNo;

    /** 子订单号（履约/售后展示锚点） */
    private String subOrderNo;

    /** 用户ID（分片键，与父单一致） */
    private Long userId;

    /** 卖家ID（0=平台自营） */
    private Long sellerId;

    /** 状态：0待付款/1待发货/2已发货/3已完成/4已取消/5已退款 */
    private Integer status;

    /** 商品金额（Σ行小计） */
    private BigDecimal totalAmount;

    /** 优惠金额（Σ行分摊） */
    private BigDecimal discountAmount;

    /** 实付金额（Σ行实付 = totalAmount - discountAmount） */
    private BigDecimal payAmount;

    private LocalDateTime deliveredAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime refundedAt;
    /** 逻辑删除（与 t_order 同口径） */
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
