package com.myxhs.home.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 笔记详情聚合 VO（BFF 层返回给前端）
 * <p>
 * 聚合来源：
 * - 笔记详情 → content 服务
 * - 作者信息 → user 服务
 * - 计数（点赞/收藏/评论） → counter 服务
 * - 社交状态（是否点赞/收藏/关注） → analytics 服务
 * - 热门评论 → content 服务（评论接口）
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteDetailAggVO {

    // ========== 笔记基本信息 ==========
    private Long noteId;
    private String title;
    private String content;
    private List<String> images;
    private String videoUrl;
    private String coverUrl;
    private Integer noteType;
    private List<String> tags;
    private LocalDateTime createdAt;

    // ========== 作者信息 ==========
    private Long authorId;
    private String authorNickname;
    private String authorAvatar;

    // ========== 计数 ==========
    private Long likeCount;
    private Long collectCount;
    private Long commentCount;

    // ========== 社交状态（当前用户视角） ==========
    private Boolean isLiked;
    private Boolean isCollected;
    private Boolean isFollowed;

    // ========== 热门评论（前 3 条） ==========
    private List<Map<String, Object>> hotComments;
}
