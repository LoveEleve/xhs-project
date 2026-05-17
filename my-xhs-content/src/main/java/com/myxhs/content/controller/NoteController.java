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
@RestController
@RequestMapping("/api/note")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;
    private final FileStorageService fileStorageService;

    /**
     * 发布笔记
     * <p>
     * 同一用户1分钟内最多发布5篇（限流）。
     * 发布前自动进行 DFA 敏感词检测。
     * </p>
     */
    @PostMapping("/publish")
    @RateLimit(windowSeconds = 60, maxRequests = 5, perUser = true, prefix = "note:publish",
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
    @RateLimit(windowSeconds = 60, maxRequests = 20, perUser = true, prefix = "note:upload",
            message = "上传过于频繁，请稍后重试")
    public R<Map<String, String>> uploadImage(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam("file") MultipartFile file) {
        String url = fileStorageService.upload(file, "note");
        return R.ok("上传成功", Map.of("url", url));
    }
}
