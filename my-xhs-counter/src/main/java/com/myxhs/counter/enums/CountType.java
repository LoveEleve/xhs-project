package com.myxhs.counter.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 计数类型枚举
 */
@Getter
@AllArgsConstructor
public enum CountType {

    LIKE(1, "点赞"),
    COLLECT(2, "收藏"),
    COMMENT(3, "评论"),
    SHARE(4, "分享"),
    VIEW(5, "浏览"),
    FOLLOWER(6, "粉丝"),
    FOLLOWING(7, "关注");

    private final int code;
    private final String desc;

    public static CountType of(int code) {
        for (CountType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("无效的计数类型: " + code);
    }

    /**
     * 获取计数类型的英文名（用于批量查询响应的 key）
     */
    public String getEnglishName() {
        return switch (this) {
            case LIKE -> "like";
            case COLLECT -> "collect";
            case COMMENT -> "comment";
            case SHARE -> "share";
            case VIEW -> "view";
            case FOLLOWER -> "follower";
            case FOLLOWING -> "following";
        };
    }
}
