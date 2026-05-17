package com.myxhs.counter.dto;

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
    private List<QueryItem> queries;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryItem {
        /** 目标类型：1-笔记 2-用户 */
        private Integer targetType;

        /** 目标ID */
        private Long targetId;

        /** 需要查询的计数类型列表 */
        private List<Integer> countTypes;
    }
}
