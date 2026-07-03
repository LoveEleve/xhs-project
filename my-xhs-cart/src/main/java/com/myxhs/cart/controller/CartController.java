package com.myxhs.cart.controller;

import com.myxhs.cart.dto.request.CartAddRequest;
import com.myxhs.cart.dto.request.CartCheckRequest;
import com.myxhs.cart.dto.request.CartMergeRequest;
import com.myxhs.cart.dto.request.CartUpdateQuantityRequest;
import com.myxhs.cart.dto.response.CartListVO;
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

    /**
     * 加入购物车
     */
    @PostMapping("/add")
    @RateLimit(prefix = "cart:add", maxRequests = 20, windowSeconds = 60, perUser = true)
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
    public R<CartListVO> getCartList(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(cartService.getCartList(userId));
    }

    /**
     * 匿名购物车合并（登录时调用）
     */
    @PostMapping("/merge")
    public R<Void> mergeAnonymousCart(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CartMergeRequest request) {
        cartService.mergeAnonymousCart(userId, request);
        return R.ok();
    }

    /**
     * 获取购物车商品数量（角标用）
     */
    @GetMapping("/count")
    public R<Map<String, Integer>> getCartCount(@RequestHeader("X-User-Id") Long userId) {
        int count = cartService.getCartCount(userId);
        return R.ok(Map.of("count", count));
    }
}
