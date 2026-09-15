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
 * OBS-01：指标 TopN（业务级指标目录，模型无需写 PromQL）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricTopTool implements AgentTool {

    private final PrometheusClient prometheusClient;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "metric_top";
    }

    @Override
    public String getDescription() {
        return "查指标 TopN（近5分钟窗口）。metric 可选：error_rate(各服务5xx错误率%)/qps(各服务QPS)/latency_p95(各服务P95延迟ms)/slow_uri(P95最慢接口ms，可传service)/heap_mb(JVM堆MB)。查指标优先用本工具，无需写 PromQL。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("metric", Map.of("type", "string",
                "description", "指标名：" + MetricQueryBuilder.Metric.keys(), "enum",
                List.of(MetricQueryBuilder.Metric.keys().split("/"))));
        props.put("service", Map.of("type", "string", "description", "服务名过滤（可选，slow_uri 常用），如 my-xhs-cart"));
        props.put("topN", Map.of("type", "integer", "description", "返回条数，默认 10，最大 50"));
        return ToolSupport.schema(props, List.of("metric"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String metricArg = ToolSupport.arg(param, "metric");
        String service = ToolSupport.arg(param, "service");
        int topN = Math.max(1, Math.min(50, ToolSupport.intArg(param, "topN", 10)));
        try {
            MetricQueryBuilder.Metric metric = MetricQueryBuilder.Metric.from(metricArg);
            String promql = MetricQueryBuilder.instant(metric, service);
            JsonNode result = prometheusClient.query(promql).path("data").path("result");
            List<Map<String, Object>> items = new ArrayList<>();
            for (JsonNode series : result) {
                double value = series.path("value").path(1).asDouble(Double.NaN);
                if (Double.isNaN(value)) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                JsonNode labels = series.path("metric");
                item.put("label", labels.path("uri").asText(labels.path("application").asText("")));
                if (!labels.path("application").asText("").isBlank() && !labels.path("uri").asText("").isBlank()) {
                    item.put("service", labels.path("application").asText(""));
                }
                item.put("value", Math.round(value * 1000.0) / 1000.0);
                items.add(item);
            }
            items.sort((a, b) -> Double.compare((double) b.get("value"), (double) a.get("value")));
            List<Map<String, Object>> top = items.size() > topN ? items.subList(0, topN) : items;
            auditService.record(ToolSupport.actor(param), "metric.top", "prometheus",
                    Map.of("metric", metric.key, "count", top.size()), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of(
                    "metric", metric.key, "unit", metric.unit, "window", "5m", "items", top)));
        } catch (IllegalArgumentException e) {
            return ToolSupport.error(param, e.getMessage());
        } catch (Exception e) {
            log.warn("[工具] metric_top 失败: {}", e.getMessage());
            return ToolSupport.error(param, "指标查询失败（Prometheus 不可用或查询超时）");
        }
    }
}
