package com.myxhs.home.feign;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.fallback.AnalyticsFeignFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * 社交服务 Feign Client
 */
@FeignClient(name = "my-xhs-analytics",
        fallbackFactory = AnalyticsFeignFallbackFactory.class)
public interface AnalyticsFeignClient {

    /**
     * 获取关注列表（用于拉取关注的大V发件箱）
     */
    @GetMapping("/api/social/following/{userId}")
    R<Map<String, Object>> getFollowingList(@PathVariable("userId") Long userId,
                                            @RequestParam("page") int page,
                                            @RequestParam("size") int size);

    /**
     * 获取粉丝列表（用于推模式写扩散）
     */
    @GetMapping("/api/social/follower/{userId}")
    R<Map<String, Object>> getFollowerList(@PathVariable("userId") Long userId,
                                           @RequestParam("page") int page,
                                           @RequestParam("size") int size);

    /**
     * 批量查询点赞状态
     */
    @GetMapping("/api/social/like/batch-status")
    R<Map<Long, Boolean>> batchCheckLikeStatus(@RequestHeader("X-User-Id") Long userId,
                                               @RequestParam("bizType") int bizType,
                                               @RequestParam("bizIds") String bizIds);

    /**
     * 查询关注关系
     */
    @GetMapping("/api/social/relation/{targetUserId}")
    R<Map<String, Boolean>> checkRelation(@RequestHeader("X-User-Id") Long userId,
                                          @PathVariable("targetUserId") Long targetUserId);

    /**
     * 查询收藏状态
     */
    @GetMapping("/api/social/favorite/status")
    R<Boolean> checkFavoriteStatus(@RequestHeader("X-User-Id") Long userId,
                                   @RequestParam("noteId") Long noteId);

    /**
     * 获取粉丝数（用于判断是否大V）
     */
    @GetMapping("/api/social/follower/{userId}")
    R<Map<String, Object>> getFollowerCount(@PathVariable("userId") Long userId,
                                            @RequestParam("page") int page,
                                            @RequestParam("size") int size);
}
