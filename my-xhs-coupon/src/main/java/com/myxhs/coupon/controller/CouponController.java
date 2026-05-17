package com.myxhs.coupon.controller;

import com.myxhs.common.response.R;
import com.myxhs.coupon.dto.request.*;
import com.myxhs.coupon.dto.response.UserCouponVO;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
public class CouponController {

    private final CouponService couponService;

    // ==================== 券模板管理（管理端） ====================

    /**
     * 创建优惠券模板
     */
    @PostMapping("/template")
    public R<CouponTemplate> createTemplate(@Valid @RequestBody CreateTemplateRequest request) {
        return R.ok(couponService.createTemplate(request));
    }

    /**
     * 修改券模板状态（上线/下线）
     */
    @PutMapping("/template/{id}/status")
    public R<Void> updateTemplateStatus(@PathVariable Long id, @RequestParam Integer status) {
        couponService.updateTemplateStatus(id, status);
        return R.ok();
    }

    /**
     * 查询券模板详情
     */
    @GetMapping("/template/{id}")
    public R<CouponTemplate> getTemplate(@PathVariable Long id) {
        return R.ok(couponService.getTemplate(id));
    }

    // ==================== 用户端 ====================

    /**
     * 领取优惠券
     * <p>
     * 需要登录。userId 从 Token 中提取（这里暂用 Header 模拟）。
     * </p>
     */
    @PostMapping("/claim")
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
     * 使用优惠券（下单时调用）
     */
    @PostMapping("/use")
    public R<Void> useCoupon(@RequestHeader("X-User-Id") Long userId,
                             @Valid @RequestBody UseCouponRequest request) {
        couponService.useCoupon(userId, request);
        return R.ok();
    }

    /**
     * 退回优惠券（取消订单时调用）
     */
    @PostMapping("/return")
    public R<Void> returnCoupon(@RequestHeader("X-User-Id") Long userId,
                                @Valid @RequestBody ReturnCouponRequest request) {
        couponService.returnCoupon(userId, request);
        return R.ok();
    }
}
