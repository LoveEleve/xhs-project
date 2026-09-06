package com.myxhs.search.controller;

import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.search.dto.*;
import com.myxhs.search.job.IndexRebuildJob;
import com.myxhs.search.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 搜索接口
 * <p>
 * 核心搜索接口（笔记搜索/商品搜索）使用 {@link CompletableFuture} 异步返回，
 * 释放 Tomcat 线程等待 ES 查询结果。热搜记录在异步任务内部同步执行。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class SearchController {

    private final NoteSearchService noteSearchService;
    private final ProductSearchService productSearchService;
    private final SuggestService suggestService;
    private final SearchHistoryService searchHistoryService;
    private final HotSearchService hotSearchService;
    private final IndexRebuildJob indexRebuildJob;
    private final AccessTokenGuard accessTokenGuard;

    @Qualifier("recallExecutor")
    private final ExecutorService searchExecutor;

    /**
     * 笔记搜索（关键词 + 排序 + Search After 深分页 + 高亮）
     * <p>
     * 搜索时自动记录搜索词到热搜滑动窗口（含反作弊过滤）。
     * </p>
     */
    @GetMapping("/note")
    public CompletableFuture<R<SearchResultVO<NoteSearchVO>>> searchNotes(
            @Valid NoteSearchRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        return CompletableFuture.supplyAsync(() -> {
            // 记录搜索词到热搜窗口
            if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
                hotSearchService.recordSearchKeyword(request.getKeyword(), userId,
                        ip != null ? ip : "unknown");
            }
            return R.ok(noteSearchService.searchNotes(request, userId));
        }, searchExecutor);
    }

    /**
     * 商品搜索（关键词 + 分类 + 价格区间 + 排序 + Search After）
     * <p>
     * 搜索时自动记录搜索词到热搜滑动窗口。
     * </p>
     */
    @GetMapping("/product")
    public CompletableFuture<R<SearchResultVO<ProductSearchVO>>> searchProducts(
            @Valid ProductSearchRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        return CompletableFuture.supplyAsync(() -> {
            if (request.getKeyword() != null && !request.getKeyword().isBlank()) {
                hotSearchService.recordSearchKeyword(request.getKeyword(), userId,
                        ip != null ? ip : "unknown");
            }
            return R.ok(productSearchService.searchProducts(request));
        }, searchExecutor);
    }

    /**
     * 搜索建议（自动补全，基于 ES Completion Suggester）
     */
    @GetMapping("/suggest")
    public R<List<String>> suggest(@RequestParam String prefix) {
        return R.ok(suggestService.suggest(prefix));
    }

    /**
     * 获取搜索历史（最近 20 条）
     */
    @GetMapping("/history")
    public R<List<String>> getHistory(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(searchHistoryService.getHistory(userId));
    }

    /**
     * 清空搜索历史
     */
    @DeleteMapping("/history")
    public R<Void> clearHistory(@RequestHeader("X-User-Id") Long userId) {
        searchHistoryService.clearHistory(userId);
        return R.ok();
    }

    /**
     * 删除单条搜索历史
     */
    @DeleteMapping("/history/{keyword}")
    public R<Void> deleteHistoryItem(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable String keyword) {
        searchHistoryService.deleteHistoryItem(userId, keyword);
        return R.ok();
    }


    /**
     * 手动触发全量索引重建（管理员接口）
     */
    @PostMapping("/index/rebuild")
    public R<String> rebuildIndex(@RequestHeader("X-User-Id") Long userId,
                                  @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "仅管理员可执行此操作");
        }
        indexRebuildJob.manualRebuild();
        return R.ok("索引重建任务已启动");
    }

    // ==================== 热搜排行榜 ====================

    /**
     * 获取热搜榜 Top 50（置顶词优先展示）
     */
    @GetMapping("/hot")
    public R<List<HotSearchVO>> getHotSearch() {
        return R.ok(hotSearchService.getHotSearchList());
    }

    /**
     * 记录搜索词（前端主动调用，补充搜索词记录）
     */
    @PostMapping("/hot/record")
    public R<Void> recordSearchKeyword(
            @RequestParam String keyword,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Forwarded-For", required = false) String ip) {
        hotSearchService.recordSearchKeyword(keyword, userId, ip != null ? ip : "unknown");
        return R.ok();
    }

    /**
     * 人工置顶热搜词（管理员接口）
     */
    @PutMapping("/hot/pin")
    public R<Void> pinKeyword(@RequestParam String keyword,
                              @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "仅管理员可执行此操作");
        }
        hotSearchService.pinKeyword(keyword);
        return R.ok();
    }

    /**
     * 取消置顶
     */
    @DeleteMapping("/hot/pin")
    public R<Void> unpinKeyword(@RequestParam String keyword,
                                @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "仅管理员可执行此操作");
        }
        hotSearchService.unpinKeyword(keyword);
        return R.ok();
    }

    /**
     * 人工屏蔽热搜词（管理员接口）
     */
    @PutMapping("/hot/block")
    public R<Void> blockKeyword(@RequestParam String keyword,
                                @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "仅管理员可执行此操作");
        }
        hotSearchService.blockKeyword(keyword);
        return R.ok();
    }

    /**
     * 取消屏蔽
     */
    @DeleteMapping("/hot/block")
    public R<Void> unblockKeyword(@RequestParam String keyword,
                                  @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "仅管理员可执行此操作");
        }
        hotSearchService.unblockKeyword(keyword);
        return R.ok();
    }

    /**
     * 历史热搜快照（按日期查询）
     */
    @GetMapping("/hot/snapshot")
    public R<List<HotSearchVO>> getHotSearchSnapshot(@RequestParam String date) {
        return R.ok(hotSearchService.getHotSearchSnapshot(date));
    }
}
