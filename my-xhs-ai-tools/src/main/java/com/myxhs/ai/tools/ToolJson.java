package com.myxhs.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 工具结果类型化输出（架构重构：消灭手拼 JSON 字符串）。
 * 所有工具返回 record（类型安全、字段名一致），统一经此处序列化；
 * 集中处理转义/非 ASCII 转义关闭，字段拼写错误在编译期暴露。
 */
public final class ToolJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(SerializationFeature.INDENT_OUTPUT);

    private ToolJson() {
    }

    /** 序列化 record → JSON 字符串（工具返回契约） */
    public static String write(Object result) {
        try {
            return MAPPER.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("工具结果序列化失败: " + e.getMessage(), e);
        }
    }

    /** 错误响应（统一 status=error 契约；window 可空） */
    public static String error(String metric, String window, String message) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("status", "error");
        m.put("metric", metric == null ? "" : metric);
        m.put("window", window);
        m.put("message", message);
        return write(m);
    }

    public static String error(String metric, String message) {
        return error(metric, null, message);
    }
}
