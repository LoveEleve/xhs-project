package com.myxhs.counter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计数请求 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterRequest {

    /** 目标类型：1-笔记 2-用户 */
    private Integer targetType;

    /** 目标ID */
    private Long targetId;

    /** 计数类型：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注 */
    private Integer countType;
}
