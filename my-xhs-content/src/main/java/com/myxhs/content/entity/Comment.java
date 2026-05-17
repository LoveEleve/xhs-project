package com.myxhs.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.myxhs.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 评论实体
 * <p>
 * 对应 t_comment 表。
 * 支持楼中楼评论：parent_id 标识父评论（0 为一级评论），reply_to_id 标识被回复的评论。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_comment")
public class Comment extends BaseEntity {

    /** 所属笔记ID */
    private Long noteId;

    /** 评论用户ID */
    private Long userId;

    /** 父评论ID（0为一级评论） */
    private Long parentId;

    /** 回复的评论ID（楼中楼回复指向的具体评论） */
    private Long replyToId;

    /** 评论内容 */
    private String content;

    /** 点赞数 */
    private Integer likeCount;
}
