package com.myxhs.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.audit.AuditService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OBS-06：ES 原生检索兜底（自研，不依赖 MCP）。queryBody 以 JSON 字符串传入，避免嵌套 schema。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsSearchTool implements AgentTool {

    /** 允许的索引白名单前缀（防跨索引越权/误查系统索引） */
    static final List<String> ALLOWED_PREFIX = List.of("myxhs-", ".alerts", "xhs-ai");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EsLogClient esLogClient;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "es_search";
    }

    @Override
    public String getDescription() {
        return "执行 Elasticsearch 原生 DSL 检索（复杂查询兜底）。参数：index(默认 myxhs-logs-*)、queryBody(JSON 字符串，如 {\"query\":{\"term\":{\"level.keyword\":\"ERROR\"}},\"size\":5})。简单日志问题优先 log_search/log_top_services。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("index", Map.of("type", "string", "description", "索引或模式，默认 myxhs-logs-*"));
        props.put("queryBody", Map.of("type", "string", "description", "ES DSL JSON 字符串（对象），如 {\"query\":{...},\"size\":5}"));
        props.put("size", Map.of("type", "integer", "description", "返回条数上限，默认 10，最大 50"));
        return ToolSupport.schema(props, List.of("queryBody"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String index = ToolSupport.arg(param, "index");
        String body = ToolSupport.arg(param, "queryBody");
        int size = Math.max(1, Math.min(50, ToolSupport.intArg(param, "size", 10)));
        try {
            String queryIndex = index == null || index.isBlank() ? "myxhs-logs-*" : index;
            validateIndex(queryIndex);
            JsonNode root = MAPPER.readTree(esLogClient.searchIndex(queryIndex, buildBody(body, size)));
            long total = root.path("hits").path("total").path("value").asLong(0);
            List<Map<String, Object>> hits = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                Map<String, Object> item = new LinkedHashMap<>();
                JsonNode src = hit.path("_source");
                item.put("index", hit.path("_index").asText(""));
                item.put("id", hit.path("_id").asText(""));
                item.put("source", truncateFields(src));
                hits.add(item);
            }
            auditService.record(ToolSupport.actor(param), "es.search", "es",
                    Map.of("index", queryIndex, "hits", total), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of("total", total, "hits", hits)));
        } catch (IllegalArgumentException e) {
            return ToolSupport.error(param, e.getMessage());
        } catch (Exception e) {
            log.warn("[工具] es_search 失败: {}", e.getMessage());
            return ToolSupport.error(param, "ES 检索失败（请检查 DSL/索引）");
        }
    }

    /** 校验索引白名单并注入 size */
    static String buildBody(String queryBody, int size) throws Exception {
        if (queryBody == null || queryBody.isBlank()) {
            throw new IllegalArgumentException("queryBody 必填（JSON 字符串）");
        }
        JsonNode parsed = MAPPER.readTree(queryBody);
        if (!parsed.isObject()) {
            throw new IllegalArgumentException("queryBody 必须是 JSON 对象");
        }
        com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
        if (!node.has("size")) {
            node.put("size", size);
        }
        return node.toString();
    }

    static void validateIndex(String index) {
        for (String prefix : ALLOWED_PREFIX) {
            if (index.startsWith(prefix)) {
                return;
            }
        }
        throw new IllegalArgumentException("索引不在白名单前缀内: " + String.join("/", ALLOWED_PREFIX));
    }

    private static Map<String, Object> truncateFields(JsonNode src) {
        Map<String, Object> fields = new LinkedHashMap<>();
        src.fields().forEachRemaining(e -> {
            String value = e.getValue().asText("");
            fields.put(e.getKey(), value.length() <= 200 ? value : value.substring(0, 200) + "...");
        });
        return fields;
    }
}
