package com.myxhs.home.service;

import com.myxhs.common.response.R;
import com.myxhs.home.dto.NoteCardVO;
import com.myxhs.home.dto.UserProfileAggVO;
import com.myxhs.home.exception.DownstreamUnavailableException;
import com.myxhs.home.feign.AnalyticsFeignClient;
import com.myxhs.home.feign.ContentFeignClient;
import com.myxhs.home.feign.CounterFeignClient;
import com.myxhs.home.feign.UserFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 用户主页聚合服务
 * <p>
 * 核心职责：将用户主页所需的多源数据并行聚合为一个完整的 VO。
 * <p>
 * 聚合编排（单层并行，所有数据源无依赖关系）：
 * 并行：用户信息 + 计数 + 关注关系 + 用户笔记列表
 * <p>
 * 降级策略：
 * - 用户服务不可用 → 返回 null（用户不存在）
 * - 其他服务不可用 → 对应字段返回默认值
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileAggService {

    private final UserFeignClient userFeignClient;
    private final AnalyticsFeignClient analyticsFeignClient;
    private final ContentFeignClient contentFeignClient;
    private final CounterFeignClient counterFeignClient;
    private final ExecutorService aggregatorPool;

    /**
     * 聚合用户主页
     *
     * @param targetUserId 目标用户ID
     * @param currentUserId 当前登录用户ID（可为 null，未登录时社交状态全部为 false）
     */
    @SuppressWarnings("unchecked")
    public UserProfileAggVO getUserProfile(Long targetUserId, Long currentUserId) {

        // ========== 单层并行：所有数据源无依赖关系 ==========

        // 1. 用户基本信息
        CompletableFuture<Map<String, Object>> userFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                         R<Map<String, Object>> r = userFeignClient.getUserPublicInfo(targetUserId);
                         if (r != null && r.getCode() == 503) {
                             throw new DownstreamUnavailableException("用户服务不可用");
                         }
                         return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.emptyMap();
                     } catch (DownstreamUnavailableException e) {
                         throw e;
                     } catch (Exception e) {
                         log.warn("[用户主页] 获取用户信息失败: userId={}", targetUserId, e);
                         return Collections.emptyMap();
                     }
                }, aggregatorPool);

        // 2. 计数（粉丝数/关注数 — 从 analytics 直接取，不绕 counter）
        CompletableFuture<Map<String, Long>> counterFuture = CompletableFuture
                .supplyAsync(() -> {
                    Map<String, Long> result = new HashMap<>();
                    try {
                        R<Long> flwR = analyticsFeignClient.getFollowerCount(targetUserId);
                        R<Long> fwgR = analyticsFeignClient.getFollowingCount(targetUserId);
                        result.put("follower", flwR != null && flwR.isSuccess() ? flwR.getData() : 0L);
                        result.put("following", fwgR != null && fwgR.isSuccess() ? fwgR.getData() : 0L);
                    } catch (Exception e) {
                        log.warn("[用户主页] 获取计数失败: userId={}", targetUserId, e);
                    }
                    return result;
                }, aggregatorPool);

        // 3. 关注关系
        CompletableFuture<Map<String, Boolean>> relationFuture = CompletableFuture
                .supplyAsync(() -> {
                    if (currentUserId == null || currentUserId.equals(targetUserId)) {
                        return Collections.<String, Boolean>emptyMap();
                    }
                    try {
                        R<Map<String, Boolean>> r = analyticsFeignClient.checkRelation(currentUserId, targetUserId);
                        return (r != null && r.isSuccess() && r.getData() != null) ? r.getData() : Collections.emptyMap();
                    } catch (Exception e) {
                        log.warn("[用户主页] 查询关注关系失败: currentUser={}, targetUser={}", currentUserId, targetUserId);
                        return Collections.<String, Boolean>emptyMap();
                    }
                }, aggregatorPool);

        // 4. 用户笔记列表（前 10 条）+ 已发布笔记总数（用于 noteCount）
        long[] noteCountHolder = {0};
        CompletableFuture<List<NoteCardVO>> notesFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        R<Map<String, Object>> r = contentFeignClient.getUserNotes(targetUserId, 1, 10);
                        if (r != null && r.isSuccess() && r.getData() != null) {
                            // content 返回 PageResult：字段是 records / total（修复：原误读 list 导致笔记列表为空）
                            Object records = r.getData().get("records");
                            Object total = r.getData().get("total");
                            if (total != null) {
                                // T-119：R4 全局 Long→ToStringSerializer 使 total 为 String——原 instanceof Number 恒 false → noteCount 恒 0
                                noteCountHolder[0] = toLongValue(total, 0L);
                            }
                            if (records instanceof List) {
                                List<Map<String, Object>> noteList = (List<Map<String, Object>>) records;
                                return noteList.stream().map(this::mapToNoteCard).toList();
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[用户主页] 获取用户笔记失败: userId={}", targetUserId);
                    }
                    return Collections.<NoteCardVO>emptyList();
                }, aggregatorPool);

        // 5. 获赞+收藏数：拉取用户全部已发布笔记，sum 其 like(1)+collect(2) 计数
        CompletableFuture<Long> likeCollectFuture = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        List<Long> noteIds = new ArrayList<>();
                        int page = 1;
                        final int size = 50;
                        long total = Long.MAX_VALUE;
                        while (noteIds.size() < total && page <= 20) { // 上限保护，避免过多分页
                            R<Map<String, Object>> r = contentFeignClient.getUserNotes(targetUserId, page, size);
                            if (r == null || !r.isSuccess() || r.getData() == null) break;
                            Object totalObj = r.getData().get("total");
                            if (totalObj instanceof Number) total = ((Number) totalObj).longValue();
                            Object records = r.getData().get("records");
                            if (!(records instanceof List)) break;
                            List<?> recs = (List<?>) records;
                            for (Object o : recs) {
                                Object id = ((Map<?, ?>) o).get("id");
                                if (id instanceof Number) noteIds.add(((Number) id).longValue());
                            }
                            if (recs.size() < size) break;
                            page++;
                        }
                        if (noteIds.isEmpty()) return 0L;

                        List<Map<String, Object>> queries = new ArrayList<>();
                        for (Long id : noteIds) {
                            queries.add(Map.of("targetType", 1, "targetId", id, "countTypes", List.of(1, 2)));
                        }
                        R<Map<String, Map<String, Long>>> cr = counterFeignClient.batchGetCounts(Map.of("queries", queries));
                        if (cr == null || !cr.isSuccess() || cr.getData() == null) return 0L;
                        long sum = 0L;
                        for (Long id : noteIds) {
                            Map<String, Long> m = cr.getData().get("1:" + id);
                            if (m == null) continue;
                            sum += m.getOrDefault("like", 0L) + m.getOrDefault("collect", 0L);
                        }
                        return sum;
                    } catch (Exception e) {
                        log.warn("[用户主页] 获取获赞收藏数失败: userId={}", targetUserId);
                        return 0L;
                    }
                }, aggregatorPool);

        // 等待所有并行任务完成（总超时 3 秒）
        try {
            CompletableFuture.allOf(userFuture, counterFuture, relationFuture, notesFuture, likeCollectFuture)
                    .get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[用户主页] 聚合超时，部分数据降级");
        } catch (Exception e) {
            log.warn("[用户主页] 聚合异常", e);
        }

        Map<String, Object> userData = userFuture.getNow(Collections.emptyMap());
        if (userFuture.isCompletedExceptionally()) {
            throw new DownstreamUnavailableException("用户服务不可用");
        }
        if (userData.isEmpty()) {
            return null;
        }

        Map<String, Long> counters = counterFuture.getNow(Collections.emptyMap());
        Map<String, Boolean> relation = relationFuture.getNow(Collections.emptyMap());
        List<NoteCardVO> notes = notesFuture.getNow(Collections.emptyList());
        Long likeCollect = likeCollectFuture.getNow(0L);

        // ========== 组装 VO ==========
        return UserProfileAggVO.builder()
                .userId(targetUserId)
                .nickname((String) userData.get("nickname"))
                .avatar((String) userData.get("avatar"))
                .bio((String) userData.get("bio"))
                .followingCount(counters.getOrDefault("following", 0L))
                .followerCount(counters.getOrDefault("follower", 0L))
                .likeAndCollectCount(likeCollect)
                .noteCount(noteCountHolder[0])
                .isFollowing(relation.getOrDefault("isFollowing", false))
                .isFollowBack(relation.getOrDefault("isFollowBack", false))
                .isMutual(relation.getOrDefault("isMutual", false))
                .notes(notes)
                .build();
    }

    /**
     * 将笔记 Map 转换为 NoteCardVO
     */
    private NoteCardVO mapToNoteCard(Map<String, Object> note) {
        return NoteCardVO.builder()
                .noteId(toLongValue(note.get("id")))
                .title((String) note.get("title"))
                .coverUrl((String) note.get("coverUrl"))
                .noteType(toIntValue(note.get("noteType"), 0))
                .build();
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
