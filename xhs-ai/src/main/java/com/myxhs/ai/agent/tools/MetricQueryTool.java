package com.myxhs.ai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
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
 * OBS-05：裸 PromQL 兜底查询（自研，不依赖 MCP；带白名单/长度校验，只读）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricQueryTool implements AgentTool {

    private final PrometheusClient prometheusClient;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "metric_query";
    }

    @Override
    public String getDescription() {
        return "执行 PromQL 即时查询（复杂聚合/多标签时使用；简单指标优先 metric_top/metric_trend）。只读、带指标白名单校验。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("promql", Map.of("type", "string", "description",
                "PromQL 表达式，如 sum by (application) (rate(http_server_requests_seconds_count[5m]))"));
        return ToolSupport.schema(props, List.of("promql"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String promql = ToolSupport.arg(param, "promql");
        try {
            MetricQueryBuilder.validatePromql(promql);
            JsonNode result = prometheusClient.query(promql).path("data").path("result");
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode series : result) {
                Map<String, Object> item = new LinkedHashMap<>();
                Map<String, Object> labels = new LinkedHashMap<>();
                series.path("metric").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                item.put("labels", labels);
                item.put("value", series.path("value").path(1).asText(""));
                items.add(item);
                if (items.size() >= 50) {
                    break;
                }
            }
            auditService.record(ToolSupport.actor(param), "metric.query", "prometheus",
                    Map.of("series", items.size()), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of("series", items.size(), "items", items)));
        } catch (IllegalArgumentException e) {
            return ToolSupport.error(param, e.getMessage());
        } catch (Exception e) {
            log.warn("[工具] metric_query 失败: {}", e.getMessage());
            return ToolSupport.error(param, "PromQL 查询失败（Prometheus 不可用或表达式非法）");
        }
    }
}
