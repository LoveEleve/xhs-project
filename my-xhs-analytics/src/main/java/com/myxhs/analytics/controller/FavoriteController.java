package com.myxhs.analytics.controller;

import com.myxhs.analytics.dto.request.FavoriteRequest;
import com.myxhs.analytics.service.FavoriteService;
import com.myxhs.common.annotation.Idempotent;
import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 收藏接口
 * <p>
 * 提供收藏/取消收藏、查询收藏状态、收藏列表等功能。
 * 所有接口均需登录（通过 X-User-Id Header 获取用户 ID）。
 * </p>
 */
@RestController
@RequestMapping("/api/social/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * 收藏笔记
     * <p>
     * ZSet ZSCORE 判断幂等：重复收藏直接返回成功。
     * 限流：每用户每分钟最多 30 次收藏操作。
     * @Idempotent：5秒内相同请求直接拦截，防止网络抖动重复提交。
     * </p>
     */
    @PostMapping
    @RateLimit(windowSeconds = 60, maxRequests = 30, perUser = true, prefix = "social:favorite",
            message = "操作过于频繁，请稍后重试")
    @Idempotent(key = "'favorite:' + #userId + ':' + #request.noteId",
            expireSeconds = 5, message = "请勿重复收藏")
    public R<Void> favorite(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody FavoriteRequest request) {
        favoriteService.favorite(userId, request.getNoteId());
        return R.ok("收藏成功", null);
    }

    /**
     * 取消收藏
     */
    @DeleteMapping
    @RateLimit(windowSeconds = 60, maxRequests = 30, perUser = true, prefix = "social:unfavorite",
            message = "操作过于频繁，请稍后重试")
    public R<Void> unfavorite(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody FavoriteRequest request) {
        favoriteService.unfavorite(userId, request.getNoteId());
        return R.ok("取消收藏成功", null);
    }

    /**
     * 查询收藏状态
     *
     * @param noteId 笔记ID
     */
    @GetMapping("/status")
    public R<Boolean> checkFavoriteStatus(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam Long noteId) {
        return R.ok(favoriteService.isFavorited(userId, noteId));
    }

    /**
     * 收藏列表（按收藏时间倒序）
     *
     * @param page 页码（默认1）
     * @param size 每页条数（默认20，最大50）
     */
    @GetMapping("/list")
    public R<Map<String, Object>> getFavoriteList(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Long> noteIds = favoriteService.getFavoriteList(userId, page, size);
        long total = favoriteService.getFavoriteCount(userId);
        return R.ok(Map.of("total", total, "list", noteIds));
    }
}
