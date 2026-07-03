package com.myxhs.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 缓存预热启动器（ApplicationRunner）
 * <p>
 * 应用启动完成后自动执行，预热以下缓存：
 * 1. 热搜 Top 50 → Redis ZSet
 * 2. 全站热门笔记 Top 100 → Redis ZSet
 * 3. 商品分类树 → Redis String
 * </p>
 * <p>
 * 注意：预热失败不影响服务启动，降级为按需加载。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnBean({StringRedisTemplate.class, JdbcTemplate.class})
public class CacheWarmupRunner implements ApplicationRunner {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[缓存预热] 开始...");
        long start = System.currentTimeMillis();
        int warmed = 0;

        try { warmed += warmHotSearch(); } catch (Exception e) { log.warn("[缓存预热] 热搜预热失败", e); }
        try { warmed += warmHotNotes();    } catch (Exception e) { log.warn("[缓存预热] 热门笔记预热失败", e); }
        try { warmed += warmCategoryTree();} catch (Exception e) { log.warn("[缓存预热] 分类树预热失败", e); }

        long cost = System.currentTimeMillis() - start;
        log.info("[缓存预热] 完成: {} 项, cost={}ms", warmed, cost);
    }

    /**
     * 预热热搜 Top 50
     */
    private int warmHotSearch() {
        String key = "search:hot:global";
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            log.debug("[缓存预热] 热搜已存在，跳过");
            return 0;
        }
        try {
            List<Map<String, Object>> hotList = jdbcTemplate.queryForList(
                    "SELECT keyword, search_count FROM t_search_hot ORDER BY search_count DESC LIMIT 50");
            if (hotList.isEmpty()) return 0;
            for (Map<String, Object> row : hotList) {
                String keyword = (String) row.get("keyword");
                double score = ((Number) row.get("search_count")).doubleValue();
                stringRedisTemplate.opsForZSet().add(key, keyword, score);
            }
            stringRedisTemplate.expire(key, 1, TimeUnit.HOURS);
            return hotList.size();
        } catch (Exception e) {
            log.debug("[缓存预热] 热搜表不存在或为空，跳过");
            return 0;
        }
    }

    /**
     * 预热全站热门笔记 Top 100
     */
    private int warmHotNotes() {
        String key = "recommend:hot:global";
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            log.debug("[缓存预热] 热门笔记已存在，跳过");
            return 0;
        }
        try {
            List<Map<String, Object>> hotList = jdbcTemplate.queryForList(
                    "SELECT note_id, hot_score FROM t_item_feature WHERE hot_score > 0 ORDER BY hot_score DESC LIMIT 100");
            for (Map<String, Object> row : hotList) {
                String noteId = String.valueOf(((Number) row.get("note_id")).longValue());
                double score = ((Number) row.get("hot_score")).doubleValue();
                stringRedisTemplate.opsForZSet().add(key, noteId, score);
            }
            stringRedisTemplate.expire(key, 1, TimeUnit.HOURS);
            return hotList.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 预热商品分类树
     */
    private int warmCategoryTree() {
        String key = "product:category:tree";
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return 0;
        }
        try {
            List<Map<String, Object>> cats = jdbcTemplate.queryForList(
                    "SELECT id, name, parent_id, level FROM t_category ORDER BY parent_id, sort_order");
            if (!cats.isEmpty()) {
                stringRedisTemplate.opsForValue().set(key, cats.toString(), 1, TimeUnit.HOURS);
                return 1;
            }
        } catch (Exception e) {
            // 表可能不存在
        }
        return 0;
    }
}
