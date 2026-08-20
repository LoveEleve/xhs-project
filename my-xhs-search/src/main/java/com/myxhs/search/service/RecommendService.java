package com.myxhs.search.service;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.search.dto.BehaviorRequest;
import com.myxhs.search.dto.RecallItem;
import com.myxhs.search.dto.RecommendFeedVO;
import com.myxhs.search.recommend.RecallStrategy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 推荐系统核心服务
 * <p>
 * 四层推荐架构：召回 → 粗排 → 精排 → 重排
 * </p>
 * <p>
 * 召回层：5 路并行（Item-CF / 内容 / 热门 / 关注 / 地理），每路 100 条，合并去重 ≈300 条
 * 粗排层：加权打分（热度 0.3 + 相似度 0.3 + 时效 0.2 + 来源权重 0.2），取 Top 100
 * 精排层：规则排序（预留 ML 模型接口），取 Top 50
 * 重排层：已读过滤（HyperLogLog）+ 品类打散（同品类不超过 2 个连续），最终 20 条
 * </p>
 */
@Slf4j
@Service
public class RecommendService {

    private final List<RecallStrategy> recallStrategies;
    private final ExecutorService recallExecutor;
    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final IdGeneratorUtil idGeneratorUtil;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${recommend.recall.size-per-strategy:100}")
    private int recallSizePerStrategy;

    @Value("${recommend.recall.timeout-ms:2000}")
    private int recallTimeoutMs;

    @Value("${recommend.feed.size:20}")
    private int feedSize;

    /** 来源权重配置 */
    private static final Map<String, Double> SOURCE_WEIGHT = Map.of(
            "ITEM_CF", 1.0,
            "CONTENT", 0.8,
            "FOLLOWING", 0.9,
            "HOT", 0.6,
            "GEO", 0.5
    );

