package com.myxhs.analytics.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注/粉丝列表响应 VO
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FollowVO {

    /** 用户ID */
    private Long userId;

    /** 昵称（预留，后续通过 Feign 调用用户服务获取） */
    private String nickname;

    /** 头像（预留） */
    private String avatar;

    /** 关注时间 */
    private LocalDateTime followedAt;

    /** 是否互相关注 */
    private Boolean isFollowBack;
}
