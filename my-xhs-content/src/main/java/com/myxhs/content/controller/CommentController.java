package com.myxhs.content.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.PageResult;
import com.myxhs.common.response.R;
import com.myxhs.content.dto.request.CommentCreateRequest;
import com.myxhs.content.dto.response.CommentVO;
import com.myxhs.content.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 评论接口
 * <p>
 * 提供评论发表、删除、一级评论列表（游标分页）、子评论列表（楼中楼展开）、评论计数等功能。
 * 发表/删除评论需要登录（通过 X-User-Id Header 获取用户 ID）。
 * 查询评论列表为公开接口，无需登录。
 * </p>
 */
@RestController
@RequestMapping("/api/comment")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * 发表评论
     * <p>
     * 同一用户1分钟内最多发表10条评论（限流）。
     * 发表前自动进行 DFA 敏感词检测。
     * </p>
     */
    @PostMapping
    @RateLimit(windowSeconds = 60, maxRequests = 10, perUser = true, prefix = "myxhs:comment:create",
            message = "评论过于频繁，请稍后重试")
    public R<Map<String, Long>> createComment(
            @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CommentCreateRequest request) {
        Long commentId = commentService.createComment(userId, request);
        return R.ok("评论成功", Map.of("commentId", commentId));
    }

    /**
     * 删除评论
     * <p>
     * 评论作者或笔记作者可删除。
     * 删除一级评论时，其下所有子评论也会被删除。
     * </p>
     */
    @DeleteMapping("/{id}")
    public R<Void> deleteComment(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable("id") Long commentId) {
        commentService.deleteComment(userId, commentId);
        return R.ok();
    }

    /**
     * 获取笔记的一级评论列表（游标分页，公开接口）
     * <p>
     * 游标分页：首次请求不传 lastId，后续传上一页最后一条评论的 ID。
     * 每条一级评论预加载前3条子评论（楼中楼预览）。
     * </p>
     *
     * @param noteId   笔记ID
     * @param lastId   游标（上一页最后一条评论的ID）
     * @param pageSize 每页条数（默认10，最大20）
     */
    @GetMapping("/list/{noteId}")
    public R<List<CommentVO>> getCommentList(
            @PathVariable("noteId") Long noteId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(commentService.getCommentList(noteId, lastId, pageSize));
    }

    /**
     * 获取子评论列表（游标分页，楼中楼展开，公开接口）
     * <p>
     * 点击"查看更多回复"时调用，加载一级评论下的更多子评论。
     * </p>
     *
     * @param parentId 一级评论ID
     * @param lastId   游标（上一页最后一条子评论的ID）
     * @param pageSize 每页条数（默认10，最大20）
     */
    @GetMapping("/children/{parentId}")
    public R<List<CommentVO>> getChildComments(
            @PathVariable("parentId") Long parentId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(commentService.getChildComments(parentId, lastId, pageSize));
    }

    /**
     * 获取笔记的评论总数（公开接口）
     */
    @GetMapping("/count/{noteId}")
    public R<Map<String, Long>> getCommentCount(@PathVariable("noteId") Long noteId) {
        long count = commentService.getCommentCount(noteId);
        return R.ok(Map.of("count", count));
    }

    /**
     * 获取笔记的一级评论列表（传统分页，备用接口）
     */
    @GetMapping("/page/{noteId}")
    public R<PageResult<CommentVO>> getCommentPage(
            @PathVariable("noteId") Long noteId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(commentService.getCommentPage(noteId, pageNum, pageSize));
    }
}
