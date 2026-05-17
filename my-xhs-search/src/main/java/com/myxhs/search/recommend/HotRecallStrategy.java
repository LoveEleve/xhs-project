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
 * 热门召回
 * <p>
 * 从全局热门 ZSet 中取 Top N 笔记。
 * 热门池由定时任务维护：recommend:hot:global → ZSet(noteId, hotScore)
 * </p>
 * <p>
 * 适用场景：兜底策略，保证任何用户都能获得推荐结果。
 * 冷启动用户的主要召回来源。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotRecallStrategy implements RecallStrategy {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public List<RecallItem> recall(Long userId, int size) {
        try {
            Set<ZSetOperations.TypedTuple<String>> hotSet = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(RedisKeyConstants.RECOMMEND_HOT_GLOBAL, 0, size - 1);

            if (hotSet == null || hotSet.isEmpty()) {
                return Collections.emptyList();
            }

            return hotSet.stream()
                    .filter(t -> t.getValue() != null && t.getScore() != null)
                    .map(t -> new RecallItem(Long.valueOf(t.getValue()), t.getScore(), "HOT"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[推荐-热门] 召回失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public String name() {
        return "HOT";
    }
}
