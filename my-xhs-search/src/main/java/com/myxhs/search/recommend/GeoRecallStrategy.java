package com.myxhs.search.recommend;

import com.myxhs.search.dto.RecallItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 地理位置召回（同城内容 — 基于 GeoHash）
 * <p>
 * 原理：GeoHash 将经纬度编码为字符串，相同前缀 = 距离近。
 * 查询用户 GeoHash 前缀匹配的内容实现附近召回。
 * </p>
 * <p>
 * 降级策略：用户位置未知时降级为热门高质量内容（全站兜底）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeoRecallStrategy implements RecallStrategy {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    /** GeoHash 前缀匹配长度（越长越精确，5=~4.9km, 6=~1.2km） */
    private static final int GEOHASH_PREFIX_LEN = 5;

    @Override
    public List<RecallItem> recall(Long userId, int size) {
        try {
            // 1. 查询用户 GeoHash（Redis 中缓存的位置信息）
            String geoHash = getUserGeoHash(userId);

            if (geoHash == null || geoHash.length() < GEOHASH_PREFIX_LEN) {
                // 用户位置未知，降级为热门高质量内容
                return fallbackHotRecall(size);
            }

            // 2. 基于 GeoHash 前缀匹配查询附近内容
            String prefix = geoHash.substring(0, GEOHASH_PREFIX_LEN);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT note_id, quality_score, created_at FROM t_item_feature " +
                            "WHERE quality_score > 0.3 " +
                            "AND geo_hash LIKE ? " +
                            "ORDER BY quality_score DESC, created_at DESC LIMIT ?",
                    prefix + "%", size * 2); // 多召回一些

            if (rows.isEmpty()) {
                // 附近无内容，扩大范围：只用前 4 位（~40km 范围）
                String prefix4 = geoHash.substring(0, 4);
                rows = jdbcTemplate.queryForList(
                        "SELECT note_id, quality_score, created_at FROM t_item_feature " +
                                "WHERE quality_score > 0.3 " +
                                "AND geo_hash LIKE ? " +
                                "ORDER BY quality_score DESC, created_at DESC LIMIT ?",
                        prefix4 + "%", size);
            }

            if (rows.isEmpty()) {
                return fallbackHotRecall(size);
            }

            return rows.stream()
                    .map(row -> {
                        Long noteId = ((Number) row.get("note_id")).longValue();
                        double score = ((Number) row.get("quality_score")).doubleValue();
                        RecallItem item = new RecallItem(noteId, score, "GEO");
                        if (row.get("created_at") instanceof java.time.LocalDateTime dt) {
                            item.setPublishTime(dt.atZone(java.time.ZoneId.systemDefault())
                                    .toInstant().toEpochMilli());
                        }
                        return item;
                    })
                    .limit(size)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[推荐-地理] 召回失败: userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取用户 GeoHash（优先 Redis 缓存）
     */
    private String getUserGeoHash(Long userId) {
        try {
            String key = "user:geo:" + userId;
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        } catch (Exception e) {
            log.debug("[推荐-地理] 获取用户GeoHash失败: userId={}", userId);
        }
        return null;
    }

    /**
     * 位置未知时的降级：全站高质量热门内容
     */
    private List<RecallItem> fallbackHotRecall(int size) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT note_id, quality_score, created_at FROM t_item_feature " +
                            "WHERE quality_score > 0.5 " +
                            "ORDER BY quality_score DESC, created_at DESC LIMIT ?",
                    size);
            return rows.stream()
                    .map(row -> {
                        Long noteId = ((Number) row.get("note_id")).longValue();
                        double score = ((Number) row.get("quality_score")).doubleValue();
                        RecallItem item = new RecallItem(noteId, score, "GEO");
                        if (row.get("created_at") instanceof java.time.LocalDateTime dt) {
                            item.setPublishTime(dt.atZone(java.time.ZoneId.systemDefault())
                                    .toInstant().toEpochMilli());
                        }
                        return item;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public String name() {
        return "GEO";
    }
}
