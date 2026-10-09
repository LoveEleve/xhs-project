package com.myxhs.content.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.PageResult;
import com.myxhs.common.response.R;
import com.myxhs.content.dto.request.NotePublishRequest;
import com.myxhs.content.dto.request.NoteUpdateRequest;
import com.myxhs.content.dto.response.NoteDetailVO;
import com.myxhs.content.dto.response.NoteItemVO;
import com.myxhs.content.service.FileStorageService;
import com.myxhs.content.service.NoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 笔记接口
 * <p>
 * 提供笔记发布、草稿保存、编辑、删除、详情查询、列表查询、图片上传等功能。
 * 需要登录的接口通过 X-User-Id Header 获取用户 ID（由 Gateway 注入）。
 * </p>
 */
@lombok.extern.slf4j.Slf4j
@RestController
@RequestMapping("/api/note")
@RequiredArgsConstructor
public class NoteController {

    /** 批量详情单次上限 */
    private static final int MAX_BATCH_DETAIL_SIZE = 100;

    private final NoteService noteService;
    private final FileStorageService fileStorageService;
    private final com.myxhs.common.web.AccessTokenGuard accessTokenGuard;

    /**
     * 发布笔记
     * <p>
     * 同一用户1分钟内最多发布5篇（限流）。
     * 发布前自动进行 DFA 敏感词检测。
     * </p>
     */
    @PostMapping("/publish")
    @RateLimit(windowSeconds = 60, maxRequests = 5, perUser = true, prefix = "myxhs:note:publish",
            message = "发布过于频繁，请稍后重试")
    public R<Map<String, Long>> publishNote(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody NotePublishRequest request) {
        Long noteId = noteService.publishNote(userId, request);
        return R.ok("发布成功", Map.of("noteId", noteId));
    }

    /**
     * 保存草稿
     * <p>
     * 草稿允许不完整（标题可为空），因此不做参数校验。
     * </p>
     */
    @PostMapping("/draft")
    public R<Map<String, Long>> saveDraft(
            @RequestHeader("X-User-Id") Long userId,
            @RequestBody NotePublishRequest request) {
        Long noteId = noteService.saveDraft(userId, request);
        return R.ok("草稿保存成功", Map.of("noteId", noteId));
    }

    /**
     * 编辑笔记
     */
    @PutMapping("/{id}")
    public R<Void> updateNote(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable("id") Long noteId,
            @Valid @RequestBody NoteUpdateRequest request) {
        noteService.updateNote(userId, noteId, request);
        return R.ok();
    }

    /**
     * 删除笔记
     */
    @DeleteMapping("/{id}")
    public R<Void> deleteNote(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable("id") Long noteId) {
        noteService.deleteNote(userId, noteId);
        return R.ok();
    }

    /**
     * 获取笔记详情（公开接口，无需登录）
     */
    @GetMapping("/detail/{id}")
    public R<NoteDetailVO> getNoteDetail(@PathVariable("id") Long noteId) {
        return R.ok(noteService.getNoteDetail(noteId));
    }

    /**
     * 批量获取笔记详情（P2-3: Feed 场景一次取多篇，避免逐条 HTTP）
     */
    @PostMapping("/batch-detail")
    public R<Map<Long, NoteDetailVO>> batchGetNoteDetail(@RequestBody java.util.List<Long> noteIds) {
        // 条数上限：公开接口，原实现不设限（万级 id 会放大 DB/缓存查询）
        if (noteIds == null || noteIds.isEmpty()) {
            return R.ok(java.util.Map.of());
        }
        if (noteIds.size() > MAX_BATCH_DETAIL_SIZE) {
            log.warn("[笔记] batch-detail 超限截断: requested={}, limit={}", noteIds.size(), MAX_BATCH_DETAIL_SIZE);
            noteIds = noteIds.subList(0, MAX_BATCH_DETAIL_SIZE);
        }
        return R.ok(noteService.batchGetNoteDetail(noteIds));
    }

    /**
     * 获取指定用户的笔记列表（公开接口，仅已发布）
     */
    @GetMapping("/user/{userId}")
    public R<PageResult<NoteItemVO>> getUserNotes(
            @PathVariable("userId") Long userId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(noteService.getUserNotes(userId, pageNum, pageSize));
    }

    /**
     * 获取当前用户的笔记列表（包含草稿等，需登录）
     */
    @GetMapping("/my")
    public R<PageResult<NoteItemVO>> getMyNotes(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(noteService.getMyNotes(userId, status, pageNum, pageSize));
    }

    /**
     * 发布草稿
     */
    @PostMapping("/{id}/publish")
    public R<Void> publishDraft(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable("id") Long noteId) {
        noteService.publishDraft(userId, noteId);
        return R.ok();
    }

    /**
     * 上传笔记图片
     * <p>
     * 支持 JPEG/PNG/GIF/WebP，最大 5MB。
     * 返回图片访问 URL，前端拿到 URL 后在发布笔记时传入 images 字段。
     * </p>
     */
    @PostMapping("/upload/image")
    @RateLimit(windowSeconds = 60, maxRequests = 20, perUser = true, prefix = "myxhs:note:upload",
            message = "上传过于频繁，请稍后重试")
    public R<Map<String, String>> uploadImage(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("file") MultipartFile file) {
        String url = fileStorageService.upload(file, "note");
        return R.ok("上传成功", Map.of("url", url));
    }

    /**
     * 分享笔记
     * <p>
     * 记录一次分享事件，递增笔记的分享计数。
     * 使用 Redis Hash 存储笔记的各维度计数（分享、点赞、收藏等）。
     * </p>
     */
    @PostMapping("/{id}/share")
    @RateLimit(prefix = "myxhs:note:share", maxRequests = 10, windowSeconds = 60, perUser = true,
            message = "分享过于频繁")
    public R<Void> shareNote(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable("id") Long noteId) {
        noteService.shareNote(noteId, userId);
        return R.ok();
    }
    /**
     * 审核笔记（管理端，X-Admin-Call）：1=通过 2=驳回
     */
    @org.springframework.web.bind.annotation.PutMapping("/internal/audit/{noteId}")
    public R<Void> auditNote(@org.springframework.web.bind.annotation.PathVariable Long noteId,
                             @org.springframework.web.bind.annotation.RequestParam int status,
                             @org.springframework.web.bind.annotation.RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (accessTokenGuard == null || !accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "无权访问管理接口");
        }
        noteService.auditNote(noteId, status);
        return R.ok();
    }

}
