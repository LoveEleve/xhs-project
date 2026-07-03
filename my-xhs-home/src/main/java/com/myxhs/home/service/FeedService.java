package com.myxhs.home.service;

import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.response.R;
import com.myxhs.home.dto.FeedVO;
import com.myxhs.home.dto.NoteCardVO;
import com.myxhs.home.feign.AnalyticsFeignClient;
import com.myxhs.home.feign.ContentFeignClient;
import com.myxhs.home.feign.CounterFeignClient;
import com.myxhs.home.feign.NotificationFeignClient;
import com.myxhs.home.feign.UserFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Feed 流服务
 * <p>
 * 核心职责：
 * 1. 从 Redis 收件箱/发件箱读取 Feed 流笔记 ID 列表
 * 2. CompletableFuture 并行聚合下游服务数据
 * 3. 游标分页（基于时间戳 score）
 * </p>
 * <p>
 * 推拉混合模型：
 * - 普通用户发笔记 → 推送到粉丝收件箱（写扩散，由 MQ Consumer 执行）
 * - 大V发笔记 → 写入自己的发件箱（粉丝读取时实时拉取）
 * - 读取 Feed → 收件箱 + 关注的大V发件箱 → 合并排序 → 游标分页
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final StringRedisTemplate stringRedisTemplate;
    private final ContentFeignClient contentFeignClient;
    private final UserFeignClient userFeignClient;
    private final AnalyticsFeignClient analyticsFeignClient;
    private final CounterFeignClient counterFeignClient;
    private final NotificationFeignClient notificationFeignClient;
    private final ExecutorService aggregatorPool;
    private final ExecutorService batchFeignPool;

    @Value("${home.feed.big-v-threshold:100000}")
    private long bigVThreshold;

    @Value("${home.feed.default-page-size:20}")
    private int defaultPageSize;

    /**
     * 获取关注 Feed 流（推拉混合 + 游标分页 + 并行聚合）
     *
     * @param userId    当前用户ID
     * @param lastScore 上一页最后一条的 score（首次传 null 或 0）
     * @param size      每页大小
     */
    public FeedVO getFollowFeed(Long userId, Double lastScore, int size) {
        if (size <= 0 || size > 50) {
            size = defaultPageSize;
        }
        if (lastScore == null || lastScore <= 0) {
            lastScore = Double.MAX_VALUE; // 首次请求，从最新开始
        }

        // ========== 第 1 步：从 Redis 获取笔记 ID 列表 ==========

        // 1a. 从收件箱拉取（推模式笔记）
        String inboxKey = RedisKeyConstants.FEED_INBOX + userId;
        Set<ZSetOperations.TypedTuple<String>> inboxTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(inboxKey, 0, lastScore - 0.001, 0, size);

        // 1b. 获取用户关注的大V列表，从大V发件箱拉取
        List<ZSetOperations.TypedTuple<String>> bigVTuples = pullBigVOutbox(userId, lastScore, size);

        // 1c. 合并 + 按 score 降序排序 + 取 Top size
        List<ZSetOperations.TypedTuple<String>> merged = mergeAndSort(inboxTuples, bigVTuples, size);

        if (merged.isEmpty()) {
            return FeedVO.builder()
                    .notes(Collections.emptyList())
                    .hasMore(false)
                    .unreadCount(0)
                    .build();
        }

        // 提取 noteId 列表和最小 score（下一页游标）
        List<Long> noteIds = merged.stream()
                .map(t -> Long.valueOf(t.getValue()))
                .collect(Collectors.toList());
        Double minScore = merged.get(merged.size() - 1).getScore();

        // ========== 第 2 步：CompletableFuture 并行聚合 ==========
        return aggregateFeed(userId, noteIds, minScore, size);
    }

    /**
     * 并行聚合 Feed 数据
     * <p>
     * 2 层并行编排：
     * 第 1 层（并行）：笔记详情 + 点赞状态 + 未读数
     * 第 2 层（依赖第 1 层的 noteIds → authorIds）：作者信息 + 计数
     * </p>
     */
    private FeedVO aggregateFeed(Long userId, List<Long> noteIds, Double minScore, int requestSize) {
        // ---- 第 1 层并行 ----

        // 1a. 批量获取笔记详情（逐个调用，后续可优化为批量接口）
        CompletableFuture<Map<Long, Map<String, Object>>> notesFuture = CompletableFuture
                .supplyAsync(() -> batchGetNoteDetails(noteIds), aggregatorPool);

        // 1b. 批量查询点赞状态
        String bizIds = noteIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        CompletableFuture<Map<Long, Boolean>> likesFuture = CompletableFuture
                .supplyAsync(() -> {
                    R<Map<Long, Boolean>> r = analyticsFeignClient.batchCheckLikeStatus(userId, 1, bizIds);
                    return r != null && r.isSuccess() && r.getData() != null ? r.getData() : Collections.emptyMap();
                }, aggregatorPool);

        // 1c. 获取未读通知数
        CompletableFuture<Integer> unreadFuture = CompletableFuture
                .supplyAsync(() -> {
                    R<Map<String, Object>> r = notificationFeignClient.getUnreadCount(userId);
                    if (r != null && r.isSuccess() && r.getData() != null) {
                        Object total = r.getData().get("total");
                        return total != null ? ((Number) total).intValue() : 0;
                    }
                    return 0;
                }, aggregatorPool);

        // 等待第 1 层完成（总超时 3 秒，防止下游卡死拖垮线程池）
        try {
            CompletableFuture.allOf(notesFuture, likesFuture, unreadFuture)
                    .get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[Feed] 第1层聚合超时，部分数据降级");
        } catch (Exception e) {
            log.warn("[Feed] 第1层聚合异常", e);
        }

        Map<Long, Map<String, Object>> notesMap = notesFuture.getNow(Collections.emptyMap());
        Map<Long, Boolean> likesMap = likesFuture.getNow(Collections.emptyMap());
        int unreadCount = unreadFuture.getNow(0);

        // 提取作者 ID 列表
        Set<Long> authorIds = new HashSet<>();
        for (Map<String, Object> note : notesMap.values()) {
            Object uid = note.get("userId");
            if (uid != null) {
                authorIds.add(((Number) uid).longValue());
            }
        }

        // ---- 第 2 层并行（依赖第 1 层结果） ----

        // 2a. 批量获取作者信息
        CompletableFuture<Map<Long, Map<String, Object>>> usersFuture = CompletableFuture
                .supplyAsync(() -> batchGetUserInfos(authorIds), aggregatorPool);

        // 2b. 批量获取计数
        CompletableFuture<Map<String, Map<String, Long>>> countersFuture = CompletableFuture
                .supplyAsync(() -> batchGetCounters(noteIds), aggregatorPool);

        // 等待第 2 层完成（总超时 2 秒）
        try {
            CompletableFuture.allOf(usersFuture, countersFuture)
                    .get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[Feed] 第2层聚合超时，部分数据降级");
        } catch (Exception e) {
            log.warn("[Feed] 第2层聚合异常", e);
        }

        Map<Long, Map<String, Object>> usersMap = usersFuture.getNow(Collections.emptyMap());
        Map<String, Map<String, Long>> countersMap = countersFuture.getNow(Collections.emptyMap());

        // ========== 第 3 步：组装 NoteCardVO 列表 ==========
        List<NoteCardVO> cards = new ArrayList<>();
        for (Long noteId : noteIds) {
            Map<String, Object> note = notesMap.get(noteId);
            if (note == null || note.isEmpty()) {
                continue; // 笔记不存在或已删除，跳过
            }

            Long authorId = note.get("userId") != null ? ((Number) note.get("userId")).longValue() : null;
            Map<String, Object> author = authorId != null ? usersMap.getOrDefault(authorId, Collections.emptyMap()) : Collections.emptyMap();
            String counterKey = "1:" + noteId; // targetType=1(笔记):targetId
            Map<String, Long> counters = countersMap.getOrDefault(counterKey, Collections.emptyMap());

            NoteCardVO card = NoteCardVO.builder()
                    .noteId(noteId)
                    .title((String) note.get("title"))
                    .coverUrl((String) note.get("coverUrl"))
                    .noteType(note.get("noteType") != null ? ((Number) note.get("noteType")).intValue() : 0)
                    .authorId(authorId)
                    .authorNickname((String) author.get("nickname"))
                    .authorAvatar((String) author.get("avatar"))
                    .likeCount(counters.getOrDefault("like", 0L))
                    .collectCount(counters.getOrDefault("collect", 0L))
                    .commentCount(counters.getOrDefault("comment", 0L))
                    .isLiked(likesMap.getOrDefault(noteId, false))
                    .isCollected(false) // 收藏状态需要额外接口，暂不聚合
                    .isFollowed(true) // Feed 流中的笔记都是关注的人发的
                    .build();

            cards.add(card);
        }

        return FeedVO.builder()
                .notes(cards)
                .nextCursor(minScore != null ? formatCursor(minScore) : null)
                .hasMore(cards.size() >= requestSize)
                .unreadCount(unreadCount)
                .build();
    }

    // ==================== 私有方法 ====================

    /**
     * 从关注的大V发件箱拉取笔记（拉模式）
     * <p>
     * 优化：使用 Pipeline 一次性查询所有大V的发件箱，避免串行 N 次网络往返。
     * </p>
     */
    @SuppressWarnings("unchecked")
    private List<ZSetOperations.TypedTuple<String>> pullBigVOutbox(Long userId, Double lastScore, int size) {
        // 获取用户关注列表中的大V
        List<Long> bigVIds = getFollowingBigVIds(userId);
        if (bigVIds.isEmpty()) {
            return Collections.emptyList();
        }

        int perBigV = Math.max(1, size / bigVIds.size());
        double maxScore = lastScore - 0.001;

        // 使用 Pipeline 批量查询所有大V的发件箱（1 次网络往返替代 N 次）
        List<Object> pipelineResults = stringRedisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (Long bigVId : bigVIds) {
                        String outboxKey = RedisKeyConstants.FEED_OUTBOX + bigVId;
                        byte[] keyBytes = outboxKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        // 使用低层 ZREVRANGEBYSCOREWITHSCORES 命令
                        connection.zSetCommands().zRevRangeByScoreWithScores(
                                keyBytes,
                                0.0,
                                maxScore,
                                0,
                                perBigV
                        );
                    }
                    return null;
                });

        List<ZSetOperations.TypedTuple<String>> result = new ArrayList<>();
        for (Object obj : pipelineResults) {
            if (obj instanceof Set) {
                Set<ZSetOperations.TypedTuple<String>> tuples = (Set<ZSetOperations.TypedTuple<String>>) obj;
                if (tuples != null) {
                    result.addAll(tuples);
                }
            }
        }
        return result;
    }

    /**
     * 获取用户关注的大V ID 列表
     * <p>
     * 从 Redis 关注列表中筛选粉丝数 > bigVThreshold 的用户。
     * 使用 MGET 批量查询大V标记（1 次网络往返），避免 N+1 问题。
     * </p>
     */
    private List<Long> getFollowingBigVIds(Long userId) {
        String followingKey = RedisKeyConstants.FOLLOW_LIST + userId;
        Set<String> followingIds = stringRedisTemplate.opsForZSet().reverseRange(followingKey, 0, 499);
        if (followingIds == null || followingIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建所有大V标记 Key，使用 MGET 一次网络往返批量查询
        List<String> followingList = new ArrayList<>(followingIds);
        List<String> bigVKeys = followingList.stream()
                .map(fid -> "myxhs:user:bigv:" + fid)
                .collect(Collectors.toList());

        List<String> bigVValues = stringRedisTemplate.opsForValue().multiGet(bigVKeys);
        if (bigVValues == null) {
            return Collections.emptyList();
        }

        List<Long> bigVIds = new ArrayList<>();
        for (int i = 0; i < followingList.size(); i++) {
            String value = bigVValues.get(i);
            if ("1".equals(value)) {
                bigVIds.add(Long.valueOf(followingList.get(i)));
            }
            // 缓存不存在（null）时不查询粉丝数，由定时任务或粉丝数变更事件刷新
        }
        return bigVIds;
    }

    /**
     * 合并收件箱和大V发件箱的笔记，按 score 降序排序，取 Top size
     */
    private List<ZSetOperations.TypedTuple<String>> mergeAndSort(
            Set<ZSetOperations.TypedTuple<String>> inbox,
            List<ZSetOperations.TypedTuple<String>> bigV,
            int size) {

        Stream<ZSetOperations.TypedTuple<String>> stream1 = inbox != null ? inbox.stream() : Stream.empty();
        Stream<ZSetOperations.TypedTuple<String>> stream2 = bigV != null ? bigV.stream() : Stream.empty();

        return Stream.concat(stream1, stream2)
                .filter(t -> t.getScore() != null)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore())) // 降序
                .limit(size)
                .collect(Collectors.toList());
    }

    /**
     * 格式化游标值，保持 score 的完整精度
     * <p>
     * 当前 score 为毫秒时间戳（整数），使用 longValue 截断无精度丢失。
     * 但如果未来 score 设计包含小数（如热度权重），需要保留完整精度，
     * 否则分页时会跳过 score 相同的记录。
     * 使用 String.valueOf(double) 保留完整精度作为兜底。
     * </p>
     */
    private String formatCursor(Double score) {
        if (score == null) {
            return null;
        }
        // 如果是整数值（毫秒时间戳），用 long 格式避免科学计数法
        if (score == Math.floor(score) && !Double.isInfinite(score)) {
            return String.valueOf(score.longValue());
        }
        // 包含小数的 score，保留完整精度
        return String.valueOf(score);
    }

    /**
     * 批量获取笔记详情（并行 Feign 调用）
     * <p>
     * 由于下游 content 服务暂无批量接口，使用 CompletableFuture 并行调用单个接口。
     * 20 条笔记并行调用，总耗时 ≈ 单次调用耗时（而非 20 倍）。
     * </p>
     */
    private Map<Long, Map<String, Object>> batchGetNoteDetails(List<Long> noteIds) {
        Map<Long, CompletableFuture<Map.Entry<Long, Map<String, Object>>>> futures = new LinkedHashMap<>();
        for (Long noteId : noteIds) {
            CompletableFuture<Map.Entry<Long, Map<String, Object>>> future = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            R<Map<String, Object>> r = contentFeignClient.getNoteDetail(noteId);
                            if (r != null && r.isSuccess() && r.getData() != null) {
                                return Map.entry(noteId, r.getData());
                            }
                        } catch (Exception e) {
                            log.warn("[Feed] 获取笔记详情失败: noteId={}", noteId);
                        }
                        return null;
                    }, batchFeignPool);
            futures.put(noteId, future);
        }

        // 使用 allOf 并行等待所有 future，总超时 3 秒（而非 N × 2 秒）
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Feed] 批量获取笔记详情超时(部分降级)");
        }

        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<Long, CompletableFuture<Map.Entry<Long, Map<String, Object>>>> entry : futures.entrySet()) {
            try {
                Map.Entry<Long, Map<String, Object>> pair = entry.getValue().getNow(null);
                if (pair != null) {
                    result.put(pair.getKey(), pair.getValue());
                }
            } catch (Exception e) {
                log.warn("[Feed] 获取笔记详情异常: noteId={}", entry.getKey());
            }
        }
        return result;
    }

    /**
     * 批量获取用户信息（并行 Feign 调用，allOf 并行等待）
     */
    private Map<Long, Map<String, Object>> batchGetUserInfos(Set<Long> userIds) {
        Map<Long, CompletableFuture<Map.Entry<Long, Map<String, Object>>>> futures = new HashMap<>();
        for (Long uid : userIds) {
            CompletableFuture<Map.Entry<Long, Map<String, Object>>> future = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            R<Map<String, Object>> r = userFeignClient.getUserPublicInfo(uid);
                            if (r != null && r.isSuccess() && r.getData() != null) {
                                return Map.entry(uid, r.getData());
                            }
                        } catch (Exception e) {
                            log.warn("[Feed] 获取用户信息失败: userId={}", uid);
                        }
                        return null;
                    }, batchFeignPool);
            futures.put(uid, future);
        }

        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Feed] 批量获取用户信息超时(部分降级)");
        }

        Map<Long, Map<String, Object>> result = new HashMap<>();
        for (Map.Entry<Long, CompletableFuture<Map.Entry<Long, Map<String, Object>>>> entry : futures.entrySet()) {
            try {
                Map.Entry<Long, Map<String, Object>> pair = entry.getValue().getNow(null);
                if (pair != null) {
                    result.put(pair.getKey(), pair.getValue());
                }
            } catch (Exception e) {
                log.warn("[Feed] 获取用户信息异常: userId={}", entry.getKey());
            }
        }
        return result;
    }

    /**
     * 批量获取计数
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Long>> batchGetCounters(List<Long> noteIds) {
        try {
            // 构建批量查询请求
            List<Map<String, Object>> queries = noteIds.stream()
                    .map(noteId -> {
                        Map<String, Object> q = new HashMap<>();
                        q.put("targetType", 1); // 笔记
                        q.put("targetId", noteId);
                        q.put("countTypes", List.of(1, 2, 3)); // 1=点赞 2=收藏 3=评论
                        return q;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> request = Map.of("queries", queries);
            R<Map<String, Map<String, Long>>> r = counterFeignClient.batchGetCounts(request);
            if (r != null && r.isSuccess() && r.getData() != null) {
                return r.getData();
            }
        } catch (Exception e) {
            log.warn("[Feed] 批量获取计数失败", e);
        }
        return Collections.emptyMap();
    }
}
