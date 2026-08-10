package com.myxhs.search.controller;

import com.myxhs.common.response.R;
import com.myxhs.search.dto.BehaviorRequest;
import com.myxhs.search.dto.RecommendFeedVO;
import com.myxhs.search.job.RecommendComputeJob;
import com.myxhs.search.service.RecommendService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 推荐系统接口
 * <p>
 * 推荐 Feed 接口使用 {@link CompletableFuture} 异步返回，释放 Tomcat 线程。
 * 推荐计算涉及多路召回 + 粗排 + 精排 + 重排，耗时较长（200~500ms），
 * 异步化可避免阻塞 Tomcat 线程池。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/recommend")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class RecommendController {

    private final RecommendService recommendService;
    private final RecommendComputeJob recommendComputeJob;

    @Qualifier("recallExecutor")
    private final ExecutorService recallExecutor;

    /** 管理接口令牌（配置化管理，不再硬编码） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.admin.token}")
    private String adminToken;

    private boolean isAdminCall(String v) { return adminToken != null && !adminToken.isEmpty() && adminToken.equals(v); }

    /**
     * 个性化推荐 Feed（发现页）
     * <p>
     * 四层推荐流水线：5路召回 → 粗排 → 精排 → 重排
     * 冷启动用户自动降级为热门+地理推荐。
     * </p>
     */
    @GetMapping("/feed")
    public CompletableFuture<R<List<RecommendFeedVO>>> getRecommendFeed(
            @RequestHeader("X-User-Id") Long userId) {
        return CompletableFuture
                .supplyAsync(() -> R.ok(recommendService.getRecommendFeed(userId)), recallExecutor);
    }

    /**
     * 相似笔记推荐
     * <p>
     * 基于 Item-CF 相似矩阵，返回与指定笔记最相似的内容。
     * </p>
     */
    @GetMapping("/similar/{noteId}")
    public R<List<RecommendFeedVO>> getSimilarNotes(
            @PathVariable Long noteId,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(recommendService.getSimilarNotes(noteId, Math.min(size, 20)));
    }

    /**
     * 上报用户行为（曝光/点击/停留时长等）
     * <p>
     * 行为数据用于：
     * 1. Item-CF 相似矩阵计算
     * 2. 用户兴趣标签更新
     * 3. 热门池维护
     * </p>
     */
    @PostMapping("/behavior")
    public R<Void> reportBehavior(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody BehaviorRequest request) {
        recommendService.reportBehavior(userId, request);
        return R.ok();
    }

    /**
     * 手动触发推荐离线计算（管理员接口）
     * <p>
     * 依次执行：特征提取 → Item-CF 矩阵计算 → 热门池刷新
     * </p>
     */
    @PostMapping("/compute")
    public R<String> triggerCompute(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!isAdminCall(adminCall)) return R.fail(403, "无权访问管理接口");
        recommendComputeJob.extractFeatures();
        recommendComputeJob.computeItemCFMatrix();
        recommendComputeJob.refreshHotPool();
        return R.ok("推荐离线计算已触发");
    }
}
