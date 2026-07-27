package com.myxhs.counter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
    @NotNull(message = "目标类型不能为空")
    @Min(value = 1, message = "目标类型无效")
    @Max(value = 2, message = "目标类型无效")
    private Integer targetType;

    /** 目标ID */
    @NotNull(message = "目标ID不能为空")
    private Long targetId;

    /** 计数类型：1-点赞 2-收藏 3-评论 4-分享 5-浏览 6-粉丝 7-关注 */
    @NotNull(message = "计数类型不能为空")
    @Min(value = 1, message = "计数类型无效")
    @Max(value = 7, message = "计数类型无效")
    private Integer countType;
}
