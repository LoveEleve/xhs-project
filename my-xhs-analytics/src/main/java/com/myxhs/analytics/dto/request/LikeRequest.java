package com.myxhs.analytics.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 点赞请求 DTO
 */
@Data
public class LikeRequest {

    /**
     * 业务类型：1-笔记 2-评论
     */
    @NotNull(message = "业务类型不能为空")
    @Min(value = 1, message = "业务类型无效")
    @Max(value = 2, message = "业务类型无效")
    private Integer bizType;

    /**
     * 业务ID（笔记ID或评论ID）
     */
    @NotNull(message = "业务ID不能为空")
    @Positive(message = "业务ID必须为正数")
    private Long bizId;
}
