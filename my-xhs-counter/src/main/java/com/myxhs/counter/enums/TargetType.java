package com.myxhs.counter.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 目标类型枚举
 */
@Getter
@AllArgsConstructor
public enum TargetType {

    NOTE(1, "笔记"),
    USER(2, "用户");

    private final int code;
    private final String desc;

    public static TargetType of(int code) {
        for (TargetType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("无效的目标类型: " + code);
    }
}
