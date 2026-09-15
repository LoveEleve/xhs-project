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
 * DIAG-10：按服务聚合日志条数 TopN（业务级参数）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogTopServicesTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EsLogClient esLogClient;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "log_top_services";
    }

    @Override
    public String getDescription() {
        return "按服务聚合统计日志条数 TopN（用于定位哪个服务日志最多）。参数：level(默认ERROR)、minutes(默认60)、topN(默认10)。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("level", Map.of("type", "string", "description", "日志级别：ERROR/WARN/INFO，默认 ERROR"));
        props.put("minutes", Map.of("type", "integer", "description", "时间窗（分钟），默认 60，最大 1440"));
        props.put("topN", Map.of("type", "integer", "description", "返回服务数，默认 10，最大 50"));
        return ToolSupport.schema(props, List.of());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String level = ToolSupport.arg(param, "level");
        int minutes = ToolSupport.intArg(param, "minutes", 60);
        int topN = ToolSupport.intArg(param, "topN", 10);
        try {
            String body = LogQueryBuilder.topServicesBody(level == null ? "ERROR" : level, minutes, topN);
            JsonNode root = MAPPER.readTree(esLogClient.search(body));
            List<Map<String, Object>> buckets = new ArrayList<>();
            for (JsonNode bucket : root.path("aggregations").path("top_services").path("buckets")) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("service", bucket.path("key").asText(""));
                item.put("count", bucket.path("doc_count").asLong(0));
                buckets.add(item);
            }
            auditService.record(ToolSupport.actor(param), "log.top_services", "es",
                    Map.of("level", level == null ? "" : level, "minutes", minutes, "buckets", buckets.size()),
                    "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of(
                    "level", level == null ? "ERROR" : level, "minutes", minutes, "buckets", buckets)));
        } catch (Exception e) {
            log.warn("[工具] log_top_services 失败: {}", e.getMessage());
            return ToolSupport.error(param, "日志聚合失败（请缩小时间窗或稍后重试）");
        }
    }
}
