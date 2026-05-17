package com.myxhs.analytics.controller;

import com.myxhs.analytics.dto.response.FollowVO;
import com.myxhs.analytics.service.FollowService;
import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 关注与社交关系接口
 * <p>
 * 提供关注/取关、关注列表、粉丝列表、共同关注、关注关系查询等功能。
 * 关注/取关需要登录（通过 X-User-Id Header 获取用户 ID）。
 * 关注列表/粉丝列表为公开接口，无需登录。
 * </p>
 */
@RestController
@RequestMapping("/api/social")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /**
     * 关注用户
     * <p>
     * 同一用户1分钟内最多关注20人（限流）。
     * Lua 脚本保证关注 + 计数的原子性。
     * </p>
     */
    @PostMapping("/follow/{targetUserId}")
    @RateLimit(windowSeconds = 60, maxRequests = 20, perUser = true, prefix = "social:follow",
            message = "操作过于频繁，请稍后重试")
    public R<Void> follow(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long targetUserId) {
        followService.follow(userId, targetUserId);
        return R.ok("关注成功", null);
    }

    /**
     * 取关用户
     */
    @DeleteMapping("/follow/{targetUserId}")
    public R<Void> unfollow(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long targetUserId) {
        followService.unfollow(userId, targetUserId);
        return R.ok("取关成功", null);
    }

    /**
     * 获取关注列表（公开接口）
     *
     * @param userId 用户ID
     * @param page   页码（默认1）
     * @param size   每页条数（默认20，最大50）
     */
    @GetMapping("/following/{userId}")
    public R<Map<String, Object>> getFollowingList(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<FollowVO> list = followService.getFollowingList(userId, page, size);
        long total = followService.getFollowingCount(userId);
        return R.ok(Map.of("total", total, "list", list));
    }

    /**
     * 获取粉丝列表（公开接口）
     *
     * @param userId 用户ID
     * @param page   页码（默认1）
     * @param size   每页条数（默认20，最大50）
     */
    @GetMapping("/follower/{userId}")
    public R<Map<String, Object>> getFollowerList(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<FollowVO> list = followService.getFollowerList(userId, page, size);
        long total = followService.getFollowerCount(userId);
        return R.ok(Map.of("total", total, "list", list));
    }

    /**
     * 获取共同关注（需登录）
     *
     * @param targetUserId 目标用户ID
     */
    @GetMapping("/common/{targetUserId}")
    public R<List<Long>> getCommonFollowing(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long targetUserId) {
        return R.ok(followService.getCommonFollowing(userId, targetUserId));
    }

    /**
     * 查询是否关注了目标用户（需登录）
     *
     * @param targetUserId 目标用户ID
     */
    @GetMapping("/relation/{targetUserId}")
    public R<Map<String, Boolean>> checkRelation(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long targetUserId) {
        boolean isFollowing = followService.isFollowing(userId, targetUserId);
        boolean isFollowBack = followService.isFollowing(targetUserId, userId);
        return R.ok(Map.of(
                "isFollowing", isFollowing,
                "isFollowBack", isFollowBack,
                "isMutual", isFollowing && isFollowBack
        ));
    }

    // ==================== 内部管理接口 ====================

    /**
     * 计数对账修复（内部接口，后续接入定时任务或管理后台）
     * <p>
     * 以 Redis ZSet 的 ZCARD 为准，修复 counter 计数值。
     * 用于防御 Lua 脚本部分执行失败导致的计数不一致。
     * </p>
     */
    @PostMapping("/internal/repair-counter/{userId}")
    public R<String> repairCounter(@PathVariable Long userId) {
        String result = followService.repairUserCounters(userId);
        return R.ok(result);
    }
}
