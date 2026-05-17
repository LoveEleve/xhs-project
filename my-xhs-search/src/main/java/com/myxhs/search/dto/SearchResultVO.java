package com.myxhs.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索结果 VO（通用分页）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultVO<T> {

    /** 搜索结果列表 */
    private List<T> items;

    /** 总命中数 */
    private long total;

    /** Search After 游标（下一页使用） */
    private String searchAfter;

    /** 是否有更多数据 */
    private boolean hasMore;

    /** 搜索耗时（毫秒） */
    private long took;
}
