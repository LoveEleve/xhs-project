package com.myxhs.order.dto.event;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单终结事件（ORDER_TERMINATION_TOPIC 消息体）
 * <p>
 * 04B 重构：由 terminateOrder 在"状态 0→4 迁移 + 本地消息表（Outbox）"同一本地事务中生成，
 * 事务提交后即时投递（失败由 LocalMessageRetryJob 兜底补发）。
 * </p>
 * <p>
 * 订阅方（各自只做自己域的事，不再由订单侧同步 Feign 编排）：
 * - inventory：按 orderNo 派生伪订单ID，释放预扣 + 写终结墓碑（拦截迟到预扣）
 * - coupon：按 couponId 退券（乐观锁幂等）
 * - notification：按 reason 生成用户通知（type=5 订单通知）
 * </p>
 * <p>
 * 幂等约定：消息 KEYS header = orderNo（跨重投稳定，Outbox 补发换新 msgId 时仍可去重）；
 * 消费者除 msgId/KEYS 去重外，各域内在幂等（库存墓碑/退券乐观锁/通知窗口锁）为最终防线。
 * </p>
 */
@Data
@Builder
public class OrderTerminationEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件版本（消费者按版本做兼容演进） */
    private Integer eventVersion;

    /** 订单ID（真实订单ID，非伪ID；coupon/notification 直接使用） */
    private Long orderId;

    /** 订单号（inventory 侧 SHA-256 派生伪订单ID；消息幂等键） */
    private String orderNo;

    /** 用户ID（分片键冗余） */
    private Long userId;

    /** 下单使用的用户券ID（可为空；coupon 侧退券入参 userCouponId） */
    private Long couponId;

    /** 终结原因（TerminationReason.name()：USER_CANCELLED/TIMEOUT/INVENTORY_FAILED/COUPON_FAILED） */
    private String reason;

    /** 终结来源（APP / TIMEOUT_JOB / SYSTEM） */
    private String source;

    /** 发生时间（ISO-8601 字符串） */
    private String occurredAt;

    /** 链路追踪ID（跨服务排障） */
    private String traceId;
}
