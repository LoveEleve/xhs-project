package com.myxhs.order.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.order.dto.request.AftersaleApplyRequest;
import com.myxhs.order.dto.request.AftersaleAuditRequest;
import com.myxhs.order.entity.Aftersale;
import com.myxhs.order.service.AftersaleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 售后接口（仅退款 / 退货退款）
 * <p>
 * 用户面走 X-User-Id（网关注入）；审核与退款重试为内部端点，要求 X-Internal-Call（fail-closed）。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/order/aftersale")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class AftersaleController {

    private final AftersaleService aftersaleService;
    private final AccessTokenGuard accessTokenGuard;

    /**
     * 申请售后（应退金额按优惠分摊计算，见 DiscountAllocator）
     */
    @PostMapping("/apply")
    @RateLimit(prefix = "myxhs:order:aftersale:apply", maxRequests = 5, windowSeconds = 60, perUser = true,
               message = "售后申请过于频繁，请稍后再试")
    public R<Aftersale> apply(@RequestHeader("X-User-Id") Long userId,
                              @Valid @RequestBody AftersaleApplyRequest request) {
        return R.ok(aftersaleService.apply(userId, request));
    }

    /**
     * 我的售后单列表
     */
    @GetMapping("/list")
    public R<List<Aftersale>> list(@RequestHeader("X-User-Id") Long userId,
                                   @RequestParam(value = "limit", required = false) Integer limit) {
        return R.ok(aftersaleService.list(userId, limit));
    }

    /**
     * 售后单详情（含归属校验）
     */
    @GetMapping("/{aftersaleNo}")
    public R<Aftersale> detail(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable("aftersaleNo") String aftersaleNo) {
        return R.ok(aftersaleService.get(userId, aftersaleNo));
    }

    /**
     * 撤销申请（仅待审核可撤）
     */
    @PostMapping("/{aftersaleNo}/cancel")
    @RateLimit(prefix = "myxhs:order:aftersale:cancel", maxRequests = 10, windowSeconds = 60, perUser = true,
               message = "操作过于频繁，请稍后再试")
    public R<Aftersale> cancel(@RequestHeader("X-User-Id") Long userId,
                               @PathVariable("aftersaleNo") String aftersaleNo) {
        return R.ok(aftersaleService.cancel(userId, aftersaleNo));
    }

    /**
     * 审核（内部）：同意即发起退款（支付域）+ 库存回补（库存域）
     */
    @PostMapping("/audit")
    public R<Aftersale> audit(@Valid @RequestBody AftersaleAuditRequest request,
                              @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        boolean approve = Boolean.TRUE.equals(request.getApprove());
        return R.ok(aftersaleService.audit(request.getAftersaleNo(), approve, request.getRejectReason()));
    }

    /**
     * 退款失败重试（内部）
     */
    @PostMapping("/{aftersaleNo}/retry-refund")
    public R<Aftersale> retryRefund(@PathVariable("aftersaleNo") String aftersaleNo,
                                    @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) {
            return R.fail(403, "仅限内部服务调用");
        }
        return R.ok(aftersaleService.retryRefund(aftersaleNo));
    }
}
