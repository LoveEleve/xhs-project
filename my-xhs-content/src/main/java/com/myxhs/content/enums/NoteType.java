package com.myxhs.content.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 笔记类型枚举
 */
@Getter
@AllArgsConstructor
public enum NoteType {

    IMAGE_TEXT(0, "图文"),
    VIDEO(1, "视频");

    private final int code;
    private final String desc;

    public static NoteType of(int code) {
        for (NoteType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的笔记类型: " + code);
    }
}
