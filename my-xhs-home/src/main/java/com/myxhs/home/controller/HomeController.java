package com.myxhs.home.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.home.dto.*;
import com.myxhs.home.exception.DownstreamUnavailableException;
import com.myxhs.home.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 首页聚合接口（BFF 层）
 * <p>
 * 核心职责：将多个下游微服务的数据并行聚合，返回前端所需的完整 VO。
 * 所有聚合接口均有降级策略：下游服务不可用时，对应字段返回默认值。
 * </p>
 * <p>
 * 异步化：聚合 API 返回 {@link CompletableFuture}，由 Spring MVC 在 Servlet 异步上下文中执行，
 * 释放 Tomcat 线程等待下游服务响应。聚合逻辑本身已在 Service 层通过 CompletableFuture 并行编排，
 * Controller 层只负责将同步调用包装到线程池中提交。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/home")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class HomeController {

    private final FeedService feedService;
    private final NoteAggService noteAggService;
    private final ProductAggService productAggService;
    private final UserProfileAggService userProfileAggService;
    private final CartAggService cartAggService;

    @Qualifier("aggregatorPool")
    private final ExecutorService aggregatorPool;

    // ==================== Feed 流 ====================

    /**
     * 关注 Feed 流（推拉混合 + 游标分页）
     * <p>
     * 首次请求不传 lastScore（或传 0），返回最新的笔记。
     * 翻页时传上一页返回的 nextCursor 作为 lastScore。
     * </p>
     */
    @GetMapping("/feed")
    public CompletableFuture<R<FeedVO>> getFollowFeed(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) Double lastScore,
            @RequestParam(defaultValue = "20") int size) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return R.ok(feedService.getFollowFeed(userId, lastScore, size));
            } catch (DownstreamUnavailableException e) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, e.getMessage());
            }
        }, aggregatorPool);
    }

    // ==================== 笔记详情聚合 ====================

    /**
     * 笔记详情聚合接口
     * <p>
     * 聚合数据源：笔记详情 + 作者信息 + 计数 + 社交状态 + 热门评论
     * 未登录用户：社交状态（点赞/收藏/关注）全部为 false
     * </p>
     */
    @GetMapping("/note/{noteId}")
    public CompletableFuture<R<NoteDetailAggVO>> getNoteDetail(
            @PathVariable Long noteId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                NoteDetailAggVO result = noteAggService.getNoteDetail(noteId, userId);
                if (result == null) {
                    return R.fail(404, "笔记不存在");
                }
                return R.ok(result);
            } catch (DownstreamUnavailableException e) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, e.getMessage());
            }
        }, aggregatorPool);
    }

    // ==================== 商品详情聚合 ====================

    /**
     * 商品详情聚合接口
     * <p>
     * 聚合数据源：SPU 详情 + SKU 列表 + 各 SKU 库存 + 商品计数
     * 公开接口，无需登录。
     * </p>
     */
    @GetMapping("/product/{spuId}")
    public CompletableFuture<R<ProductDetailAggVO>> getProductDetail(@PathVariable Long spuId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProductDetailAggVO result = productAggService.getProductDetail(spuId);
                if (result == null) {
                    return R.fail(404, "商品不存在");
                }
                return R.ok(result);
            } catch (DownstreamUnavailableException e) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, e.getMessage());
            }
        }, aggregatorPool);
    }

    // ==================== 用户主页聚合 ====================

    /**
     * 用户主页聚合接口
     * <p>
     * 聚合数据源：用户信息 + 计数 + 社交关系 + 用户笔记列表
     * 未登录用户：社交关系全部为 false
     * </p>
     */
    @GetMapping("/user/{targetUserId}")
    public CompletableFuture<R<UserProfileAggVO>> getUserProfile(
            @PathVariable Long targetUserId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                UserProfileAggVO result = userProfileAggService.getUserProfile(targetUserId, userId);
                if (result == null) {
                    return R.fail(404, "用户不存在");
                }
                return R.ok(result);
            } catch (DownstreamUnavailableException e) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, e.getMessage());
            }
        }, aggregatorPool);
    }

    // ==================== 购物车聚合 ====================

    /**
     * 购物车聚合接口
     * <p>
     * 聚合数据源：购物车列表 + 各 SKU 库存 + 可用优惠券
     * 需要登录。
     * </p>
     */
    @GetMapping("/cart")
    public CompletableFuture<R<CartAggVO>> getCartAgg(@RequestHeader("X-User-Id") Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return R.ok(cartAggService.getCartAgg(userId));
            } catch (DownstreamUnavailableException e) {
                return R.fail(ResultCode.SERVICE_UNAVAILABLE, e.getMessage());
            }
        }, aggregatorPool);
    }
}
