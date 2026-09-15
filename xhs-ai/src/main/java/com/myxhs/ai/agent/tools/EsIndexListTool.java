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
 * OBS-04：ES 索引清单（自研兜底，不依赖 MCP 子进程）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsIndexListTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EsLogClient esLogClient;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "es_index_list";
    }

    @Override
    public String getDescription() {
        return "列出 Elasticsearch 索引（名称/健康/状态/文档数，按文档数倒序）。不依赖 MCP 进程；需要看索引整体分布时使用。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolSupport.schema(Map.of(), List.of());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            JsonNode rows = MAPPER.readTree(esLogClient.getJson("/_cat/indices?format=json"));
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode row : rows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", row.path("index").asText(""));
                item.put("health", row.path("health").asText(""));
                item.put("status", row.path("status").asText(""));
                item.put("docs", row.path("docs.count").asLong(0));
                items.add(item);
            }
            items.sort((a, b) -> Long.compare(((Number) b.get("docs")).longValue(),
                    ((Number) a.get("docs")).longValue()));
            List<Map<String, Object>> top = items.size() > 50 ? items.subList(0, 50) : items;
            auditService.record(ToolSupport.actor(param), "es.index_list", "es",
                    Map.of("count", items.size()), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of("total", items.size(), "indices", top)));
        } catch (Exception e) {
            log.warn("[工具] es_index_list 失败: {}", e.getMessage());
            return ToolSupport.error(param, "ES 索引列表查询失败");
        }
    }
}
