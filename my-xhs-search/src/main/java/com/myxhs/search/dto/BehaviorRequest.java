package com.myxhs.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 用户行为上报请求
 */
@Data
public class BehaviorRequest {

    /** 笔记ID */
    @NotNull(message = "笔记ID不能为空")
    private Long noteId;

    /**
     * 行为类型
     * 1=曝光 2=点击 3=点赞 4=收藏 5=评论 6=分享 7=停留
     */
    @NotNull(message = "行为类型不能为空")
    @Min(value = 1, message = "行为类型最小为1")
    @Max(value = 7, message = "行为类型最大为7")
    private Integer behaviorType;

    /** 停留时长（秒），仅 behaviorType=7 时有效 */
    private Integer duration;
}
