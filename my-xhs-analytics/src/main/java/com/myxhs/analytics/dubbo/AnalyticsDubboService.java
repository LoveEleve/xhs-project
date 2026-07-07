package com.myxhs.analytics.dubbo;

import java.util.Map;

/**
 * 分析/社交 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于首页聚合服务高频调用社交服务：
 * - 批量查询点赞状态（Feed 流每个笔记的点赞状态）
 * - 关注关系查询（用户主页）
 * - 收藏状态查询
 * </p>
 */
public interface AnalyticsDubboService {

    /**
     * 获取关注列表（用于拉取关注的大V发件箱）
     */
    Map<String, Object> getFollowingList(Long userId, int page, int size);

    /**
     * 获取粉丝列表（用于推模式写扩散）
     */
    Map<String, Object> getFollowerList(Long userId, int page, int size);

    /**
     * 批量查询点赞状态
     */
    Map<Long, Boolean> batchCheckLikeStatus(Long userId, int bizType, String bizIds);

    /**
     * 查询关注关系
     */
    Map<String, Boolean> checkRelation(Long userId, Long targetUserId);

    /**
     * 查询收藏状态
     */
    Boolean checkFavoriteStatus(Long userId, Long noteId);
}
