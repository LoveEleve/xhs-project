package com.myxhs.analytics.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 收藏请求 DTO
 */
@Data
public class FavoriteRequest {

    /**
     * 笔记ID
     */
    @NotNull(message = "笔记ID不能为空")
    private Long noteId;
}
