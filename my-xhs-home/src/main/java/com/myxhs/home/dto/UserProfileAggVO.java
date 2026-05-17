package com.myxhs.home.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户主页聚合 VO（BFF 层返回给前端）
 * <p>
 * 聚合来源：
 * - 用户基本信息 → user 服务
 * - 计数（关注数/粉丝数/获赞数/笔记数） → counter 服务
 * - 社交关系（是否关注/互关） → analytics 服务
 * - 用户笔记列表 → content 服务
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileAggVO {

    // ========== 用户基本信息 ==========
    private Long userId;
    private String nickname;
    private String avatar;
    private String bio;

    // ========== 计数 ==========
    private Long followingCount;
    private Long followerCount;
    private Long likeAndCollectCount;
    private Long noteCount;

    // ========== 社交关系（当前用户视角） ==========
    private Boolean isFollowing;
    private Boolean isFollowBack;
    private Boolean isMutual;

    // ========== 用户笔记列表（首页展示前 10 条） ==========
    private List<NoteCardVO> notes;
}
