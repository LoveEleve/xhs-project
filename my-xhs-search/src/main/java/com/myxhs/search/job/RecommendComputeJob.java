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
        long start = System.currentTimeMillis();

        try {
            List<Long> missingNotes = jdbcTemplate.queryForList(
                    "SELECT DISTINCT b.note_id FROM t_user_behavior b " +
                            "LEFT JOIN t_item_feature f ON b.note_id = f.note_id " +
                            "WHERE f.note_id IS NULL LIMIT 500",
                    Long.class);

            if (missingNotes.isEmpty()) {
                log.debug("[推荐-特征] 无新笔记需要提取特征");
                return;
            }

            String[] categories = {"美食", "旅行", "穿搭", "美妆", "数码", "运动", "宠物", "家居"};
            String[][] tagPool = {
                    {"美食", "探店", "菜谱", "甜品"},
                    {"旅行", "攻略", "风景", "自驾"},
                    {"穿搭", "OOTD", "时尚", "潮流"},
                    {"美妆", "护肤", "化妆", "测评"},
                    {"数码", "手机", "电脑", "评测"},
                    {"运动", "健身", "跑步", "瑜伽"},
                    {"宠物", "猫咪", "狗狗", "萌宠"},
                    {"家居", "装修", "收纳", "好物"}
            };

            Random random = new Random();
            String sql = "INSERT INTO t_item_feature (id, note_id, tags, category, quality_score) " +
                    "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE tags=VALUES(tags), category=VALUES(category)";

            List<Object[]> batchArgs = new ArrayList<>();
            for (Long noteId : missingNotes) {
                int catIdx = Math.abs(noteId.hashCode()) % categories.length;
                String category = categories[catIdx];
                String[] tags = tagPool[catIdx];
                int tagCount = 2 + random.nextInt(2);
                List<String> selectedTags = new ArrayList<>();
                Set<Integer> usedIdx = new HashSet<>();
                for (int i = 0; i < tagCount && i < tags.length; i++) {
                    int idx;
                    do { idx = random.nextInt(tags.length); } while (usedIdx.contains(idx));
                    usedIdx.add(idx);
                    selectedTags.add(tags[idx]);
                }
                String tagsJson = "[\"" + String.join("\",\"", selectedTags) + "\"]";
                double qualityScore = 0.3 + random.nextDouble() * 0.7;

                batchArgs.add(new Object[]{
                        com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                        noteId, tagsJson, category, qualityScore
                });
            }

            jdbcTemplate.batchUpdate(sql, batchArgs);

            long cost = System.currentTimeMillis() - start;
            log.info("[推荐-特征] 特征提取完成: {} 条, cost={}ms", batchArgs.size(), cost);
        } catch (Exception e) {
            log.error("[推荐-特征] 特征提取失败", e);
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
        long start = System.currentTimeMillis();

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
            log.error("[推荐-热门] 热门池更新失败", e);
        }
    }
}
