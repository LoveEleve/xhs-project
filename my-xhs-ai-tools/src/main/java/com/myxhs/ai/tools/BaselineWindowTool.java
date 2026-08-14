package com.myxhs.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 对比基线窗口工具（D4：基线约束确定性化）。
 * 纯计算、无 DB：给定当前窗口，返回**上一同长窗口**（同跨度、紧邻当前之前）。
 * 例：2026-08-01~2026-08-07（7 天）→ 2026-07-25~2026-07-31。
 * 窗口规则单一事实源 = MetricWindow（与 PolicyGuard 共用，防规则漂移）。
 * 目的：基线窗口由确定性工具产生（可重复），模型不得自行推算——防口径漂移。
 */
public class BaselineWindowTool {

    public static final String METRIC_NAME = "baseline.window";

    private final ObjectMapper om;

    public BaselineWindowTool() {
        this(new ObjectMapper());
    }

    public BaselineWindowTool(ObjectMapper om) {
        this.om = om;
    }

    /** 计算上一同长窗口；参数非法返回 status=error JSON（不抛异常） */
    public String baselineWindow(String window) {
        try {
            MetricWindow w = MetricWindow.parse(window);
            MetricWindow base = w.baseline();
            ObjectNode node = om.createObjectNode();
            node.put("status", "ok");
            node.put("metric", METRIC_NAME);
            node.put("asOf", java.time.Instant.now().toString());
            node.put("current", window.trim());
            node.put("baseline", base.format());
            node.put("spanDays", w.spanDays());
            node.put("rule", "上一同长窗口（同跨度、紧邻当前之前）");
            return write(node);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage() + (window == null ? "" : ": " + window.trim()));
        }
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
