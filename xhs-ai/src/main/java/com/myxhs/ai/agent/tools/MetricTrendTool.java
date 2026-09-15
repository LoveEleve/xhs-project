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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OBS-02：指标趋势（判断突发 vs 持续）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricTrendTool implements AgentTool {

    private final PrometheusClient prometheusClient;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "metric_trend";
    }

    @Override
    public String getDescription() {
        return "查某服务的指标趋势（判断突发还是持续）。metric 可选：error_rate/qps/latency_p95；参数：service(必填)、minutes(默认60，最大1440)。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("metric", Map.of("type", "string", "description", "error_rate/qps/latency_p95",
                "enum", List.of("error_rate", "qps", "latency_p95")));
        props.put("service", Map.of("type", "string", "description", "服务名（APP_NAME），如 xhs-ai/my-xhs-order"));
        props.put("minutes", Map.of("type", "integer", "description", "时间窗分钟，默认 60，最大 1440"));
        return ToolSupport.schema(props, List.of("metric", "service"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        try {
            MetricQueryBuilder.Metric metric = MetricQueryBuilder.Metric.from(ToolSupport.arg(param, "metric"));
            String service = ToolSupport.arg(param, "service");
            int minutes = MetricQueryBuilder.clampMinutes(ToolSupport.intArg(param, "minutes", 60));
            int step = MetricQueryBuilder.trendStepSeconds(minutes);
            long end = Instant.now().getEpochSecond();
            long start = end - minutes * 60L;
            JsonNode result = prometheusClient
                    .queryRange(MetricQueryBuilder.trend(metric, service), start, end, step)
                    .path("data").path("result");
            List<Map<String, Object>> points = new ArrayList<>();
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double sum = 0;
            int n = 0;
            for (JsonNode series : result) {
                for (JsonNode point : series.path("values")) {
                    double value = point.path(1).asDouble(Double.NaN);
                    if (Double.isNaN(value)) {
                        continue;
                    }
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                    sum += value;
                    n++;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("time", Instant.ofEpochSecond(point.path(0).asLong()).toString());
                    item.put("value", Math.round(value * 1000.0) / 1000.0);
                    points.add(item);
                }
            }
            if (points.size() > 60) {
                int stride = points.size() / 60 + 1;
                List<Map<String, Object>> sampled = new ArrayList<>();
                for (int i = 0; i < points.size(); i += stride) {
                    sampled.add(points.get(i));
                }
                points = sampled;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            if (n > 0) {
                summary.put("min", Math.round(min * 1000.0) / 1000.0);
                summary.put("max", Math.round(max * 1000.0) / 1000.0);
                summary.put("avg", Math.round(sum / n * 1000.0) / 1000.0);
                summary.put("last", points.isEmpty() ? null : points.get(points.size() - 1).get("value"));
            }
            auditService.record(ToolSupport.actor(param), "metric.trend", "prometheus",
                    Map.of("metric", metric.key, "service", service, "points", points.size()), "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of(
                    "metric", metric.key, "service", service, "minutes", minutes,
                    "stepSeconds", step, "summary", summary, "points", points)));
        } catch (IllegalArgumentException e) {
            return ToolSupport.error(param, e.getMessage());
        } catch (Exception e) {
            log.warn("[工具] metric_trend 失败: {}", e.getMessage());
            return ToolSupport.error(param, "趋势查询失败（Prometheus 不可用或查询超时）");
        }
    }
}
