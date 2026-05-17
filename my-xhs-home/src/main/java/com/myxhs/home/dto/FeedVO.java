package com.myxhs.home.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Feed 流响应（游标分页）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedVO {

    /** 笔记卡片列表 */
    private List<NoteCardVO> notes;

    /** 下一页游标（时间戳 score，传给下次请求的 lastScore） */
    private String nextCursor;

    /** 是否还有更多 */
    private Boolean hasMore;

    /** 未读通知数 */
    private Integer unreadCount;
}
