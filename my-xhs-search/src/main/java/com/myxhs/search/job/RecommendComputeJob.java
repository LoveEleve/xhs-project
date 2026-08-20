package com.myxhs.search.job;

import com.myxhs.common.constants.RedisKeyConstants;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 推荐系统离线计算任务（XXL-Job 分布式调度）
 * <p>
 * 职责：
 * 1. Item-CF 相似矩阵计算（每 2 小时）
 * 2. 内容特征提取（每 1 小时）
 * 3. 全局热门池维护（每 30 分钟）
 * </p>
 * <p>
 * XXL-Job 调度保证：Admin 只调度一个 Executor 实例执行，无需 Redisson 分布式锁。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendComputeJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    // ==================== Item-CF 相似矩阵计算 ====================

    /**
     * Item-CF 相似矩阵计算（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 0/2 * * ?（每 2 小时）
     * <p>
     * 算法：基于用户行为的物品共现矩阵 → 余弦相似度
     * 1. 找出所有有正向行为的用户-物品对
     * 2. 对每对物品，计算共同交互用户数
     * 3. 余弦相似度 = 共现次数 / sqrt(物品A交互数 × 物品B交互数)
     * 4. 每个物品保留 Top 20 相似物品，写入 Redis ZSet
     * </p>
     * <p>
     * 优化：只计算最近 7 天有交互的热门笔记（交互数 > 5），
     * 避免全量计算导致内存溢出。
     * </p>
     */
    @XxlJob("recommendItemCFJob")
    public void computeItemCFMatrix() {
        try {
            doComputeItemCFMatrix();
            XxlJobHelper.handleSuccess("Item-CF 相似矩阵计算完成");
        } catch (Exception e) {
            log.error("[推荐-ItemCF] 相似矩阵计算异常", e);
            XxlJobHelper.handleFail("Item-CF 计算异常: " + e.getMessage());
        }
    }

    private void doComputeItemCFMatrix() {
        // 前置检查：行为数据表是否存在
        jdbcTemplate.queryForList("SELECT 1 FROM t_user_behavior LIMIT 1");
        long start = System.currentTimeMillis();

        // 1. 获取最近 7 天有正向行为的用户-物品对
        List<Map<String, Object>> interactions = jdbcTemplate.queryForList(
                "SELECT user_id, note_id FROM t_user_behavior " +
                        "WHERE created_at > DATE_SUB(NOW(), INTERVAL 7 DAY) " +
                        "AND (behavior_type IN (3, 4, 5, 6) OR (behavior_type = 7 AND duration > 10)) " +
                        "GROUP BY user_id, note_id");

        if (interactions.isEmpty()) {
            log.info("[推荐-ItemCF] 无交互数据，跳过计算");
            return;
        }

        // 2. 构建倒排索引：用户 → 交互物品集合
        Map<Long, Set<Long>> userItems = new HashMap<>();
        Map<Long, Integer> itemCount = new HashMap<>();

        for (Map<String, Object> row : interactions) {
            Long userId = ((Number) row.get("user_id")).longValue();
            Long noteId = ((Number) row.get("note_id")).longValue();
            userItems.computeIfAbsent(userId, k -> new HashSet<>()).add(noteId);
            itemCount.merge(noteId, 1, Integer::sum);
        }

        // 3. 只保留交互数 > 5 的热门物品（控制计算规模）
        Set<Long> hotItems = new HashSet<>();
        itemCount.forEach((noteId, count) -> {
            if (count >= 5) hotItems.add(noteId);
        });

        if (hotItems.isEmpty()) {
            log.info("[推荐-ItemCF] 无热门物品，跳过计算");
            return;
        }

        // 4. 计算物品共现矩阵
        Map<Long, Map<Long, Integer>> coOccurrence = new HashMap<>();
        for (Set<Long> items : userItems.values()) {
            List<Long> hotList = items.stream()
                    .filter(hotItems::contains)
                    .limit(50)
                    .toList();

            for (int i = 0; i < hotList.size(); i++) {
                for (int j = i + 1; j < hotList.size(); j++) {
                    Long a = hotList.get(i);
                    Long b = hotList.get(j);
                    coOccurrence.computeIfAbsent(a, k -> new HashMap<>()).merge(b, 1, Integer::sum);
                    coOccurrence.computeIfAbsent(b, k -> new HashMap<>()).merge(a, 1, Integer::sum);
                }
            }
        }

        // 5. 计算余弦相似度，写入 Redis
        int totalPairs = 0;
        for (Map.Entry<Long, Map<Long, Integer>> entry : coOccurrence.entrySet()) {
            Long itemA = entry.getKey();
            int countA = itemCount.getOrDefault(itemA, 1);
            String key = RedisKeyConstants.RECOMMEND_ITEMCF + itemA;
            String tmpKey = key + ":tmp";

            stringRedisTemplate.delete(tmpKey);

            Map<Long, Integer> coItems = entry.getValue();
            for (Map.Entry<Long, Integer> coEntry : coItems.entrySet()) {
                Long itemB = coEntry.getKey();
                int coCount = coEntry.getValue();
                int countB = itemCount.getOrDefault(itemB, 1);

                double similarity = coCount / Math.sqrt((double) countA * countB);

                stringRedisTemplate.opsForZSet().add(tmpKey, String.valueOf(itemB), similarity);
                totalPairs++;
            }

            Long zsetSize = stringRedisTemplate.opsForZSet().size(tmpKey);
            if (zsetSize != null && zsetSize > 20) {
                stringRedisTemplate.opsForZSet().removeRange(tmpKey, 0, zsetSize - 21);
            }

            stringRedisTemplate.rename(tmpKey, key);
            stringRedisTemplate.expire(key, 24, TimeUnit.HOURS);
        }

        long cost = System.currentTimeMillis() - start;
        log.info("[推荐-ItemCF] 相似矩阵计算完成: 热门物品={}, 相似对={}, cost={}ms",
                hotItems.size(), totalPairs, cost);
    }

    // ==================== 内容特征提取 ====================

    /**
     * 内容特征提取（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0 0/1 * * ?（每 1 小时）
     * <p>
     * 从笔记索引（ES）或数据库中提取标签、分类等特征，
     * 写入 t_item_feature 表。
     * 当前简化实现：从 ES note_index 中提取。
     * </p>
     */
    @XxlJob("recommendFeatureJob")
    public void extractFeatures() {
        try {
            doExtractFeatures();
            XxlJobHelper.handleSuccess("特征提取完成");
        } catch (Exception e) {
            log.error("[推荐-特征] 特征提取异常", e);
            XxlJobHelper.handleFail("特征提取异常: " + e.getMessage());
        }
    }

    private void doExtractFeatures() {
        // 前置检查：源表是否存在
        jdbcTemplate.queryForList("SELECT 1 FROM t_note LIMIT 1");
        // T-083：目标表 t_item_feature 缺失时直接 handleFail（原实现 INSERT 失败被 catch 后仍报"完成"——误导性成功）
        try {
            jdbcTemplate.queryForList("SELECT 1 FROM t_item_feature LIMIT 1");
        } catch (Exception e) {
            throw new IllegalStateException("t_item_feature 表不存在——特征提取无法执行，请先执行建表 DDL（sql/init-all.sql / t_item_feature_ddl.sql）");
        }
        long start = System.currentTimeMillis();

        try {
            // 从 t_user_behavior 中找到有新行为但无特征记录的笔记
            List<Long> missingNotes = jdbcTemplate.queryForList(
                    "SELECT DISTINCT b.note_id FROM t_user_behavior b " +
                            "LEFT JOIN t_item_feature f ON b.note_id = f.note_id " +
                            "WHERE f.note_id IS NULL LIMIT 500",
                    Long.class);

            if (missingNotes.isEmpty()) {
                log.debug("[推荐-特征] 无新笔记需要提取特征");
                return;
            }

            // 从 ES 或 MySQL note 表读取真实标签和分类
            Map<Long, NoteFeatures> realFeatures = batchFetchRealFeatures(missingNotes);

            String sql = "INSERT INTO t_item_feature (id, note_id, tags, category, quality_score) " +
                    "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE tags=VALUES(tags), category=VALUES(category)";

            List<Object[]> batchArgs = new ArrayList<>();
            for (Long noteId : missingNotes) {
                NoteFeatures nf = realFeatures.getOrDefault(noteId, NoteFeatures.UNKNOWN);

                // 计算内容质量分：基于 like_count + comment_count 归一化
                double qualityScore = computeQualityScore(nf.likeCount, nf.commentCount, nf.collectionCount);

                batchArgs.add(new Object[]{
                        com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                        noteId,
                        nf.toTagsJson(),
                        nf.category,
                        qualityScore
                });
            }

            jdbcTemplate.batchUpdate(sql, batchArgs);

            long cost = System.currentTimeMillis() - start;
            log.info("[推荐-特征] 特征提取完成: {} 条 (含真实标签), cost={}ms", batchArgs.size(), cost);
        } catch (Exception e) {
            throw new IllegalStateException("特征提取失败", e);
        }
    }

    /**
     * 批量从 MySQL note 表读取真实标签和分类
     * <p>
     * 替代 Random 随机生成逻辑，读取笔记真实 tags 和 categoryId。
     * </p>
     */
    private Map<Long, NoteFeatures> batchFetchRealFeatures(List<Long> noteIds) {
        Map<Long, NoteFeatures> result = new HashMap<>();
        if (noteIds.isEmpty()) return result;

        try {
            String placeholders = noteIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
            // 尝试从 content 库的 t_note 表读取 tags + 互动数据
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, tags, content, status FROM t_note WHERE id IN (" + placeholders + ")",
                    noteIds.toArray());

            for (Map<String, Object> row : rows) {
                Long noteId = ((Number) row.get("id")).longValue();
                String tagsJson = (String) row.get("tags");
                String content = (String) row.get("content");
                Integer status = row.get("status") != null ? ((Number) row.get("status")).intValue() : 0;

                String category = inferCategory(tagsJson, content);
                List<String> tagList = parseTagList(tagsJson, content);
                result.put(noteId, new NoteFeatures(category, tagList, 0, 0, 0));
            }

            // 补充互动数据
            enrichEngagementCounts(result);
        } catch (Exception e) {
            throw new IllegalStateException("批量读取笔记特征失败", e);
        }

        // 填充缺失的笔记为默认值
        for (Long noteId : noteIds) {
            result.putIfAbsent(noteId, NoteFeatures.UNKNOWN);
        }

        return result;
    }

    /**
     * 从 tags JSON 解析标签列表
     */
    private List<String> parseTagList(String tagsJson, String content) {
        List<String> tags = new ArrayList<>();
        if (tagsJson != null && !tagsJson.isEmpty()) {
            try {
                String cleaned = tagsJson.replaceAll("[\\[\\]\"]", "");
                for (String t : cleaned.split(",")) {
                    String trimmed = t.trim();
                    if (!trimmed.isEmpty()) tags.add(trimmed);
                }
            } catch (Exception e) {
                // 解析失败用默认值
            }
        }
        if (tags.isEmpty()) {
            // 从内容中提取关键标签
            tags.add("生活");
        }
        return tags;
    }

    /**
     * 根据标签推测分类
     */
    private String inferCategory(String tagsJson, String content) {
        if (tagsJson != null) {
            String lower = tagsJson.toLowerCase();
            if (lower.contains("美食") || lower.contains("菜")) return "美食";
            if (lower.contains("旅行") || lower.contains("旅游")) return "旅行";
            if (lower.contains("穿搭") || lower.contains("时尚")) return "穿搭";
            if (lower.contains("美妆") || lower.contains("护肤")) return "美妆";
            if (lower.contains("数码") || lower.contains("手机")) return "数码";
            if (lower.contains("运动") || lower.contains("健身")) return "运动";
            if (lower.contains("宠物") || lower.contains("猫") || lower.contains("狗")) return "宠物";
            if (lower.contains("家居") || lower.contains("装修")) return "家居";
        }
        return "生活";
    }

    /**
     * 补充互动数据（like_count, comment_count, collection_count）
     */
    private void enrichEngagementCounts(Map<Long, NoteFeatures> features) {
        if (features.isEmpty()) return;
        try {
            String noteIdsStr = features.keySet().stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));

            List<Map<String, Object>> comments = jdbcTemplate.queryForList(
                    "SELECT note_id, COUNT(*) AS comment_count FROM t_comment " +
                            "WHERE deleted = 0 AND note_id IN (" + noteIdsStr + ") GROUP BY note_id");
            for (Map<String, Object> row : comments) {
                Long noteId = ((Number) row.get("note_id")).longValue();
                NoteFeatures nf = features.get(noteId);
                if (nf != null) {
                    nf.commentCount = ((Number) row.get("comment_count")).longValue();
                }
            }

            List<Map<String, Object>> favorites = jdbcTemplate.queryForList(
                    "SELECT note_id, COUNT(*) AS favorite_count FROM my_xhs_analytics.t_favorite " +
                            "WHERE note_id IN (" + noteIdsStr + ") GROUP BY note_id");
            for (Map<String, Object> row : favorites) {
                Long noteId = ((Number) row.get("note_id")).longValue();
                NoteFeatures nf = features.get(noteId);
                if (nf != null) {
                    nf.collectionCount = ((Number) row.get("favorite_count")).longValue();
                }
            }

            for (Long noteId : features.keySet()) {
                Long likeCount = stringRedisTemplate.opsForSet().size("myxhs:like:note:" + noteId);
                features.get(noteId).likeCount = likeCount != null ? likeCount : 0L;
            }
        } catch (Exception e) {
            throw new IllegalStateException("补充互动数据失败", e);
        }
    }

    /**
     * 基于互动数据计算内容质量分（0~1 归一化）
     */
    private double computeQualityScore(long likeCount, long commentCount, long collectionCount) {
        // 互动加权：点赞 1x + 评论 3x + 收藏 5x
        double weightedScore = likeCount + commentCount * 3.0 + collectionCount * 5.0;
        // Sigmoid 归一化：在 log(1+x)/log(101) 范围内
        // 100 互动 → ~0.5, 1000 互动 → ~0.75, 10000 互动 → ~1.0
        return Math.min(Math.max(Math.log1p(weightedScore) / Math.log1p(100), 0.3), 1.0);
    }

    /**
     * 笔记特征值对象
     */
    private static class NoteFeatures {
        static final NoteFeatures UNKNOWN = new NoteFeatures("生活", List.of("生活"), 0, 0, 0);

        String category;
        List<String> tags;
        long likeCount;
        long commentCount;
        long collectionCount;

        NoteFeatures(String category, List<String> tags, long likeCount, long commentCount, long collectionCount) {
            this.category = category;
            this.tags = tags != null ? tags : List.of("生活");
            this.likeCount = likeCount;
            this.commentCount = commentCount;
            this.collectionCount = collectionCount;
        }

        String toTagsJson() {
            return "[\"" + String.join("\",\"", tags) + "\"]";
        }
    }

    // ==================== 全局热门池维护 ====================

    /**
     * 全局热门池维护（XXL-Job Handler）
     * <p>
     * Admin 配置：Cron = 0 0/30 * * * ?（每 30 分钟）
     * <p>
     * 从行为表中统计最近 24 小时内交互最多的笔记，
     * 写入 Redis ZSet：recommend:hot:global → (noteId, interactionCount)
     * </p>
     */
    @XxlJob("recommendHotPoolJob")
    public void refreshHotPool() {
        try {
            doRefreshHotPool();
            XxlJobHelper.handleSuccess("热门池更新完成");
        } catch (Exception e) {
            log.error("[推荐-热门] 热门池更新异常", e);
            XxlJobHelper.handleFail("热门池更新异常: " + e.getMessage());
        }
    }

    private void doRefreshHotPool() {
        // 前置检查
        jdbcTemplate.queryForList("SELECT 1 FROM t_user_behavior LIMIT 1");
        long start = System.currentTimeMillis();
        // ... rest of method        long start = System.currentTimeMillis();

        try {
            List<Map<String, Object>> hotNotes = jdbcTemplate.queryForList(
                    "SELECT note_id, " +
                            "SUM(CASE WHEN behavior_type = 2 THEN 1 " +
                            "         WHEN behavior_type = 3 THEN 3 " +
                            "         WHEN behavior_type = 4 THEN 5 " +
                            "         WHEN behavior_type = 5 THEN 4 " +
                            "         WHEN behavior_type = 6 THEN 6 " +
                            "         ELSE 0 END) AS hot_score " +
                            "FROM t_user_behavior " +
                            "WHERE created_at > DATE_SUB(NOW(), INTERVAL 24 HOUR) " +
                            "GROUP BY note_id " +
                            "ORDER BY hot_score DESC LIMIT 200");

            if (hotNotes.isEmpty()) {
                log.info("[推荐-热门] 无热门数据");
                return;
            }

            String tmpKey = RedisKeyConstants.RECOMMEND_HOT_GLOBAL + ":tmp";
            stringRedisTemplate.delete(tmpKey);

            for (Map<String, Object> row : hotNotes) {
                Long noteId = ((Number) row.get("note_id")).longValue();
                double score = ((Number) row.get("hot_score")).doubleValue();
                stringRedisTemplate.opsForZSet().add(tmpKey, String.valueOf(noteId), score);
            }

            stringRedisTemplate.rename(tmpKey, RedisKeyConstants.RECOMMEND_HOT_GLOBAL);
            stringRedisTemplate.expire(RedisKeyConstants.RECOMMEND_HOT_GLOBAL, 1, TimeUnit.HOURS);

            long cost = System.currentTimeMillis() - start;
            log.info("[推荐-热门] 热门池更新完成: {} 条, 最高分={}, cost={}ms",
                    hotNotes.size(),
                    hotNotes.isEmpty() ? 0 : hotNotes.get(0).get("hot_score"),
                    cost);
        } catch (Exception e) {
            throw new IllegalStateException("热门池更新失败", e);
        }
    }
}
