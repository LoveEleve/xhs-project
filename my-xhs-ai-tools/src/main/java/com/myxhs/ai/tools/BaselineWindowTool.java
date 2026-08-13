package com.myxhs.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * 对比基线窗口工具（D4：基线约束确定性化）。
 * 纯计算、无 DB：给定当前窗口，返回**上一同长窗口**（同跨度、紧邻当前之前）。
 * 例：2026-08-01~2026-08-07（7 天）→ 2026-07-25~2026-07-31。
 * 目的：基线窗口由确定性工具产生（可重复），模型不得自行推算——防口径漂移。
 */
public class BaselineWindowTool {

    public static final String METRIC_NAME = "baseline.window";

    private static final Pattern WINDOW_PATTERN =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})~(\\d{4}-\\d{2}-\\d{2})");
    private static final long MAX_WINDOW_DAYS = 31;

    private final ObjectMapper om;

    public BaselineWindowTool() {
        this(new ObjectMapper());
    }

    public BaselineWindowTool(ObjectMapper om) {
        this.om = om;
    }

    /** 计算上一同长窗口；参数非法返回 status=error JSON（不抛异常） */
    public String baselineWindow(String window) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "ok");
        node.put("metric", METRIC_NAME);
        node.put("asOf", java.time.Instant.now().toString());
        if (window == null || window.isBlank()) {
            return error("window 必填（yyyy-MM-dd~yyyy-MM-dd）");
        }
        var m = WINDOW_PATTERN.matcher(window.trim());
        if (!m.matches()) {
            return error("window 格式必须为 yyyy-MM-dd~yyyy-MM-dd: " + window);
        }
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(m.group(1));
            end = LocalDate.parse(m.group(2));
        } catch (Exception e) {
            return error("window 日期非法: " + window);
        }
        if (start.isAfter(end)) {
            return error("window 起始日期不能晚于结束日期: " + window);
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_WINDOW_DAYS) {
            return error("window 跨度不能超过 " + MAX_WINDOW_DAYS + " 天: " + window);
        }
        LocalDate baseStart = start.minusDays(span);
        LocalDate baseEnd = end.minusDays(span);
        node.put("current", window.trim());
        node.put("baseline", baseStart + "~" + baseEnd);
        node.put("spanDays", span);
        node.put("rule", "上一同长窗口（同跨度、紧邻当前之前）");
        return write(node);
    }

    private String error(String msg) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "error");
        node.put("metric", METRIC_NAME);
        node.put("error", msg);
        return write(node);
    }

    private String write(ObjectNode node) {
        try {
            return om.writeValueAsString(node);
        } catch (Exception e) {
            // ObjectNode 序列化理论不失败；兜底静态 JSON
            return "{\"status\":\"error\",\"metric\":\"" + METRIC_NAME + "\",\"error\":\"序列化失败\"}";
        }
    }
}
