package com.myxhs.search.recommend;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.search.dto.RecallItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 内容召回（基于标签匹配）
 * <p>
 * 原理：根据用户兴趣标签，从内容特征表中匹配标签相似的笔记。
 * 用户兴趣标签存储在 Redis Hash 中：recommend:user:tags:{userId} → {tag: weight}
 * 内容特征存储在 MySQL t_item_feature 表中。
 * </p>
 * <p>
 * 适用场景：Item-CF 冷启动补充（新内容无行为数据时，靠标签匹配）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentRecallStrategy implements RecallStrategy {

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<RecallItem> recall(Long userId, int size) {
        try {
            // 1. 获取用户兴趣标签（Redis Hash）
            String userTagKey = RedisKeyConstants.RECOMMEND_USER_TAGS + userId;
            Map<Object, Object> userTags = stringRedisTemplate.opsForHash().entries(userTagKey);

            if (userTags.isEmpty()) {
                return Collections.emptyList();
            }

            // 2. 取权重最高的 5 个标签
            List<String> topTags = userTags.entrySet().stream()
                    .sorted((a, b) -> Double.compare(
                            Double.parseDouble(b.getValue().toString()),
                            Double.parseDouble(a.getValue().toString())))
                    .limit(5)
                    .map(e -> e.getKey().toString())
                    .collect(Collectors.toList());

            // 3. 从内容特征表中匹配标签（LIKE 模糊匹配，生产环境应用 ES）
            Set<Long> candidates = new LinkedHashSet<>();
            Map<Long, Double> scoreMap = new HashMap<>();

            for (String tag : topTags) {
                double tagWeight = Double.parseDouble(userTags.get(tag).toString());
                // 转义 LIKE 通配符，防止 tag 中包含 % 或 _ 导致意外匹配
                String escapedTag = tag.replace("%", "\\%").replace("_", "\\_");
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "SELECT note_id, quality_score FROM t_item_feature " +
                                "WHERE tags LIKE ? ESCAPE '\\\\' ORDER BY quality_score DESC LIMIT 30",
                        "%" + escapedTag + "%");

                for (Map<String, Object> row : rows) {
                    Long noteId = ((Number) row.get("note_id")).longValue();
                    double qualityScore = ((Number) row.get("quality_score")).doubleValue();
                    // 分数 = 标签权重 × 内容质量分
                    double score = tagWeight * (1 + qualityScore);
                    scoreMap.merge(noteId, score, Double::sum);
                    candidates.add(noteId);
                }
            }

            // 4. 按分数排序取 Top size
            return scoreMap.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(size)
                    .map(e -> new RecallItem(e.getKey(), e.getValue(), "CONTENT"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[推荐-内容] 召回失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public String name() {
        return "CONTENT";
    }
}
