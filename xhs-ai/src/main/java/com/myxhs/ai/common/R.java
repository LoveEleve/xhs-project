package com.myxhs.ai.common;

import lombok.Data;

/**
 * 统一响应封装（对齐 xhs 平台 R 约定）
 */
@Data
public class R<T> {

    private int code;
    private String message;
    private T data;
    private long timestamp = System.currentTimeMillis();
    private boolean success;

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.message = "操作成功";
        r.data = data;
        r.success = true;
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        r.success = false;
        return r;
    }
}
