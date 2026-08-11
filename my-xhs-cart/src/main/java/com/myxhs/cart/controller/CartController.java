package com.myxhs.cart.controller;

import com.myxhs.cart.dto.request.CartAddRequest;
import com.myxhs.cart.dto.request.CartCheckRequest;
import com.myxhs.cart.dto.request.CartMergeRequest;
import com.myxhs.cart.dto.request.CartUpdateQuantityRequest;
import com.myxhs.cart.dto.response.CartListVO;
import com.myxhs.cart.job.CartReconcileJob;
import com.myxhs.cart.service.CartService;
import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 购物车接口
 * <p>
 * 所有接口需登录（Gateway 注入 X-User-Id Header）。
 * 购物车操作以 Redis 为权威数据源，MQ 异步持久化到 MySQL。
 * </p>
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final CartReconcileJob cartReconcileJob;

    /**
     * 加入购物车
     */
    @PostMapping("/add")
    @RateLimit(prefix = "myxhs:cart:add", maxRequests = 20, windowSeconds = 60, perUser = true)
    public R<Void> addToCart(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CartAddRequest request) {
        cartService.addToCart(userId, request);
        return R.ok();
    }

    /**
     * 修改数量
     */
    @PutMapping("/quantity")
    @RateLimit(prefix = "myxhs:cart:update", maxRequests = 20, windowSeconds = 60, perUser = true)
    public R<Void> updateQuantity(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CartUpdateQuantityRequest request) {
        cartService.updateQuantity(userId, request);
        return R.ok();
    }

    /**
     * 删除商品
     */
    @DeleteMapping("/{skuId}")
    @RateLimit(prefix = "myxhs:cart:remove", maxRequests = 20, windowSeconds = 60, perUser = true)
    public R<Void> removeFromCart(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long skuId) {
        cartService.removeFromCart(userId, skuId);
        return R.ok();
    }

    /**
     * 勾选/取消勾选
     */
    @PutMapping("/check")
    @RateLimit(prefix = "myxhs:cart:check", maxRequests = 30, windowSeconds = 60, perUser = true)
    public R<Void> checkItem(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CartCheckRequest request) {
        cartService.checkItem(userId, request);
        return R.ok();
    }

    /**
     * 全选/取消全选
     */
    @PutMapping("/check-all")
    @RateLimit(prefix = "myxhs:cart:checkAll", maxRequests = 10, windowSeconds = 60, perUser = true)
    public R<Void> checkAll(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam boolean checked) {
        cartService.checkAll(userId, checked);
        return R.ok();
    }

    /**
     * 购物车列表
     */
    @GetMapping("/list")
    @RateLimit(prefix = "myxhs:cart:list", maxRequests = 60, windowSeconds = 60, perUser = true,
            message = "查询过于频繁，请稍后重试")
    public R<CartListVO> getCartList(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(cartService.getCartList(userId));
    }

    /**
     * 匿名购物车合并（登录时调用）
     */
    @PostMapping("/merge")
    @RateLimit(prefix = "myxhs:cart:merge", maxRequests = 10, windowSeconds = 60, perUser = true,
            message = "合并操作过于频繁，请稍后重试")
    public R<Void> mergeAnonymousCart(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CartMergeRequest request) {
        cartService.mergeAnonymousCart(userId, request);
        return R.ok();
    }

    /**
     * 清空购物车（破坏性操作，严控频率）
     */
    @DeleteMapping("/clear")
    @RateLimit(prefix = "myxhs:cart:clear", maxRequests = 3, windowSeconds = 60, perUser = true)
    public R<Void> clearCart(@RequestHeader("X-User-Id") Long userId) {
        cartService.clearCart(userId);
        return R.ok();
    }

    /**
     * 获取购物车商品数量（角标用）
     */
    @GetMapping("/count")
    @RateLimit(prefix = "myxhs:cart:count", maxRequests = 120, windowSeconds = 60, perUser = true,
            message = "查询过于频繁，请稍后重试")
    public R<Map<String, Integer>> getCartCount(@RequestHeader("X-User-Id") Long userId) {
        int count = cartService.getCartCount(userId);
        return R.ok(Map.of("count", count));
    }

    // ==================== 管理接口 ====================

    /** 管理接口令牌（配置化管理，不再硬编码） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.admin.token}")
    private String adminToken;

    private boolean isAdminCall(String headerValue) {
        return adminToken != null && !adminToken.isEmpty() && adminToken.equals(headerValue);
    }

    /** 全量对账专用线程池（单线程串行执行，避免阻塞 ForkJoinPool.commonPool 影响全 JVM） */
    /** 【O2修复】MdcAwareExecutorService 包装，对账异步日志携带 traceId */
    private static final java.util.concurrent.ExecutorService RECONCILE_EXECUTOR =
            new com.myxhs.common.trace.MdcAwareExecutorService(
                    java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "cart-reconcile");
                        t.setDaemon(true);
                        return t;
                    }));

    /**
     * 手动触发购物车对账（管理接口，需 X-Admin-Call 校验）
     */
    @PostMapping("/internal/reconcile")
    @com.myxhs.common.annotation.RateLimit(windowSeconds = 60, maxRequests = 2,
            prefix = "myxhs:cart:reconcile", message = "对账操作过于频繁，每分钟最多2次")
    public R<String> reconcile(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!isAdminCall(adminCall)) {
            return R.fail(403, "无权访问管理接口");
        }
        // C-06: 异步执行避免阻塞 Tomcat 线程（全量对账可能耗时较长）；
        // 使用专用线程池而非 ForkJoinPool.commonPool（避免长任务阻塞全 JVM 共享池）
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            cartReconcileJob.reconcile();
        }, RECONCILE_EXECUTOR);
        return R.ok("对账任务已异步触发，查看日志获取详情");
    }

    /**
     * 手动触发单用户对账（C-01: 管理接口，需 X-Admin-Call 校验）
     * <p>
     * 用于手动修复 Redis-only 用户的购物车（MySQL 零记录，定时对账不会遍历到）。
     * </p>
     */
    @PostMapping("/internal/reconcile/user")
    @com.myxhs.common.annotation.RateLimit(windowSeconds = 60, maxRequests = 10,
            prefix = "myxhs:cart:reconcile:user", message = "对账操作过于频繁")
    public R<String> reconcileUser(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall,
            @RequestParam Long userId) {
        if (!isAdminCall(adminCall)) {
            return R.fail(403, "无权访问管理接口");
        }
        int repaired = cartReconcileJob.reconcileUser(userId);
        return R.ok("对账完成，修复 " + repaired + " 条记录");
    }
}
