package com.myxhs.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推荐 Feed 返回 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendFeedVO {

    /** 笔记ID */
    private Long noteId;

    /** 推荐分数 */
    private Double score;

    /** 召回来源（ITEM_CF / CONTENT / HOT / FOLLOWING / GEO） */
    private String source;

    /** 内容分类 */
    private String category;

    /** 推荐理由（可选，如"你关注的人发布了"） */
    private String reason;
}
