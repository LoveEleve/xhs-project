package com.myxhs.search.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 召回候选项
 * <p>
 * 每路召回返回的候选笔记，携带召回分数和来源标识。
 * 在粗排阶段会被赋予 rankScore。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecallItem {

    /** 笔记ID */
    private Long noteId;

    /** 召回分数（各路召回策略自行计算） */
    private double recallScore;

    /** 召回来源（ITEM_CF / CONTENT / HOT / FOLLOWING / GEO） */
    private String source;

    /** 粗排分数（粗排阶段赋值） */
    private double rankScore;

    /** 内容分类（重排阶段用于品类打散） */
    private String category;

    public RecallItem(Long noteId, double recallScore, String source) {
        this.noteId = noteId;
        this.recallScore = recallScore;
        this.source = source;
    }
}
