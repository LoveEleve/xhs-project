package com.myxhs.counter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量查询计数请求 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CounterBatchRequest {

    /** 查询列表 */
    @NotEmpty(message = "查询列表不能为空")
    @Size(max = 100, message = "查询列表最多 100 项")
    @Valid
    private List<QueryItem> queries;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryItem {
        /** 目标类型：1-笔记 2-用户 3-评论 4-商品 */
        @NotNull(message = "目标类型不能为空")
        @Min(value = 1, message = "目标类型1-4")
        @Max(value = 4, message = "目标类型1-4")
        private Integer targetType;

        /** 目标ID */
        @NotNull(message = "目标ID不能为空")
        @Positive(message = "目标ID必须为正数")
        private Long targetId;

        /** 需要查询的计数类型列表 */
        @NotEmpty(message = "计数类型不能为空")
        @Size(max = 7, message = "计数类型最多 7 项")
        private List<@NotNull @Min(1) @Max(7) Integer> countTypes;
    }
}
