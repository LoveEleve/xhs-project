package com.myxhs.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.order.dto.request.AftersaleApplyRequest;
import com.myxhs.order.entity.Aftersale;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderItem;
import com.myxhs.order.feign.InventoryFeignClient;
import com.myxhs.order.feign.PaymentFeignClient;
import com.myxhs.order.mapper.OrderItemMapper;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.repository.AftersaleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 售后域：仅退款 / 退货退款
 * <p>
 * 核心设计：
 * 1. 应退金额按"订单优惠分摊"计算——退款 = 明细金额 - 分摊优惠，
 *    分摊用 {@link DiscountAllocator} 按订单快照确定性重算，不落库、不会因算法漂移而不一致；
 * 2. 状态机：待审核(0) → 已同意(1)/已拒绝(2) → 退款中(3) → 已完成(4)；另有已取消(5)、退款失败(6)；
 *    所有流转都是条件更新（WHERE status=期望值），并发冲突时返回 0 并报错，不做补偿；
 * 3. 退款受理走支付域"按订单退款"接口（上游不感知 paymentId）；库存回补按 SKU 调库存域
 *    refend-restore（库存域按 orderId+skuId 幂等，与全额退款链路天然不重复）；
 * 4. 跨库一致性：售后表在公共库、订单/明细在分片库，不做分布式本地事务，
 *    依靠唯一键（order_id+sku_id+type）+ 条件更新 + 累计退款额度校验保证不重不漏。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AftersaleService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final AftersaleRepository aftersaleRepository;
    private final PaymentFeignClient paymentFeignClient;
    private final InventoryFeignClient inventoryFeignClient;
    private final IdGeneratorUtil idGeneratorUtil;
    private final BusinessMetrics businessMetrics;

    private static final String NO_PREFIX = "AS";

    /** 与支付域 RefundVO 的时间格式保持一致（yyyy-MM-dd HH:mm:ss） */
    private static final java.time.format.DateTimeFormatter PAYMENT_TIME_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_REASON_LENGTH = 200;
    private static final int DEFAULT_LIST_LIMIT = 50;

    // ==================== 用户侧 ====================

    /**
     * 申请售后（同一订单同一 SKU 同类型只允许一张售后单；驳回/取消/退款失败后可重新申请）
     */
    public Aftersale apply(Long userId, AftersaleApplyRequest request) {
        int type = request.getType();
        if (type != Aftersale.TYPE_REFUND_ONLY && type != Aftersale.TYPE_RETURN_REFUND) {
            throw new BizException(ResultCode.PARAM_INVALID, "售后类型不合法");
        }
        if (request.getReason() != null && request.getReason().length() > MAX_REASON_LENGTH) {
            throw new BizException(ResultCode.PARAM_INVALID, "申请原因过长");
        }

        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .eq(Order::getId, request.getOrderId()));
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() == null || order.getStatus() < 1 || order.getStatus() > 3) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "当前订单状态不支持申请售后");
        }
        if (type == Aftersale.TYPE_RETURN_REFUND && order.getStatus() == 1) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "未发货订单请选择仅退款");
        }

        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, order.getId())
                .eq(OrderItem::getUserId, userId));
        OrderItem target = items.stream()
                .filter(item -> item.getSkuId() != null && item.getSkuId().equals(request.getSkuId()))
                .findFirst()
                .orElseThrow(() -> new BizException(ResultCode.ORDER_NOT_FOUND, "订单中不存在该商品"));

        int quantity = request.getApplyQuantity() != null ? request.getApplyQuantity() : target.getQuantity();
        if (quantity <= 0 || target.getQuantity() == null || quantity > target.getQuantity()) {
            throw new BizException(ResultCode.PARAM_INVALID, "申请数量不合法");
        }

        // 优惠分摊 + SKU 维度权益（纯函数，按订单快照重算）
        SkuEntitlement entitlement = entitlement(order, items, target);
        BigDecimal refundable = resolveRefundable(entitlement, quantity);
        if (refundable.signum() <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "该商品可退金额为 0（可能已退完）");
        }

        // 单品维度上限（跨售后类型）：同一 SKU 累计应退不得超过"整件可退金额 = 明细金额 - 分摊优惠"
        // 否则"仅退款"退一部分后再用"退货退款"退整件，会出现同一商品被退两次的超退
        if (entitlement.occupiedAmount().add(refundable).compareTo(entitlement.refundableTotal()) > 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "该商品累计退款金额超过可退上限");
        }
        BigDecimal share = entitlement.discountShare();

        // 累计退款额度保护：已占用（退款中/已完成） + 本次 ≤ 订单实付
        BigDecimal occupied = aftersaleRepository.sumRefundedAmount(order.getId());
        BigDecimal payAmount = order.getPayAmount() != null ? order.getPayAmount() : BigDecimal.ZERO;
        if (occupied.add(refundable).compareTo(payAmount) > 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "累计退款金额将超过订单实付金额");
        }

        Aftersale entity = buildEntity(order, target, type, quantity, share, refundable, request);

        Aftersale existing = aftersaleRepository.findByOrderSkuType(order.getId(), request.getSkuId(), type);
        if (existing == null) {
            entity.setAftersaleNo(NO_PREFIX + idGeneratorUtil.nextId());
            entity.setStatus(Aftersale.STATUS_APPLIED);
            try {
                aftersaleRepository.insert(entity);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发重复申请：唯一键 (order_id, sku_id, type) 兜底
                businessMetrics.recordAftersaleApply("duplicated");
                throw new BizException(ResultCode.IDEMPOTENT_REJECT, "该商品已有进行中的售后申请");
            }
            businessMetrics.recordAftersaleApply("applied");
            log.info("[售后] 申请成功: aftersaleNo={}, orderId={}, skuId={}, qty={}, refundAmount={}",
                    entity.getAftersaleNo(), order.getId(), request.getSkuId(), quantity, refundable);
            return aftersaleRepository.findByNo(entity.getAftersaleNo());
        }

        if (existing.getStatus() == Aftersale.STATUS_REJECTED
                || existing.getStatus() == Aftersale.STATUS_CANCELLED
                || existing.getStatus() == Aftersale.STATUS_REFUND_FAILED) {
            int affected = aftersaleRepository.reapply(existing.getId(), existing.getStatus(), entity);
            if (affected == 0) {
                throw new BizException(ResultCode.IDEMPOTENT_REJECT, "售后单状态已变化，请刷新后重试");
            }
            return aftersaleRepository.findByNo(existing.getAftersaleNo());
        }
        businessMetrics.recordAftersaleApply("duplicated");
        throw new BizException(ResultCode.IDEMPOTENT_REJECT, "该商品已有进行中的售后申请");
    }

    public Aftersale get(Long userId, String aftersaleNo) {
        Aftersale aftersale = mustGet(aftersaleNo);
        if (!aftersale.getUserId().equals(userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看该售后单");
        }
        return aftersale;
    }

    public List<Aftersale> list(Long userId, Integer limit) {
        int size = (limit == null || limit <= 0) ? DEFAULT_LIST_LIMIT : Math.min(limit, 200);
        return aftersaleRepository.listByUser(userId, size);
    }

    /** 用户撤销：仅待审核可撤 */
    public Aftersale cancel(Long userId, String aftersaleNo) {
        Aftersale aftersale = get(userId, aftersaleNo);
        if (aftersale.getStatus() != Aftersale.STATUS_APPLIED) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "仅待审核的售后单可以撤销");
        }
        int affected = aftersaleRepository.cancel(aftersale.getId(), Aftersale.STATUS_APPLIED, Aftersale.STATUS_CANCELLED);
        if (affected == 0) {
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "售后单状态已变化，请刷新后重试");
        }
        return aftersaleRepository.findByNo(aftersaleNo);
    }

    // ==================== 恢复任务（卡死回收 / 自动重试） ====================

    /** 退款中卡死阈值（分钟）：超过则向支付域核对事实 */
    private static final int STUCK_MINUTES = 5;
    /** 退款失败自动重试上限（次） */
    private static final int MAX_AUTO_RETRY = 3;
    /** 自动重试退避（分钟） */
    private static final int RETRY_BACKOFF_MINUTES = 10;
    /** 单轮处理上限（防一次拉太多） */
    private static final int RECOVER_BATCH = 200;

    /**
     * 售后恢复（XXL-Job 调用）：处理两类异常单
     * <p>
     * 1. <b>退款中卡死</b>：先查支付域退款单事实再决策——已成功（金额/类型/时间窗匹配）→ 补库存并置完成；
     * 仍有退款中的单 → 保持等待；既无成功也无进行中 → 转退款失败交给重试分支。
     * 这样可正确覆盖"Feign 超时但支付域已受理/已完成"的未知结果场景，避免盲目重试。
     * 2. <b>退款失败自动重试</b>：退避 10 分钟、最多 3 次（支付域可退金额上限是最终防线）；
     * 3. <b>已完成未回补</b>：退款成功但库存回补失败（含库存域整体不可用）→ 重试回补并标记，
     *    覆盖"跨服务调用失败时库存域内部补偿表无记录"的盲区。
     * </p>
     *
     * @return 处理摘要
     */
    public Map<String, Object> recoverStuckAndRetry() {
        Map<String, Object> summary = new LinkedHashMap<>();
        int recovered = 0;
        int failedOver = 0;
        int stillProcessing = 0;
        int retried = 0;

        LocalDateTime stuckBefore = LocalDateTime.now().minusMinutes(STUCK_MINUTES);
        for (Aftersale aftersale : aftersaleRepository.listByStatus(Aftersale.STATUS_REFUNDING, RECOVER_BATCH)) {
            if (aftersale.getAuditedAt() != null && aftersale.getAuditedAt().isAfter(stuckBefore)) {
                stillProcessing++;
                continue;
            }
            switch (resolveStuck(aftersale)) {
                case RECOVERED -> recovered++;
                case FAILED_OVER -> failedOver++;
                default -> stillProcessing++;
            }
        }

        LocalDateTime retryBefore = LocalDateTime.now().minusMinutes(RETRY_BACKOFF_MINUTES);
        for (Aftersale aftersale : aftersaleRepository.listByStatus(Aftersale.STATUS_REFUND_FAILED, RECOVER_BATCH)) {
            int retryCount = aftersale.getRetryCount() == null ? 0 : aftersale.getRetryCount();
            if (retryCount >= MAX_AUTO_RETRY) {
                continue;
            }
            if (aftersale.getUpdatedAt() != null && aftersale.getUpdatedAt().isAfter(retryBefore)) {
                continue;
            }
            try {
                retryRefund(aftersale.getAftersaleNo());
                retried++;
            } catch (Exception e) {
                log.warn("[售后恢复] 自动重试失败: aftersaleNo={}, cause={}", aftersale.getAftersaleNo(), e.getMessage());
            }
        }

        // 3) 已完成但库存未回补（跨服务回补失败）→ 重试回补
        int restocked = 0;
        for (Aftersale aftersale : aftersaleRepository.listPendingRestock(RECOVER_BATCH)) {
            if (restoreStock(aftersale)) {
                aftersaleRepository.markRestocked(aftersale.getId());
                restocked++;
            }
        }

        summary.put("recovered", recovered);
        summary.put("failedOver", failedOver);
        summary.put("stillProcessing", stillProcessing);
        summary.put("retried", retried);
        summary.put("restocked", restocked);
        log.info("[售后恢复] 完成: {}", summary);
        return summary;
    }

    private enum StuckResult { RECOVERED, FAILED_OVER, STILL_PROCESSING }

    /**
     * 核对支付域退款单事实，决定卡死单的归属
     */
    private StuckResult resolveStuck(Aftersale aftersale) {
        R<List<PaymentFeignClient.RefundView>> resp;
        try {
            resp = paymentFeignClient.listRefunds(aftersale.getOrderId());
        } catch (Exception e) {
            log.warn("[售后恢复] 支付域查询异常, 保持退款中待下轮: aftersaleNo={}", aftersale.getAftersaleNo(), e);
            return StuckResult.STILL_PROCESSING;
        }
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            log.warn("[售后恢复] 支付域查询失败, 保持退款中待下轮: aftersaleNo={}", aftersale.getAftersaleNo());
            return StuckResult.STILL_PROCESSING;
        }

        java.util.Set<String> usedRefundNos = aftersaleRepository.usedRefundNos(aftersale.getOrderId());
        boolean hasProcessing = false;
        for (PaymentFeignClient.RefundView refund : resp.getData()) {
            // 一个成功退款只能归属一张售后单：已被其他售后单认领的退款单不再参与匹配（防同额误认）
            if (refund.getRefundNo() != null && usedRefundNos.contains(refund.getRefundNo())) {
                continue;
            }
            if (!matches(aftersale, refund)) {
                continue;
            }
            if (refund.getStatus() != null && refund.getStatus() == 1) {
                // 支付域已实际退款成功：补库存（幂等）+ 置完成并认领退款单号，修正"未知结果"下的错误结论
                if (restoreStock(aftersale)) {
                    aftersaleRepository.markRestocked(aftersale.getId());
                }
                aftersaleRepository.updateOnRefundSuccess(aftersale.getId(), Aftersale.STATUS_REFUNDING,
                        Aftersale.STATUS_FINISHED, refund.getRefundNo());
                log.warn("[售后恢复] 支付域已退款成功, 售后单修正为完成: aftersaleNo={}, refundNo={}",
                        aftersale.getAftersaleNo(), refund.getRefundNo());
                return StuckResult.RECOVERED;
            }
            if (refund.getStatus() != null && refund.getStatus() == 0) {
                hasProcessing = true;
            }
        }
        if (hasProcessing) {
            return StuckResult.STILL_PROCESSING;
        }
        aftersaleRepository.markStuckFailed(aftersale.getId());
        log.warn("[售后恢复] 支付域无对应退款记录, 转退款失败待重试: aftersaleNo={}", aftersale.getAftersaleNo());
        return StuckResult.FAILED_OVER;
    }

    /**
     * 匹配支付域退款单：金额 + 退款类型一致，且创建时间不早于审核时间（排除同订单其他商品的等额退款）
     */
    private boolean matches(Aftersale aftersale, PaymentFeignClient.RefundView refund) {
        if (refund.getRefundAmount() == null
                || refund.getRefundAmount().compareTo(aftersale.getRefundAmount()) != 0) {
            return false;
        }
        if (refund.getRefundType() != null && aftersale.getType() != null
                && !refund.getRefundType().equals(aftersale.getType())) {
            return false;
        }
        if (refund.getCreatedAt() == null || aftersale.getAuditedAt() == null) {
            return true;
        }
        try {
            LocalDateTime createdAt = LocalDateTime.parse(refund.getCreatedAt(), PAYMENT_TIME_FORMATTER);
            return !createdAt.isBefore(aftersale.getAuditedAt().minusMinutes(1));
        } catch (Exception e) {
            // 时间解析失败不阻断匹配（金额+类型已足够）
            return true;
        }
    }

    // ==================== 内部（审核/退款执行） ====================

    /**
     * 审核：同意则立即发起退款（支付域）+ 库存回补（库存域）
     */
    public Aftersale audit(String aftersaleNo, boolean approve, String rejectReason) {
        Aftersale aftersale = mustGet(aftersaleNo);
        if (aftersale.getStatus() != Aftersale.STATUS_APPLIED) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "售后单不在待审核状态");
        }
        if (!approve) {
            int affected = aftersaleRepository.updateOnAudit(
                    aftersale.getId(), Aftersale.STATUS_APPLIED, Aftersale.STATUS_REJECTED, rejectReason);
            if (affected == 0) {
                throw new BizException(ResultCode.IDEMPOTENT_REJECT, "售后单状态已变化，请刷新后重试");
            }
            log.info("[售后] 已驳回: aftersaleNo={}, reason={}", aftersaleNo, rejectReason);
            return aftersaleRepository.findByNo(aftersaleNo);
        }

        // 批准前复核订单状态：订单可能已被取消/全额退款（走既有退款链路），此时售后应自动驳回，
        // 避免在"钱已经退过"的订单上重复动作（支付域虽会兜底拒绝，但业务语义应明确）
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, aftersale.getUserId())
                .eq(Order::getId, aftersale.getOrderId()));
        if (order == null || order.getStatus() == null || order.getStatus() < 1 || order.getStatus() > 3) {
            String reason = order == null ? "订单不存在" : "订单状态已变化(status=" + order.getStatus() + ")";
            int rejected = aftersaleRepository.updateOnAudit(
                    aftersale.getId(), Aftersale.STATUS_APPLIED, Aftersale.STATUS_REJECTED, "自动驳回: " + reason);
            if (rejected == 0) {
                throw new BizException(ResultCode.IDEMPOTENT_REJECT, "售后单状态已变化，请刷新后重试");
            }
            log.warn("[售后] 审核时订单状态不允许售后, 自动驳回: aftersaleNo={}, {}", aftersaleNo, reason);
            return aftersaleRepository.findByNo(aftersaleNo);
        }

        // 额度复核：并发下同一 SKU 可能同时存在"仅退款/退货退款"两张待审单，
        // 申请时都通过了校验；审核时以最新占用为准再判一次，超限自动驳回（避免走到支付域才失败）
        SkuEntitlement entitlement = entitlementByOrder(aftersale.getUserId(), aftersale.getOrderId(), aftersale.getSkuId());
        if (entitlement == null
                || entitlement.occupiedAmount().add(aftersale.getRefundAmount())
                        .compareTo(entitlement.refundableTotal()) > 0) {
            String reason = entitlement == null ? "订单商品已变化" : "可退额度已被其他售后单占用";
            int rejected = aftersaleRepository.updateOnAudit(
                    aftersale.getId(), Aftersale.STATUS_APPLIED, Aftersale.STATUS_REJECTED, "自动驳回: " + reason);
            if (rejected == 0) {
                throw new BizException(ResultCode.IDEMPOTENT_REJECT, "售后单状态已变化，请刷新后重试");
            }
            log.warn("[售后] 审核复核可退额度不足, 自动驳回: aftersaleNo={}, {}", aftersaleNo, reason);
            return aftersaleRepository.findByNo(aftersaleNo);
        }

        int affected = aftersaleRepository.updateOnAudit(
                aftersale.getId(), Aftersale.STATUS_APPLIED, Aftersale.STATUS_REFUNDING, null);
        if (affected == 0) {
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "售后单状态已变化，请刷新后重试");
        }
        return executeRefund(aftersaleNo);
    }

    /** 退款失败后重试（运维/内部调用） */
    public Aftersale retryRefund(String aftersaleNo) {
        Aftersale aftersale = mustGet(aftersaleNo);
        if (aftersale.getStatus() != Aftersale.STATUS_REFUND_FAILED) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "仅退款失败的售后单可以重试");
        }
        int affected = aftersaleRepository.updateOnRetry(aftersale.getId());
        if (affected == 0) {
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "售后单状态已变化，请刷新后重试");
        }
        return executeRefund(aftersaleNo);
    }

    /**
     * 执行退款：支付域受理成功后回补库存并置完成
     * <p>
     * 退款失败保持"退款失败"状态，由人工/定时任务重试；不做自动无限重试，避免放大故障。
     * </p>
     */
    private Aftersale executeRefund(String aftersaleNo) {
        Aftersale aftersale = mustGet(aftersaleNo);
        String refundNo;
        try {
            R<String> resp = paymentFeignClient.refundByOrder(
                    new PaymentFeignClient.RefundByOrderRequest(aftersale.getOrderId(), aftersale.getRefundAmount(),
                            aftersale.getReason(), aftersale.getType()),
                    aftersale.getUserId());
            if (resp == null || !resp.isSuccess()) {
                markRefundFailed(aftersale, "支付域拒绝退款: " + (resp == null ? "无响应" : resp.getMessage()));
                return aftersaleRepository.findByNo(aftersaleNo);
            }
            // 支付域回传退款单号 → 精确归属；未回传时回查兜底
            refundNo = resp.getData() != null ? resp.getData() : attributeRefund(aftersale);
        } catch (Exception e) {
            markRefundFailed(aftersale, "退款调用异常: " + e.getMessage());
            return aftersaleRepository.findByNo(aftersaleNo);
        }

        restoreStock(aftersale);
        int affected = aftersaleRepository.updateOnRefundSuccess(
                aftersale.getId(), Aftersale.STATUS_REFUNDING, Aftersale.STATUS_FINISHED, refundNo);
        log.info("[售后] 退款完成: aftersaleNo={}, refundAmount={}, refundNo={}, statusUpdate={}",
                aftersaleNo, aftersale.getRefundAmount(), refundNo, affected);
        return aftersaleRepository.findByNo(aftersaleNo);
    }

    /**
     * 退款受理成功后，从支付域回查并"认领"本次退款单号（归属到售后单）
     * <p>
     * 目的：让每个成功退款单在订单域有唯一归属，卡死恢复时可按退款单号精确匹配，
     * 避免同订单等额退款被张冠李戴。回查失败不影响主流程（归属留空，恢复时退化为保守判断）。
     * </p>
     */
    private String attributeRefund(Aftersale aftersale) {
        try {
            R<List<PaymentFeignClient.RefundView>> resp = paymentFeignClient.listRefunds(aftersale.getOrderId());
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                return null;
            }
            java.util.Set<String> used = aftersaleRepository.usedRefundNos(aftersale.getOrderId());
            String candidate = null;
            for (PaymentFeignClient.RefundView refund : resp.getData()) {
                if (refund.getRefundNo() == null || used.contains(refund.getRefundNo())) {
                    continue;
                }
                if (refund.getStatus() != null && refund.getStatus() == 1 && matches(aftersale, refund)) {
                    candidate = refund.getRefundNo();
                }
            }
            return candidate;
        } catch (Exception e) {
            log.warn("[售后] 退款单号归属回查失败(不影响完成): aftersaleNo={}, cause={}",
                    aftersale.getAftersaleNo(), e.getMessage());
            return null;
        }
    }

    private void markRefundFailed(Aftersale aftersale, String message) {
        businessMetrics.recordAftersaleRefund(false);
        aftersaleRepository.updateOnRefundFail(aftersale.getId(), Aftersale.STATUS_REFUNDING, Aftersale.STATUS_REFUND_FAILED);
        log.error("[售后] 退款失败: aftersaleNo={}, orderId={}, refundAmount={}, cause={}",
                aftersale.getAftersaleNo(), aftersale.getOrderId(), aftersale.getRefundAmount(), message);
    }

    /**
     * 库存回补（按 SKU）：库存域以 orderId+skuId 幂等，与全额退款链路重复调用安全
     */
    private boolean restoreStock(Aftersale aftersale) {
        // 时点说明：当前在"退款受理成功"后回补库存，对 type=1（未发货/协商退款）语义正确；
        // 对 type=2（退货退款）真实系统应在"退货入库质检通过"后回补，本实现按简化口径在退款受理时回补，
        // 由库存累计正增量语义保证不重复回补（如需严格时点，可在此处按 type 分流并新增入库确认步骤）。
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("orderId", aftersale.getOrderId());
            request.put("userId", aftersale.getUserId());
            request.put("skuId", aftersale.getSkuId());
            request.put("quantity", aftersale.getApplyQuantity());
            R<Void> resp = inventoryFeignClient.refundRestore(request);
            if (resp == null || !resp.isSuccess()) {
                log.error("[售后] 库存回补未成功(待恢复任务重试): aftersaleNo={}, resp={}",
                        aftersale.getAftersaleNo(), resp == null ? "无响应" : resp.getMessage());
                return false;
            }
            return true;
        } catch (Exception e) {
            // 跨服务失败（库存域整体不可用等）：库存域内部补偿表不会留下记录，
            // 由售后单的 restock_status=0 + 恢复任务兜底重试（库存域按 orderId+skuId 累计幂等）
            log.error("[售后] 库存回补异常(待恢复任务重试): aftersaleNo={}", aftersale.getAftersaleNo(), e);
            return false;
        }
    }

    /**
     * SKU 维度权益快照：分摊优惠、整件可退金额、已占用金额、已退数量
     */
    private record SkuEntitlement(OrderItem item, BigDecimal discountShare, BigDecimal refundableTotal,
                                  BigDecimal occupiedAmount, int refundedQuantity) {
    }

    private SkuEntitlement entitlement(Order order, List<OrderItem> items, OrderItem item) {
        Map<Long, BigDecimal> allocation = DiscountAllocator.allocateByItems(items, order.getDiscountAmount());
        BigDecimal share = allocation.getOrDefault(item.getId(), BigDecimal.ZERO).setScale(2, RoundingMode.DOWN);
        BigDecimal refundableTotal = DiscountAllocator.refundableAmount(item, share, item.getQuantity());
        BigDecimal occupied = aftersaleRepository.sumRefundedAmountBySku(order.getId(), item.getSkuId());
        int refundedQuantity = aftersaleRepository.sumRefundedQuantityBySku(order.getId(), item.getSkuId());
        return new SkuEntitlement(item, share, refundableTotal, occupied, refundedQuantity);
    }

    /**
     * 按 (用户, 订单, SKU) 重新计算权益快照（审核复核用）
     */
    private SkuEntitlement entitlementByOrder(Long userId, Long orderId, Long skuId) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId).eq(Order::getId, orderId));
        if (order == null) {
            return null;
        }
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, orderId).eq(OrderItem::getUserId, userId));
        OrderItem item = items.stream()
                .filter(i -> i.getSkuId() != null && i.getSkuId().equals(skuId))
                .findFirst().orElse(null);
        return item == null ? null : entitlement(order, items, item);
    }

    /**
     * 本次应退金额
     * <p>
     * 未退满：按数量比例向下取整（永不超退）；
     * 本次退满：用"整件可退 − 已退金额"补齐差额，保证多次部分退款累计严格等于整件可退金额
     * （否则每次向下取整会让用户累计少拿几分，与对账口径也对不上）。
     * </p>
     */
    private BigDecimal resolveRefundable(SkuEntitlement entitlement, int quantity) {
        Integer itemQuantity = entitlement.item().getQuantity();
        if (itemQuantity != null && entitlement.refundedQuantity() + quantity >= itemQuantity) {
            return entitlement.refundableTotal().subtract(entitlement.occupiedAmount());
        }
        return DiscountAllocator.refundableAmount(entitlement.item(), entitlement.discountShare(), quantity);
    }

    private Aftersale mustGet(String aftersaleNo) {
        Aftersale aftersale = aftersaleRepository.findByNo(aftersaleNo);
        if (aftersale == null) {
            throw new BizException(ResultCode.NOT_FOUND, "售后单不存在");
        }
        return aftersale;
    }

    private Aftersale buildEntity(Order order, OrderItem item, int type, int quantity,
                                  BigDecimal share, BigDecimal refundable, AftersaleApplyRequest request) {
        Aftersale entity = new Aftersale();
        entity.setOrderId(order.getId());
        entity.setOrderNo(order.getOrderNo());
        entity.setUserId(order.getUserId());
        entity.setSkuId(item.getSkuId());
        entity.setSkuName(item.getSkuName());
        entity.setType(type);
        entity.setApplyQuantity(quantity);
        // 申请数量对应的商品金额 = 单价 × 数量（明细总价已含数量，这里按申请数量重算）
        BigDecimal unitPrice = item.getPrice() != null ? item.getPrice() : BigDecimal.ZERO;
        entity.setItemAmount(unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.DOWN));
        entity.setDiscountShare(share);
        entity.setRefundAmount(refundable);
        entity.setReason(request.getReason());
        entity.setReturnWaybill(request.getReturnWaybill());
        return entity;
    }
}
