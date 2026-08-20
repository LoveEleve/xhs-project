package com.myxhs.analytics.controller;

import com.myxhs.analytics.dto.request.LikeRequest;
import com.myxhs.analytics.service.LikeService;
import com.myxhs.common.annotation.Idempotent;
import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 点赞接口
 * <p>
 * 提供点赞/取消点赞、查询点赞状态、批量查询点赞状态等功能。
 * 所有接口均需登录（通过 X-User-Id Header 获取用户 ID）。
 * </p>
 */
@RestController
@RequestMapping("/api/social/like")
@RequiredArgsConstructor
@Validated
public class LikeController {

    private final LikeService likeService;

    /**
     * 点赞（笔记/评论）
     * <p>
     * SADD 天然幂等：重复点赞直接返回成功，不报错。
     * 限流：每用户每分钟最多 30 次点赞操作。
     * </p>
     */
    @PostMapping
    @RateLimit(windowSeconds = 60, maxRequests = 30, perUser = true, prefix = "social:like",
            message = "操作过于频繁，请稍后重试")
    @Idempotent(key = "'like:' + #userId + ':' + #request.bizType + ':' + #request.bizId",
            expireSeconds = 5, message = "请勿重复点赞")
    public R<Void> like(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody LikeRequest request) {
        likeService.like(userId, request);
        return R.ok("点赞成功", null);
    }

    /**
     * 取消点赞
     */
    @DeleteMapping
    @RateLimit(windowSeconds = 60, maxRequests = 30, perUser = true, prefix = "social:unlike",
            message = "操作过于频繁，请稍后重试")
    @Idempotent(key = "'unlike:' + #userId + ':' + #request.bizType + ':' + #request.bizId",
            expireSeconds = 5, message = "请勿重复操作")
    public R<Void> unlike(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody LikeRequest request) {
        likeService.unlike(userId, request);
        return R.ok("取消点赞成功", null);
    }

    /**
     * 查询点赞状态
     *
     * @param bizType 业务类型：1-笔记 2-评论
     * @param bizId   业务ID
     */
    @GetMapping("/status")
    public R<Boolean> checkLikeStatus(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam int bizType,
            @RequestParam Long bizId) {
        return R.ok(likeService.isLiked(userId, bizType, bizId));
    }

    /**
     * 批量查询点赞状态（列表页优化）
     * <p>
     * Pipeline 一次网络往返完成 N 次 SISMEMBER 查询。
     * </p>
     *
     * @param bizType 业务类型：1-笔记 2-评论
     * @param bizIds  业务ID列表（逗号分隔）
     */
    @GetMapping("/batch-status")
    public R<Map<Long, Boolean>> batchCheckLikeStatus(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam @Min(1) @Max(2) int bizType,
            @RequestParam String bizIds) {
        List<Long> idList = Stream.of(bizIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> { try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; } })
                .filter(id -> id != null)
                .collect(Collectors.toList());
        return R.ok(likeService.batchCheckLikeStatus(userId, bizType, idList));
    }

    /**
     * 获取点赞数
     *
     * @param bizType 业务类型：1-笔记 2-评论
     * @param bizId   业务ID
     */
    @GetMapping("/count")
    public R<Long> getLikeCount(
            @RequestParam int bizType,
            @RequestParam Long bizId) {
        return R.ok(likeService.getLikeCount(bizType, bizId));
    }
}