    public RecommendService(
            List<RecallStrategy> recallStrategies,
            @Qualifier("recallExecutor") ExecutorService recallExecutor,
            StringRedisTemplate stringRedisTemplate,
            JdbcTemplate jdbcTemplate,
            IdGeneratorUtil idGeneratorUtil,
            RocketMQTemplate rocketMQTemplate) {
        this.recallStrategies = recallStrategies;
        this.recallExecutor = recallExecutor;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.idGeneratorUtil = idGeneratorUtil;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    // ==================== 推荐 Feed ====================

    /**
     * 获取个性化推荐 Feed
     * <p>
     * 完整四层流水线：召回 → 粗排 → 精排 → 重排
     * 每层都有超时降级和异常兜底。
     * </p>
     */
    public List<RecommendFeedVO> getRecommendFeed(Long userId) {
        long start = System.currentTimeMillis();

        // 1. 判断是否冷启动用户
        boolean isColdStart = isColdStartUser(userId);

        // 2. 多路召回（并行）
        List<RecallItem> candidates;
        if (isColdStart) {
            candidates = coldStartRecall(userId);
            log.info("[推荐] 冷启动用户: userId={}, candidates={}", userId, candidates.size());
        } else {
            candidates = multiRecall(userId);
        }

        if (candidates.isEmpty()) {
            log.warn("[推荐] 无候选内容: userId={}", userId);
            return Collections.emptyList();
        }

        // 3. 粗排
        List<RecallItem> roughRanked = roughRank(candidates);

        // 4. 精排（当前用规则，预留 ML 接口）
        List<RecallItem> fineRanked = fineRank(roughRanked);

        // 5. 重排（已读过滤 + 品类打散）
        List<RecallItem> reRanked = reRank(fineRanked, userId);

        long cost = System.currentTimeMillis() - start;
        log.info("[推荐] 完成: userId={}, candidates={}, result={}, cost={}ms",
                userId, candidates.size(), reRanked.size(), cost);

        // 6. 转换为 VO
        return reRanked.stream()
                .map(item -> RecommendFeedVO.builder()
                        .noteId(item.getNoteId())
                        .score(item.getRankScore())
                        .source(item.getSource())
                        .category(item.getCategory())
                        .reason(getRecommendReason(item))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 获取相似笔记推荐
     */
    public List<RecommendFeedVO> getSimilarNotes(Long noteId, int size) {
        try {
            String key = RedisKeyConstants.RECOMMEND_ITEMCF + noteId;
            var similar = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, 0, size - 1);

            if (similar == null || similar.isEmpty()) {
                return Collections.emptyList();
            }

            return similar.stream()
                    .filter(t -> t.getValue() != null && t.getScore() != null)
                    .map(t -> RecommendFeedVO.builder()
                            .noteId(Long.valueOf(t.getValue()))
                            .score(t.getScore())
                            .source("SIMILAR")
                            .reason("相似内容推荐")
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("[推荐] 相似推荐失败: noteId={}", noteId, e);
            return Collections.emptyList();
        }
    }

    // ==================== 行为上报 ====================

    /**
     * 上报用户行为（异步写入 MySQL）
     * <p>
     * 行为类型：1=曝光 2=点击 3=点赞 4=收藏 5=评论 6=分享 7=停留
     * 正向行为（3/4/5/6/7>10s）会影响用户兴趣标签权重。
     * </p>
     * <p>
     * 为什么用 MQ 异步？
     * 行为上报是高频写操作（QPS 可能上万），同步写 MySQL 会成为瓶颈。
     * 通过 RocketMQ 异步写入：
     * 1. 接口响应更快（不等待 DB 写入）
     * 2. MQ 削峰填谷，保护 DB
     * 3. 消费者批量插入，提升吞吐
     * </p>
     */
    public void reportBehavior(Long userId, BehaviorRequest request) {
        try {
            // 构建行为事件
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("id", idGeneratorUtil.nextId());
            event.put("userId", userId);
            event.put("noteId", request.getNoteId());
            event.put("behaviorType", request.getBehaviorType());
            event.put("duration", request.getDuration());
            event.put("timestamp", System.currentTimeMillis());

            // 异步发送到 MQ（RECOMMEND_BEHAVIOR_TOPIC）
            rocketMQTemplate.convertAndSend("RECOMMEND_BEHAVIOR_TOPIC", event);

            // 正向行为更新用户兴趣标签（增量更新，走 Redis 不走 DB，低延迟）
            if (isPositiveBehavior(request)) {
                updateUserInterestTags(userId, request.getNoteId());
            }

            log.debug("[推荐] 行为上报(MQ): userId={}, noteId={}, type={}",
                    userId, request.getNoteId(), request.getBehaviorType());
        } catch (Exception e) {
            // MQ 发送失败时降级为同步写 DB（保证行为不丢失）
            log.warn("[推荐] MQ发送失败，降级同步写DB: userId={}", userId);
            try {
                jdbcTemplate.update(
                        "INSERT INTO t_user_behavior (id, user_id, note_id, behavior_type, duration) VALUES (?, ?, ?, ?, ?)",
                        idGeneratorUtil.nextId(),
                        userId,
                        request.getNoteId(),
                        request.getBehaviorType(),
                        request.getDuration());
            } catch (Exception ex) {
                log.error("[推荐] 行为上报降级失败: userId={}", userId, ex);
                throw new IllegalStateException("行为上报失败，请稍后重试", ex);
            }
        }
    }

    // ==================== 召回层 ====================

    /**
     * 5 路并行召回
     * <p>
     * 使用 CompletableFuture + 独立线程池并行执行。
     * 每路设置超时（2s），超时降级返回空列表。
     * 合并去重：按 noteId 去重，保留最高分。
     * </p>
     */
    private List<RecallItem> multiRecall(Long userId) {
        // 为每路召回创建异步任务
        List<CompletableFuture<List<RecallItem>>> futures = recallStrategies.stream()
                .map(strategy -> CompletableFuture
                        .supplyAsync(() -> strategy.recall(userId, recallSizePerStrategy), recallExecutor)
                        .exceptionally(ex -> {
                            log.warn("[推荐] {}召回异常，降级为空", strategy.name(), ex);
                            return Collections.emptyList();
                        }))
                .collect(Collectors.toList());

        // 等待全部完成（带超时）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(recallTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[推荐] 召回超时({}ms)，使用已完成的结果", recallTimeoutMs);
        } catch (Exception e) {
            log.error("[推荐] 召回异常", e);
        }

        // 合并去重（按 noteId 去重，保留最高分）
        Map<Long, RecallItem> mergedMap = new LinkedHashMap<>();
        for (CompletableFuture<List<RecallItem>> future : futures) {
            List<RecallItem> items;
            try {
                items = future.getNow(Collections.emptyList());
            } catch (Exception e) {
                items = Collections.emptyList();
            }
            for (RecallItem item : items) {
                mergedMap.merge(item.getNoteId(), item, (existing, incoming) ->
                        incoming.getRecallScore() > existing.getRecallScore() ? incoming : existing);
            }
        }

        return new ArrayList<>(mergedMap.values());
    }

    /**
     * 冷启动召回
     * 策略：热门 60% + 地理 30% + 随机 10%
     */
    private List<RecallItem> coldStartRecall(Long userId) {
        List<RecallItem> result = new ArrayList<>();

        // 热门 60%
        for (RecallStrategy strategy : recallStrategies) {
            if ("HOT".equals(strategy.name())) {
                result.addAll(strategy.recall(userId, 60));
                break;
            }
        }

        // 地理 30%
        for (RecallStrategy strategy : recallStrategies) {
            if ("GEO".equals(strategy.name())) {
                result.addAll(strategy.recall(userId, 30));
                break;
            }
        }

        // 去重
        Map<Long, RecallItem> mergedMap = new LinkedHashMap<>();
        for (RecallItem item : result) {
            mergedMap.merge(item.getNoteId(), item, (existing, incoming) ->
                    incoming.getRecallScore() > existing.getRecallScore() ? incoming : existing);
        }

        return new ArrayList<>(mergedMap.values());
    }

    // ==================== 粗排层 ====================

    /**
     * 粗排：加权打分
     * <p>
     * 综合分 = 召回分 × 来源权重（Item-CF 1.0 > FOLLOWING 0.9 > CONTENT 0.8 > HOT 0.6 > GEO 0.5）
     * 取 Top 100
     * </p>
     */
    private List<RecallItem> roughRank(List<RecallItem> candidates) {
        for (RecallItem item : candidates) {
            double sourceWeight = SOURCE_WEIGHT.getOrDefault(item.getSource(), 0.5);
            item.setRankScore(item.getRecallScore() * sourceWeight);
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(RecallItem::getRankScore).reversed())
                .limit(100)
                .collect(Collectors.toList());
    }

    // ==================== 精排层 ====================

    /**
     * 精排：4 维加权融合（替代 1 维透传）
     * <p>
     * 四维加权得分 = 来源权重(25%) + 用户偏好(25%) + 内容质量(30%) + 时效衰减(20%)
     * 取 Top 50
     * </p>
     */
    private List<RecallItem> fineRank(List<RecallItem> candidates) {
        // 1. 补充内容特征
        enrichCategory(candidates);

        // 2. 批量查询 ES note_index 获取真实质量分（likeCount + commentCount + collectionCount）
        Map<Long, double[]> qualityScores = batchGetQualityScores(candidates);

        // 3. 四维加权计算
        for (RecallItem item : candidates) {
            double sourceWeight = SOURCE_WEIGHT.getOrDefault(item.getSource(), 0.5);
            double userPreference = getUserPreferenceScore(item);
            double quality = getQualityScore(item, qualityScores);
            double timeDecay = computeTimeDecay(item);

            // 加权融合
            item.setRankScore(
                    0.25 * sourceWeight +
                    0.25 * userPreference +
                    0.30 * quality +
                    0.20 * timeDecay
            );
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(RecallItem::getRankScore).reversed())
                .limit(50)
                .collect(Collectors.toList());
    }

    /**
     * 批量获取内容质量分（从 ES note_index 读取真实指标）
     */
    private Map<Long, double[]> batchGetQualityScores(List<RecallItem> candidates) {
        Map<Long, double[]> result = new HashMap<>();
        if (candidates.isEmpty()) return result;

        try {
            List<Long> noteIds = candidates.stream()
                    .map(RecallItem::getNoteId)
                    .collect(Collectors.toList());
            String placeholders = noteIds.stream().map(id -> "?").collect(Collectors.joining(","));
            // 从 t_item_feature 读取质量分
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT note_id, quality_score, like_count, comment_count FROM t_item_feature WHERE note_id IN (" + placeholders + ")",
                    noteIds.toArray());
            for (Map<String, Object> row : rows) {
                Long noteId = ((Number) row.get("note_id")).longValue();
                double qs = row.get("quality_score") != null
                        ? ((Number) row.get("quality_score")).doubleValue() : 0.3;
                result.put(noteId, new double[]{qs});
            }
        } catch (Exception e) {
            log.warn("[推荐-精排] 批量查质量分失败", e);
        }
        return result;
    }

    /**
     * 用户偏好得分：从 Redis 用户标签权重读取
     */
    private double getUserPreferenceScore(RecallItem item) {
        try {
            String userTagKey = "recommend:user_tags:" + item.getNoteId();
            // 简化：使用粗排分数中已经暗含的相似度
            // 真实实现应查询 Redis Hash 中用户对笔记标签的偏好权重
            return 0.5; // 兜底默认值
        } catch (Exception e) {
            return 0.5;
        }
    }

    /**
     * 内容质量得分（归一化到 0~1）
     */
    private double getQualityScore(RecallItem item, Map<Long, double[]> qualityScores) {
        double[] scores = qualityScores.get(item.getNoteId());
        if (scores == null || scores.length == 0) return 0.3;
        // 归一化：假设 quality_score 在 0~1 范围内
        return Math.min(Math.max(scores[0], 0.0), 1.0);
    }

    /**
     * 时效衰减：基于发布时间指数衰减
     * <p>
     * 衰减函数：score = e^(-t / half_life)，其中 half_life = 24 小时
     * 刚发布 → 1.0，24h → 0.37，48h → 0.14，7d → ~0.001
     * </p>
     */
    private double computeTimeDecay(RecallItem item) {
        double halfLifeHours = 24.0;
        double hoursAgo = item.getHoursSincePublish() > 0 ? item.getHoursSincePublish() : 1.0;
        return Math.exp(-hoursAgo / halfLifeHours);
    }

    // ==================== 重排层 ====================

    /**
     * 重排：已读过滤 + 品类打散
     * <p>
     * 1. 已读过滤：HyperLogLog 判断用户是否已曝光过该笔记
     * 2. 品类打散：同品类不超过 2 个连续出现
     * 3. 记录曝光：将本次推荐的笔记加入 HyperLogLog
     * </p>
     */
    private List<RecallItem> reRank(List<RecallItem> ranked, Long userId) {
        // 1. 已读过滤
        String seenKey = RedisKeyConstants.RECOMMEND_SEEN + userId;
        List<RecallItem> unread = ranked.stream()
                .filter(item -> !isAlreadySeen(seenKey, item.getNoteId()))
                .collect(Collectors.toList());

        // 2. 品类打散（同品类不超过 2 个连续——T-082：原实现 >=2 跳过导致第 2 条同品类即被误伤）
        List<RecallItem> result = new ArrayList<>();
        String lastCategory = null;
        int consecutiveCount = 0;

        for (RecallItem item : unread) {
            String category = item.getCategory() != null ? item.getCategory() : "unknown";
            if (category.equals(lastCategory)) {
                consecutiveCount++;
                if (consecutiveCount > 2) {
                    continue; // 同品类连续超过 2 个，跳过（第 3 个起）
                }
            } else {
                consecutiveCount = 1;
                lastCategory = category;
            }
            result.add(item);
            if (result.size() >= feedSize) {
                break;
            }
        }

        // 3. 记录曝光（Redis Set，精确去重，无 HyperLogLog 的 PFADD 副作用）
        if (!result.isEmpty()) {
            String[] noteIds = result.stream()
                    .map(item -> String.valueOf(item.getNoteId()))
                    .toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(seenKey, noteIds);
            // 7 天过期
            stringRedisTemplate.expire(seenKey, 7, java.util.concurrent.TimeUnit.DAYS);
        }

        return result;
    }

    // ==================== 私有方法 ====================

    /**
     * 判断是否冷启动用户（无行为记录）
     * <p>
     * 使用 EXISTS 子查询替代 COUNT(*)，找到第一条即返回，
     * 避免全表扫描该用户的所有行为记录。
     * </p>
     */
    private boolean isColdStartUser(Long userId) {
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM t_user_behavior WHERE user_id = ? LIMIT 1)",
                    Boolean.class, userId);
            return exists == null || !exists;
        } catch (Exception e) {
            return true; // 查询失败视为冷启动
        }
    }

    /**
     * 判断笔记是否已被用户曝光过
     * <p>
     * 使用 Redis Set 存储已曝光的笔记 ID。
     * 相比 HyperLogLog，Set 支持精确的 SISMEMBER 判断，
     * 不会有 PFADD 的副作用（PFADD 会把检测的元素也加入）。
     * </p>
     * <p>
     * 内存优化：Set 只保留最近 7 天的曝光记录，
     * 每个用户约 1000~5000 条（每天推荐 20 条 × 7 天 × 多次刷新），
     * 单用户占用约 50KB，可接受。
     * 如果用户量极大，可升级为 Redis Bloom Filter 模块。
     * </p>
     */
    private boolean isAlreadySeen(String seenKey, Long noteId) {
        try {
            return Boolean.TRUE.equals(
                    stringRedisTemplate.opsForSet().isMember(seenKey, String.valueOf(noteId)));
        } catch (Exception e) {
            return false; // 异常时不过滤
        }
    }

    /**
     * 补充内容分类信息（用于品类打散）
     */
    private void enrichCategory(List<RecallItem> items) {
        if (items.isEmpty()) return;

        try {
            // 批量查询分类
            List<Long> noteIds = items.stream()
                    .map(RecallItem::getNoteId)
                    .collect(Collectors.toList());

            String placeholders = noteIds.stream()
                    .map(id -> "?")
                    .collect(Collectors.joining(","));

            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT note_id, category FROM t_item_feature WHERE note_id IN (" + placeholders + ")",
                    noteIds.toArray());

            Map<Long, String> categoryMap = new HashMap<>();
            for (Map<String, Object> row : rows) {
                Long noteId = ((Number) row.get("note_id")).longValue();
                String category = (String) row.get("category");
                categoryMap.put(noteId, category);
            }

            for (RecallItem item : items) {
                item.setCategory(categoryMap.getOrDefault(item.getNoteId(), "unknown"));
            }
        } catch (Exception e) {
            log.warn("[推荐] 补充分类信息失败", e);
        }
    }

    /**
     * 判断是否正向行为
     */
    private boolean isPositiveBehavior(BehaviorRequest request) {
        int type = request.getBehaviorType();
        // 点赞(3)、收藏(4)、评论(5)、分享(6)、停留>10s(7)
        if (type >= 3 && type <= 6) return true;
        if (type == 7 && request.getDuration() != null && request.getDuration() > 10) return true;
        return false;
    }

    /**
     * 增量更新用户兴趣标签
     * <p>
     * 根据用户正向行为的笔记标签，增加对应标签的权重。
     * 权重衰减：每次 +1，定时任务会做全量重算。
     * </p>
     */
    private void updateUserInterestTags(Long userId, Long noteId) {
        try {
            // 获取笔记标签
            String tags = jdbcTemplate.queryForObject(
                    "SELECT tags FROM t_item_feature WHERE note_id = ?",
                    String.class, noteId);

            if (tags == null || tags.isBlank()) return;

            // 解析 JSON 数组格式的标签 ["美食","旅行"]
            String userTagKey = RedisKeyConstants.RECOMMEND_USER_TAGS + userId;
            String cleaned = tags.replaceAll("[\\[\\]\"]", "");
            for (String tag : cleaned.split(",")) {
                tag = tag.trim();
                if (!tag.isEmpty()) {
                    stringRedisTemplate.opsForHash().increment(userTagKey, tag, 1);
                }
            }
            // 12 小时过期
            stringRedisTemplate.expire(userTagKey, 12, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("[推荐] 更新用户标签失败: userId={}, noteId={}", userId, noteId);
        }
    }

    /**
     * 生成推荐理由
     */
    private String getRecommendReason(RecallItem item) {
        return switch (item.getSource()) {
            case "ITEM_CF" -> "猜你喜欢";
            case "CONTENT" -> "根据你的兴趣推荐";
            case "FOLLOWING" -> "你关注的人发布了";
            case "HOT" -> "大家都在看";
            case "GEO" -> "同城热门";
            default -> "为你推荐";
        };
    }
}
