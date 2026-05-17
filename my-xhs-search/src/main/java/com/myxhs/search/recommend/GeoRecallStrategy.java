package com.myxhs.search.recommend;

import com.myxhs.search.dto.RecallItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 地理位置召回（同城内容）
 * <p>
 * 基于内容特征表中的 category 字段（简化版，生产环境应使用 GeoHash）。
 * 当前实现：从最近发布的高质量内容中召回（模拟同城效果）。
 * </p>
 * <p>
 * 适用场景：本地化内容推荐，"附近的人在看什么"。
 * 冷启动用户的重要补充来源。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeoRecallStrategy implements RecallStrategy {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<RecallItem> recall(Long userId, int size) {
        try {
            // 简化实现：从最近更新的高质量内容中召回
            // 生产环境应基于 GeoHash 匹配用户位置附近的内容
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT note_id, quality_score FROM t_item_feature " +
                            "WHERE quality_score > 0.3 " +
                            "ORDER BY updated_at DESC LIMIT ?",
                    size);

            return rows.stream()
                    .map(row -> {
                        Long noteId = ((Number) row.get("note_id")).longValue();
                        double score = ((Number) row.get("quality_score")).doubleValue();
                        return new RecallItem(noteId, score, "GEO");
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[推荐-地理] 召回失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public String name() {
        return "GEO";
    }
}
