package com.myxhs.content.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论请求
 */
@Data
public class CommentCreateRequest {

    /** 笔记ID（必填） */
    @NotNull(message = "笔记ID不能为空")
    private Long noteId;

    /** 父评论ID（一级评论传0或不传，回复评论传父评论ID） */
    private Long parentId;

    /** 回复的评论ID（楼中楼回复时传入被回复的评论ID） */
    private Long replyToId;

    /**
     * 评论内容（必填，最长500字）
     * <p>
     * 注：数据库 t_comment.content 字段为 VARCHAR(1024)，预留了扩展空间。
     * 业务层限制 500 字，与小红书评论长度规范一致。
     * </p>
     */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论内容不能超过500字")
    private String content;
}
