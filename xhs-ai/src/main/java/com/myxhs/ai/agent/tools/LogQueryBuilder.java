package com.myxhs.ai.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 业务级日志查询 → ES DSL 组装（不把 DSL 暴露给模型；参数强校验）
 */
public final class LogQueryBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final int MAX_MINUTES = 1440;
    public static final int MAX_SIZE = 100;
    public static final int MAX_TOP_N = 50;

    private LogQueryBuilder() {
    }

    public static int clampMinutes(int minutes) {
        return Math.max(1, Math.min(MAX_MINUTES, minutes));
    }

    public static int clampSize(int size) {
        return Math.max(1, Math.min(MAX_SIZE, size));
    }

    public static int clampTopN(int topN) {
        return Math.max(1, Math.min(MAX_TOP_N, topN));
    }

    /** 明细检索：service/level/keyword 可选，按时间倒序取前 size 条 */
    public static String searchBody(String service, String level, String keyword, int minutes, int size) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("size", clampSize(size));
        root.put("track_total_hits", true);
        root.put("sort", MAPPER.createArrayNode().add(
                MAPPER.createObjectNode().set("@timestamp", MAPPER.createObjectNode().put("order", "desc"))));

        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        filters.add(rangeMinutes(minutes));
        if (level != null && !level.isBlank()) {
            filters.add(term("level.keyword", level));
        }
        if (service != null && !service.isBlank()) {
            filters.add(term("APP_NAME.keyword", service));
        }
        if (keyword != null && !keyword.isBlank()) {
            bool.putObject("must").putObject("match").put("message", keyword);
        }
        return root.toPrettyString();
    }

    /** 聚合：按服务统计日志条数 Top N */
    public static String topServicesBody(String level, int minutes, int topN) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("size", 0);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode filters = bool.putArray("filter");
        filters.add(rangeMinutes(minutes));
        if (level != null && !level.isBlank()) {
            filters.add(term("level.keyword", level));
        }
        ObjectNode agg = root.putObject("aggs").putObject("top_services");
        agg.putObject("terms").put("field", "APP_NAME.keyword").put("size", clampTopN(topN));
        return root.toPrettyString();
    }

    private static ObjectNode rangeMinutes(int minutes) {
        ObjectNode range = MAPPER.createObjectNode();
        range.putObject("@timestamp").put("gte", "now-" + clampMinutes(minutes) + "m").put("lte", "now");
        return MAPPER.createObjectNode().set("range", range);
    }

    private static ObjectNode term(String field, String value) {
        return MAPPER.createObjectNode().set("term", MAPPER.createObjectNode().put(field, value));
    }
}
