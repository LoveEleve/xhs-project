package com.myxhs.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 热搜词 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotSearchVO {

    /** 排名（1-50） */
    private Integer rank;

    /** 搜索词 */
    private String keyword;

    /** 热度分数 */
    private Double score;

    /** 是否人工置顶 */
    private Boolean pinned;

    /** 热度标签（爆/热/新） */
    private String tag;
}
