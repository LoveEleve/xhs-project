package com.myxhs.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知类型枚举
 * <p>
 * 消除 getTypeStr() 在多个类中重复定义的问题。
 * </p>
 */
@Getter
@AllArgsConstructor
public enum NotificationType {

    LIKE(1, "LIKE", "赞了你的笔记"),
    COMMENT(2, "COMMENT", "评论了你的笔记"),
    FOLLOW(3, "FOLLOW", "关注了你"),
    SYSTEM(4, "SYSTEM", "发送了系统通知"),
    ORDER(5, "ORDER", "更新了订单状态");

    private final int code;
    private final String name;
    private final String defaultAction;

    public static NotificationType fromCode(int code) {
        for (NotificationType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return SYSTEM; // 默认
    }

    public static String toName(int code) {
        return fromCode(code).getName();
    }
}
