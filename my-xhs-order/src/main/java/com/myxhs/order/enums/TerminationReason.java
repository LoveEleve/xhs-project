package com.myxhs.order.enums;

import com.myxhs.order.service.OrderEventService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 订单终结原因（唯一终结入口 terminateOrder 的入参）
 * <p>
 * 04B 重构：四条终结路径（用户取消/超时关单/库存失败/券失败）统一收敛到 terminateOrder，
 * 原因枚举承载各路径的语义差异：
 * - displayName：取消原因（写入事件 payload，审计归因）
 * - eventType：Event Sourcing 事件类型（决定 to_status=4，见 OrderEventService 映射）
 * - snapshotEvent：快照事件名（t_order_snapshot.event 留痕）
 * </p>
 * <p>
 * 约束：新增终结路径必须在此登记（并补齐 OrderEventService 的映射/白名单），
 * 不允许绕过 terminateOrder 直接改订单状态。
 * </p>
 */
@Getter
@RequiredArgsConstructor
public enum TerminationReason {

    /** 用户主动取消（APP 端"取消订单"） */
    USER_CANCELLED("用户主动取消", OrderEventService.EVENT_CANCELLED, "CANCELLED"),

    /** 超时未支付（延时消息/定时任务扫描） */
    TIMEOUT("超时未支付", OrderEventService.EVENT_TIMEOUT_CANCELLED, "TIMEOUT_CANCELLED"),

    /** 库存预扣失败（inventory → order 收口，不等 30 分钟超时） */
    INVENTORY_FAILED("库存预扣失败", OrderEventService.EVENT_INVENTORY_FAILED_CANCELLED, "INVENTORY_FAILED_CANCELLED"),

    /** 优惠券核销失败（下单链路同步终结，回滚已占用资源） */
    COUPON_FAILED("优惠券核销失败", OrderEventService.EVENT_COUPON_FAILED_CANCELLED, "COUPON_FAILED_CANCELLED"),

    /** 支付失败（支付失败回调；收尾补录的第五条终结路径——旧实现绕过统一入口，见收尾 Review） */
    PAY_FAILED("支付失败", OrderEventService.EVENT_PAY_FAILED_CANCELLED, "PAY_FAILED_CANCELLED");

    /** 取消原因文案（事件 payload / 审计） */
    private final String displayName;

    /** Event Sourcing 事件类型 */
    private final String eventType;

    /** 快照事件名 */
    private final String snapshotEvent;
}
