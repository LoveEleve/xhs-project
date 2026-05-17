package com.myxhs.home.feign.fallback;

import com.myxhs.common.response.R;
import com.myxhs.home.feign.AnalyticsFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
public class AnalyticsFeignFallbackFactory implements FallbackFactory<AnalyticsFeignClient> {
    @Override
    public AnalyticsFeignClient create(Throwable cause) {
        log.warn("[降级] AnalyticsFeignClient 不可用: {}", cause.getMessage());
        return new AnalyticsFeignClient() {
            @Override
            public R<Map<String, Object>> getFollowingList(Long userId, int page, int size) {
                return R.ok(Map.of("total", 0L, "list", Collections.emptyList()));
            }

            @Override
            public R<Map<String, Object>> getFollowerList(Long userId, int page, int size) {
                return R.ok(Map.of("total", 0L, "list", Collections.emptyList()));
            }

            @Override
            public R<Map<Long, Boolean>> batchCheckLikeStatus(Long userId, int bizType, String bizIds) {
                return R.ok(Collections.emptyMap());
            }

            @Override
            public R<Map<String, Boolean>> checkRelation(Long userId, Long targetUserId) {
                return R.ok(Map.of("isFollowing", false, "isFollowBack", false, "isMutual", false));
            }

            @Override
            public R<Boolean> checkFavoriteStatus(Long userId, Long noteId) {
                return R.ok(false);
            }

            @Override
            public R<Map<String, Object>> getFollowerCount(Long userId, int page, int size) {
                return R.ok(Map.of("total", 0L, "list", Collections.emptyList()));
            }
        };
    }
}
