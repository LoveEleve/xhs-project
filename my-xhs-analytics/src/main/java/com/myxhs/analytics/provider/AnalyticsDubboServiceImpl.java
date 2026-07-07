package com.myxhs.analytics.provider;

import com.myxhs.analytics.dubbo.AnalyticsDubboService;
import com.myxhs.analytics.service.FollowService;
import com.myxhs.analytics.service.LikeService;
import com.myxhs.analytics.service.FavoriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分析/社交 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的社交关系查询 RPC 接口。
 * 主要用于首页聚合服务 Feed 流中的点赞状态、关注关系、收藏状态查询。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class AnalyticsDubboServiceImpl implements AnalyticsDubboService {

    private final FollowService followService;
    private final LikeService likeService;
    private final FavoriteService favoriteService;

    @Override
    public Map<String, Object> getFollowingList(Long userId, int page, int size) {
        // 调用 FollowService 获取关注列表
        log.debug("[AnalyticsDubbo] getFollowingList: userId={}, page={}, size={}", userId, page, size);
        Map<String, Object> result = new HashMap<>();
        result.put("records", followService.getFollowingList(userId, page, size));
        return result;
    }

    @Override
    public Map<String, Object> getFollowerList(Long userId, int page, int size) {
        log.debug("[AnalyticsDubbo] getFollowerList: userId={}, page={}, size={}", userId, page, size);
        Map<String, Object> result = new HashMap<>();
        result.put("records", followService.getFollowerList(userId, page, size));
        return result;
    }

    @Override
    public Map<Long, Boolean> batchCheckLikeStatus(Long userId, int bizType, String bizIds) {
        List<Long> bizIdList = Arrays.stream(bizIds.split(","))
                .map(Long::parseLong)
                .collect(Collectors.toList());
        return likeService.batchCheckLikeStatus(userId, bizType, bizIdList);
    }

    @Override
    public Map<String, Boolean> checkRelation(Long userId, Long targetUserId) {
        boolean isFollowing = followService.isFollowing(userId, targetUserId);
        boolean isFollower = followService.isFollowing(targetUserId, userId);
        Map<String, Boolean> result = new HashMap<>();
        result.put("following", isFollowing);
        result.put("follower", isFollower);
        return result;
    }

    @Override
    public Boolean checkFavoriteStatus(Long userId, Long noteId) {
        return favoriteService.isFavorited(userId, noteId);
    }
}
