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
import java.util.List;
import java.util.Map;

/**
 * OBS-07：Prometheus label 探索（自研，不依赖 MCP）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricLabelsTool implements AgentTool {

    private final PrometheusClient prometheusClient;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "metric_labels";
    }

    @Override
    public String getDescription() {
        return "查询 Prometheus 标签：name 为空列出所有标签名；指定 name 时列出该标签取值（可用 match 过滤 series，如 up==0）。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("name", Map.of("type", "string", "description", "标签名，如 application/instance/job（可选）"));
        props.put("match", Map.of("type", "string", "description", "series 匹配器，如 up==0 或 http_server_requests_seconds_count（可选）"));
        return ToolSupport.schema(props, List.of());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String name = ToolSupport.arg(param, "name");
        String match = ToolSupport.arg(param, "match");
        try {
            JsonNode data = prometheusClient.getJson(MetricQueryBuilder.labelPath(name, match)).path("data");
            List<String> values = new ArrayList<>();
            for (JsonNode item : data) {
                values.add(item.asText(""));
                if (values.size() >= 100) {
                    break;
                }
            }
            auditService.record(ToolSupport.actor(param), "metric.labels", "prometheus",
                    Map.of("name", name == null ? "" : name, "count", values.size()), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of(
                    "label", name == null ? "ALL" : name, "count", values.size(), "values", values)));
        } catch (IllegalArgumentException e) {
            return ToolSupport.error(param, e.getMessage());
        } catch (Exception e) {
            log.warn("[工具] metric_labels 失败: {}", e.getMessage());
            return ToolSupport.error(param, "标签查询失败");
        }
    }
}
