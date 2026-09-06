package com.myxhs.coupon.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.coupon.dto.request.*;
import com.myxhs.coupon.dto.response.CouponTemplateVO;
import com.myxhs.coupon.dto.response.UserCouponVO;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.service.CouponService;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 优惠券接口
 * <p>
 * 提供券模板管理、领券、用券、退券、查询等接口。
 * 用券/退券接口由订单服务内部 Feign 调用。
 * </p>
 */
@RestController
@RequestMapping("/api/coupon")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class CouponController {

    private final CouponService couponService;
    private final AccessTokenGuard accessTokenGuard;

    @PostConstruct
    public void validateTokens() {
        accessTokenGuard.requireInternalTokenConfigured("coupon");
        accessTokenGuard.requireAdminTokenConfigured("coupon");
    }

    // ==================== 券模板管理（管理端） ====================

    /**
     * 创建优惠券模板
     * <p>
     * T-129（2026-08-16）：补 @Idempotent 10s——修复前同参数快速重发创建多个重复模板
     * （G4 登记观察项，对照 product createSpu 的 10s 幂等）
     * </p>
     */
    @PostMapping("/template")
    @RateLimit(prefix = "myxhs:coupon:createTpl", maxRequests = 5, windowSeconds = 60)
    @com.myxhs.common.annotation.Idempotent(key = "'template:create:' + #request.name + ':' + #request.type", expireSeconds = 10)
    public R<CouponTemplateVO> createTemplate(
            @Valid @RequestBody CreateTemplateRequest request,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) return R.fail(403, "无权访问管理接口");
        return R.ok(toTemplateVO(couponService.createTemplate(request)));
    }

    /**
     * 修改券模板状态（上线/下线）
     */
    @PutMapping("/template/{id}/status")
    public R<Void> updateTemplateStatus(
            @Positive @PathVariable Long id, @NotNull @RequestParam Integer status,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) return R.fail(403, "无权访问管理接口");
        couponService.updateTemplateStatus(id, status);
        return R.ok();
    }

    /**
     * 查询券模板详情
     */
    @GetMapping("/template/{id}")
    public R<CouponTemplateVO> getTemplate(@PathVariable Long id) {
        return R.ok(toTemplateVO(couponService.getTemplate(id)));
    }

    /**
     * 可领券模板列表（领券中心公开接口）
     * <p>
     * 返回当前可领取的券模板（上架、未删除、剩余>0、在有效期内）。
     * </p>
     */
    @GetMapping("/template/list")
    public R<List<CouponTemplateVO>> listClaimableTemplates() {
        return R.ok(couponService.listClaimableTemplates().stream()
                .map(this::toTemplateVO)
                .collect(Collectors.toList()));
    }

    // ==================== 用户端 ====================

    /**
     * 领取优惠券
     * <p>
     * 需要登录。userId 从 Token 中提取（这里暂用 Header 模拟）。
     * </p>
     */
    @PostMapping("/claim")
    @RateLimit(prefix = "myxhs:coupon:claim", maxRequests = 5, windowSeconds = 60, perUser = true,
               message = "领券频率过高，请稍后再试")
    public R<Void> claimCoupon(@RequestHeader("X-User-Id") Long userId,
                               @Valid @RequestBody ClaimCouponRequest request) {
        couponService.claimCoupon(userId, request);
        return R.ok();
    }

    /**
     * 我的优惠券列表
     */
    @GetMapping("/user/list")
    public R<List<UserCouponVO>> getUserCoupons(@RequestHeader("X-User-Id") Long userId,
                                                @RequestParam(required = false) Integer status) {
        return R.ok(couponService.getUserCoupons(userId, status));
    }

    /**
     * 可用优惠券（下单时展示）
     */
    @GetMapping("/user/available")
    public R<List<UserCouponVO>> getAvailableCoupons(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(couponService.getAvailableCoupons(userId));
    }

    // ==================== 内部接口（Order Feign 调用） ====================

    /**
     * 查询优惠券折扣（下单前调用，不核销——仅计算折扣金额）
     * <p>内部接口，需 X-Internal-Call 校验</p>
     */
    @GetMapping("/discount/{id}")
    public R<java.math.BigDecimal> getCouponDiscount(
            @PathVariable Long id,
            @RequestParam java.math.BigDecimal orderAmount,
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) return R.fail(403, "仅限内部服务调用");
        return R.ok(couponService.getCouponDiscount(userId, id, orderAmount));
    }

    /**
     * 使用优惠券（下单时调用，内部接口，需 X-Internal-Call 校验）
     */
    @PostMapping("/use")
    public R<java.math.BigDecimal> useCoupon(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody UseCouponRequest request,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) return R.fail(403, "仅限内部服务调用");
        java.math.BigDecimal discount = couponService.useCoupon(userId, request);
        return R.ok(discount);
    }

    /**
     * 退回优惠券（取消订单时调用，内部接口）
     */
    @PostMapping("/return")
    public R<Void> returnCoupon(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody ReturnCouponRequest request,
            @RequestHeader(value = "X-Internal-Call", required = false) String internalCall) {
        if (!accessTokenGuard.isInternalCall(internalCall)) return R.fail(403, "仅限内部服务调用");
        couponService.returnCoupon(userId, request);
        return R.ok();
    }

    // ==================== VO 转换 ====================

    private CouponTemplateVO toTemplateVO(CouponTemplate template) {
        return CouponTemplateVO.builder()
                .id(template.getId())
                .name(template.getName())
                .type(template.getType())
                .discountValue(template.getDiscountValue())
                .minAmount(template.getMinAmount())
                .totalCount(template.getTotalCount())
                .remainCount(template.getRemainCount())
                .perUserLimit(template.getPerUserLimit())
                .validStart(template.getValidStart())
                .validEnd(template.getValidEnd())
                .status(template.getStatus())
                .build();
    }
}
