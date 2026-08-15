package com.myxhs.ai.tools;

import java.util.regex.Pattern;

/**
 * 工具参数校验（M12：从 PolicyGuard 迁移的静态校验，app 与 mcp 复用）。
 * 规则单一事实源：window = MetricWindow；hours/keyword/tailLines/group 在此。
 */
public class ToolParamValidators {

    /** 组名标签过滤白名单（防 PromQL 注入） */
    private static final Pattern GROUP_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private ToolParamValidators() {
    }

    /** 返回错误消息，null=合法（规则单一事实源 MetricWindow） */
    public static String validateWindow(String window) {
        try {
            MetricWindow.parse(window);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** hours 校验：1~168 整数 */
    public static String validateHours(String hours) {
        if (hours == null || hours.isBlank()) {
            return "hours 必填（最近小时数 1~168）";
        }
        try {
            int h = Integer.parseInt(hours.trim());
            if (h < 1 || h > 168) {
                return "hours 必须在 1~168 之间";
            }
            return null;
        } catch (NumberFormatException e) {
            return "hours 必须为整数";
        }
    }

    /** 组名白名单（字母数字下划线连字符；空=全部，合法） */
    public static String validateGroup(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        return GROUP_PATTERN.matcher(group.trim()).matches()
                ? null : "组名仅允许字母数字下划线连字符";
    }

    /** 受控日志检索 keyword 白名单（无 shell 语义字符） */
    public static String validateKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "keyword 必填";
        }
        if (keyword.length() > 100) {
            return "keyword 长度不能超过 100";
        }
        if (!keyword.matches("[A-Za-z0-9_\\-\\[\\].:/=]+")) {
            return "keyword 仅允许字母数字与常见符号（_-[].:/=）";
        }
        return null;
    }

    /** tailLines 解析：返回行数（非法返回 -1） */
    public static int parseTailLines(String tailLines) {
        if (tailLines == null || tailLines.isBlank()) {
            return 500;
        }
        try {
            int n = Integer.parseInt(tailLines.trim());
            return n >= 1 && n <= 5000 ? n : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
