package com.myxhs.search.recommend;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.search.dto.RecallItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 关注召回
 * <p>
 * 从用户关注的人的最新内容中召回。
 * 数据来源：recommend:following:latest:{userId} → ZSet(noteId, timestamp)
 * 该 ZSet 由 Feed 流推送时同步写入（或定时任务聚合）。
 * </p>
 * <p>
 * 适用场景：社交关系驱动的推荐，"你关注的人发布了新内容"。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowingRecallStrategy implements RecallStrategy {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public List<RecallItem> recall(Long userId, int size) {
        try {
            String key = RedisKeyConstants.RECOMMEND_FOLLOWING_LATEST + userId;
            Set<ZSetOperations.TypedTuple<String>> followSet = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, 0, size - 1);

            if (followSet == null || followSet.isEmpty()) {
                return Collections.emptyList();
            }

            // 时间越近分数越高（score 是 timestamp，归一化为 0~1）
            long now = System.currentTimeMillis();
            long dayMs = 86400_000L;

            return followSet.stream()
                    .filter(t -> t.getValue() != null && t.getScore() != null)
                    .map(t -> {
                        long ts = t.getScore().longValue();
                        // 24 小时内的内容权重高，超过 24 小时衰减
                        double freshness = Math.max(0, 1.0 - (double) (now - ts) / (3 * dayMs));
                        return new RecallItem(Long.valueOf(t.getValue()), freshness, "FOLLOWING");
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[推荐-关注] 召回失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public String name() {
        return "FOLLOWING";
    }
}
