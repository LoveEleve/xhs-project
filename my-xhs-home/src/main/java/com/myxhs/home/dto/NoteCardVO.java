package com.myxhs.home.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Feed 流中的笔记卡片（聚合后返回给前端）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteCardVO {

    private Long noteId;
    private String title;
    private String coverUrl;
    private Integer noteType;

    /** 作者信息 */
    private Long authorId;
    private String authorNickname;
    private String authorAvatar;

    /** 计数 */
    private Long likeCount;
    private Long collectCount;
    private Long commentCount;

    /** 社交状态（当前用户视角） */
    private Boolean isLiked;
    private Boolean isCollected;
    private Boolean isFollowed;

    /** 发布时间（也是 Feed 排序的 score） */
    private LocalDateTime createdAt;

    /** 时间戳 score（用于游标分页） */
    private Double score;
}
