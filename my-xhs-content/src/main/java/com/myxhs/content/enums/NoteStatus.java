package com.myxhs.content.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;
import java.util.Set;

/**
 * 笔记状态枚举 + 状态流转校验
 * <p>
 * 防止非法状态流转（如从"已下架"直接变为"草稿"）。
 * 每次状态变更前调用 canTransitTo() 校验合法性。
 * </p>
 */
@Getter
@AllArgsConstructor
public enum NoteStatus {

    DRAFT(0, "草稿"),
    AUDITING(1, "审核中"),
    PUBLISHED(2, "已发布"),
    OFFLINE(3, "已下架");

    private final int code;
    private final String desc;

    /**
     * 合法的状态流转映射
     * <pre>
     * 草稿   → 审核中 / 已发布（DFA通过直接发布）
     * 审核中 → 已发布 / 已下架（审核拒绝）
     * 已发布 → 已下架（作者删除/违规下架）
     * 已下架 → 终态，不可流转
     * </pre>
     */
    private static final Map<NoteStatus, Set<NoteStatus>> TRANSITIONS = Map.of(
            DRAFT, Set.of(AUDITING, PUBLISHED),
            AUDITING, Set.of(PUBLISHED, OFFLINE),
            PUBLISHED, Set.of(OFFLINE),
            OFFLINE, Set.of()
    );

    /**
     * 校验状态流转是否合法
     */
    public boolean canTransitTo(NoteStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /**
     * 根据 code 获取枚举
     */
    public static NoteStatus of(int code) {
        for (NoteStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的笔记状态: " + code);
    }
}
