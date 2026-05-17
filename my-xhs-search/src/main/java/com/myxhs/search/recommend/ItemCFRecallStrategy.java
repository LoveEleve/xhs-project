package com.myxhs.search.recommend;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.search.dto.RecallItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Item-CF 协同过滤召回
 * <p>
 * 原理：用户历史正向行为（点赞/收藏/长停留）→ 找到相似物品 → 推荐相似物品。
 * 相似矩阵由离线定时任务预计算，存储在 Redis ZSet 中：
 * recommend:itemcf:{noteId} → ZSet(相似noteId, 相似度)
 * </p>
 * <p>
 * 召回流程：
 * 1. 从 MySQL 获取用户最近 20 个正向交互笔记
 * 2. 对每个笔记，从 Redis 相似矩阵取 Top 10 相似笔记
 * 3. 累计相似度排序，取 Top size
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemCFRecallStrategy implements RecallStrategy {

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<RecallItem> recall(Long userId, int size) {
        try {
            // 1. 获取用户最近正向交互的笔记（点赞=3/收藏=4/长停留=7且>10s）
            List<Long> recentNotes = getRecentPositiveInteractions(userId, 20);
            if (recentNotes.isEmpty()) {
                return Collections.emptyList();
            }

            // 2. 对每个笔记，从相似矩阵取 Top 10 相似笔记
            Map<Long, Double> scoreMap = new HashMap<>();
            for (Long noteId : recentNotes) {
                String key = RedisKeyConstants.RECOMMEND_ITEMCF + noteId;
                Set<ZSetOperations.TypedTuple<String>> similar = stringRedisTemplate.opsForZSet()
                        .reverseRangeWithScores(key, 0, 9);

                if (similar != null) {
                    for (ZSetOperations.TypedTuple<String> tuple : similar) {
                        if (tuple.getValue() == null || tuple.getScore() == null) continue;
                        Long candidateId = Long.valueOf(tuple.getValue());
                        // 排除用户已交互过的
                        if (!recentNotes.contains(candidateId)) {
                            scoreMap.merge(candidateId, tuple.getScore(), Double::sum);
                        }
                    }
                }
            }

            // 3. 按累计相似度排序，取 Top size
            return scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(size)
                    .map(e -> new RecallItem(e.getKey(), e.getValue(), "ITEM_CF"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[推荐-ItemCF] 召回失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public String name() {
        return "ITEM_CF";
    }

    /**
     * 获取用户最近正向交互的笔记ID
     * 正向行为：点赞(3)、收藏(4)、停留>10s(7)
     */
    private List<Long> getRecentPositiveInteractions(Long userId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT note_id FROM (" +
                        "SELECT note_id, MAX(created_at) AS latest_at FROM t_user_behavior " +
                        "WHERE user_id = ? AND (behavior_type IN (3, 4) OR (behavior_type = 7 AND duration > 10)) " +
                        "GROUP BY note_id ORDER BY latest_at DESC LIMIT ?" +
                        ") t",
                Long.class, userId, limit);
    }
}
