package com.myxhs.search.dto;

import lombok.Data;

/**
 * 笔记搜索请求
 */
@Data
public class NoteSearchRequest {

    /** 搜索关键词 */
    private String keyword;

    /** 排序方式：relevance(相关度)/time(时间)/hot(热度) */
    private String sort = "relevance";

    /** 每页大小 */
    private Integer size = 20;

    /** Search After 游标（上一页最后一条的排序值，JSON 数组字符串） */
    private String searchAfter;
}
