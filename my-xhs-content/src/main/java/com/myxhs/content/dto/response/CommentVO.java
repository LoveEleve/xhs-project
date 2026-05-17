package com.myxhs.content.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论响应 VO
 * <p>
 * 一级评论包含子评论列表（楼中楼），子评论不再嵌套。
 * 使用 @JsonInclude(NON_NULL) 避免子评论返回 "children": null 等冗余字段。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommentVO {

    /** 评论ID */
    private Long id;

    /** 笔记ID */
    private Long noteId;

    /** 评论用户ID */
    private Long userId;

    /** 父评论ID（0为一级评论） */
    private Long parentId;

    /** 回复的评论ID */
    private Long replyToId;

    /** 评论内容 */
    private String content;

    /** 点赞数 */
    private Integer likeCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 子评论列表（仅一级评论有，楼中楼） */
    private List<CommentVO> children;

    /** 子评论总数（仅一级评论有，用于"查看更多回复"） */
    private Long childCount;
}
