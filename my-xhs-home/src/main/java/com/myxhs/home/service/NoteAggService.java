package com.myxhs.home.service;

import com.myxhs.common.response.R;
import com.myxhs.home.dto.NoteDetailAggVO;
import com.myxhs.home.exception.DownstreamUnavailableException;
import com.myxhs.home.feign.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 笔记详情聚合服务
 * <p>
 * 核心职责：将笔记详情页所需的多源数据并行聚合为一个完整的 VO。
 * <p>
 * 聚合编排（2 层并行）：
 * 第 1 层（并行）：笔记详情 + 点赞状态 + 收藏状态 + 计数
 * 第 2 层（依赖第 1 层的 authorId）：作者信息 + 关注关系 + 热门评论
 * <p>
 * 降级策略：任何下游服务超时/异常，对应字段返回默认值，不影响整体。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteAggService {

    /** 聚合全局超时（毫秒），可用 myxhs.home.agg.global-timeout-ms 覆盖 */
    @org.springframework.beans.factory.annotation.Value("${myxhs.home.agg.global-timeout-ms:4000}")
    private long globalTimeoutMs;

    /** 第 1 层聚合超时（毫秒），A3：从硬编码 3s 下调，避免并发下 3s 尾延迟 */
    @org.springframework.beans.factory.annotation.Value("${myxhs.home.agg.layer1-timeout-ms:800}")
    private long layer1TimeoutMs;

    private final ContentFeignClient contentFeignClient;
    private final UserFeignClient userFeignClient;
    private final AnalyticsFeignClient analyticsFeignClient;
    private final CounterFeignClient counterFeignClient;
    /**
     * 内层下游调用池（A3 修复线程池饥饿）：
     * 外层请求占用 aggregatorPool，若内层 future 也提交到同池，
     * 核心线程被外层占满 + LinkedBlockingQueue 不触发扩容 → 内层任务饿死直到超时。
     * 内层统一改用独立的 batchFeignPool（30/80/500）隔离。
     */
    private final ExecutorService batchFeignPool;

    /**
     * 聚合笔记详情
     *
     * @param noteId 笔记ID
     * @param userId 当前登录用户ID（可为 null，未登录时社交状态全部为 false）
     */
    @SuppressWarnings("unchecked")
    public NoteDetailAggVO getNoteDetail(Long noteId, Long userId) {

        // 全局请求级超时控制（可配，默认 4s）
        long startTime = System.nanoTime();

        // ========== 第 1 层并行：笔记详情 + 社交状态 + 计数 ==========

        // 1a. 笔记详情
        CompletableFuture<R<Map<String, Object>>> noteFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        R<Map<String, Object>> r = contentFeignClient.getNoteDetail(noteId);
                        return r != null ? r : R.fail(503, "content服务无响应");
                    } catch (Exception e) {
                        log.warn("[笔记详情] 获取笔记详情异常: noteId={}", noteId, e);
                        return R.fail(503, "content服务异常");
                    }
                }, batchFeignPool);

        // 1b. 点赞状态
        CompletableFuture<Boolean> likeFuture = CompletableFuture
                .supplyAsync(() -> {
                    if (userId == null) return false;
                    try {
                        R<Map<Long, Boolean>> r = analyticsFeignClient.batchCheckLikeStatus(userId, 1, String.valueOf(noteId));
                        return (r != null && r.isSuccess() && r.getData() != null)
                                ? r.getData().getOrDefault(noteId, false) : false;
                    } catch (Exception e) {
                        log.warn("[笔记详情] 查询点赞状态失败: noteId={}", noteId, e);
                        return false;
                    }
                }, batchFeignPool);

        // 1c. 收藏状态
        CompletableFuture<Boolean> collectFuture = CompletableFuture
                .supplyAsync(() -> {
                    if (userId == null) return false;
                    try {
                        R<Boolean> r = analyticsFeignClient.checkFavoriteStatus(userId, noteId);
                        return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : false;
                    } catch (Exception e) {
                        log.warn("[笔记详情] 查询收藏状态失败: noteId={}", noteId);
                        return false;
                    }
                }, batchFeignPool);

        // 1d. 计数（点赞/收藏/评论）
        CompletableFuture<Map<String, Long>> counterFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        Map<String, Object> query = new HashMap<>();
                        query.put("targetType", 1);
                        query.put("targetId", noteId);
                        query.put("countTypes", List.of(1, 2, 3));
                        Map<String, Object> request = Map.of("queries", List.of(query));
                        R<Map<String, Map<String, Long>>> r = counterFeignClient.batchGetCounts(request);
                        if (r != null && r.isSuccess() && r.getData() != null) {
                            String key = "1:" + noteId;
                            return r.getData().getOrDefault(key, Collections.emptyMap());
                        }
                    } catch (Exception e) {
                        log.warn("[笔记详情] 获取计数失败: noteId={}", noteId);
                    }
                    return Collections.emptyMap();
                }, batchFeignPool);

        // 等待第 1 层完成（总超时 3 秒）
        try {
            CompletableFuture.allOf(noteFuture, likeFuture, collectFuture, counterFuture)
                    .get(layer1TimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[笔记详情] 第1层聚合超时，部分数据降级");
        } catch (Exception e) {
            log.warn("[笔记详情] 第1层聚合异常", e);
        }

        R<Map<String, Object>> noteResult = noteFuture.getNow(R.fail(503, "超时降级"));
        // 区分"服务降级"和"笔记不存在"：
        // - 服务降级（503）：不返回 null，而是抛出异常让上层感知
        // - 数据为空（成功但 data 为 null/empty）：笔记确实不存在
        if (noteResult == null || !noteResult.isSuccess()) {
            log.warn("[笔记详情] content服务不可用，无法获取笔记: noteId={}", noteId);
            throw new DownstreamUnavailableException("内容服务不可用");
        }
        Map<String, Object> noteData = noteResult.getData();
        if (noteData == null || noteData.isEmpty()) {
            return null; // 笔记确实不存在
        }

        Boolean isLiked = likeFuture.getNow(false);
        Boolean isCollected = collectFuture.getNow(false);
        Map<String, Long> counters = counterFuture.getNow(Collections.emptyMap());

        // 提取作者 ID（R4：Long 序列化为 String，兼容转换）
        Long authorId = toLongValue(noteData.get("userId"));

        // ========== 第 2 层并行（依赖第 1 层的 authorId）：作者信息 + 关注关系 + 热门评论 ==========

        // 2a. 作者信息
        CompletableFuture<Map<String, Object>> authorFuture = CompletableFuture
                .supplyAsync(() -> {
                    if (authorId == null) return Collections.<String, Object>emptyMap();
                    R<Map<String, Object>> r = userFeignClient.getUserPublicInfo(authorId);
                    return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.emptyMap();
                }, batchFeignPool);

        // 2b. 关注关系
        CompletableFuture<Map<String, Boolean>> relationFuture = CompletableFuture
                .supplyAsync(() -> {
                    if (userId == null || authorId == null || userId.equals(authorId)) {
                        return Collections.<String, Boolean>emptyMap();
                    }
                    R<Map<String, Boolean>> r = analyticsFeignClient.checkRelation(userId, authorId);
                    return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.emptyMap();
                }, batchFeignPool);

        // 2c. 热门评论（前 3 条）
        CompletableFuture<List<Map<String, Object>>> commentsFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        R<Map<String, Object>> r = contentFeignClient.getCommentPage(noteId, 1, 3);
                        if (r != null && r.isSuccess() && r.getData() != null) {
                            Object list = r.getData().get("list");
                            if (list instanceof List) {
                                return (List<Map<String, Object>>) list;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[笔记详情] 获取热门评论失败: noteId={}", noteId);
                    }
                    return Collections.<Map<String, Object>>emptyList();
                }, batchFeignPool);

        // 等待第 2 层完成（动态超时：全局超时 - 第1层已用时间）
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        long layer2TimeoutMs = Math.max(500, globalTimeoutMs - elapsedMs); // 至少 500ms
        try {
            CompletableFuture.allOf(authorFuture, relationFuture, commentsFuture)
                    .get(layer2TimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[笔记详情] 第2层聚合超时({}ms)，部分数据降级", layer2TimeoutMs);
        } catch (Exception e) {
            log.warn("[笔记详情] 第2层聚合异常", e);
        }

        Map<String, Object> authorData = authorFuture.getNow(Collections.emptyMap());
        Map<String, Boolean> relationData = relationFuture.getNow(Collections.emptyMap());
        List<Map<String, Object>> hotComments = commentsFuture.getNow(Collections.emptyList());

        // ========== 组装 VO ==========
        return NoteDetailAggVO.builder()
                .noteId(noteId)
                .title((String) noteData.get("title"))
                .content((String) noteData.get("content"))
                .images(noteData.get("images") instanceof List ? (List<String>) noteData.get("images") : Collections.emptyList())
                .videoUrl((String) noteData.get("videoUrl"))
                .coverUrl((String) noteData.get("coverUrl"))
                .noteType(toIntValue(noteData.get("noteType"), 0))
                .tags(noteData.get("tags") instanceof List ? (List<String>) noteData.get("tags") : Collections.emptyList())
                .createdAt(parseDateTime(noteData.get("createdAt")))
                .authorId(authorId)
                .authorNickname((String) authorData.get("nickname"))
                .authorAvatar((String) authorData.get("avatar"))
                .likeCount(toLongValue(counters.get("like"), 0L))
                .collectCount(toLongValue(counters.get("collect"), 0L))
                .commentCount(toLongValue(counters.get("comment"), 0L))
                .isLiked(isLiked)
                .isCollected(isCollected)
                .isFollowed(relationData.getOrDefault("isFollowing", false))
                .hotComments(hotComments)
                .build();
    }

    /**
     * 安全解析日期时间
     */
    private LocalDateTime parseDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime) return (LocalDateTime) value;
        try {
            return LocalDateTime.parse(value.toString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 兼容 Long→ToStringSerializer（R4）：下游 Feign 返回的 Long 字段实际为 String
     */
    private Long toLongValue(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLongValue(Object o, long def) {
        Long v = toLongValue(o);
        return v != null ? v : def;
    }

    private int toIntValue(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return def;
        }
    }
}
