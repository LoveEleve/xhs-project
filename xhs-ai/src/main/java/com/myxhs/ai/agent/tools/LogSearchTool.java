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
 * DIAG-09：日志明细检索（业务级参数，模型无需写 ES DSL）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogSearchTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EsLogClient esLogClient;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "log_search";
    }

    @Override
    public String getDescription() {
        return "检索 myxhs 日志（ES）。参数：service(服务名，可选)、level(级别，默认ERROR)、keyword(关键字，可选)、minutes(时间窗分钟，默认60)、size(条数，默认20)。查日志优先用本工具，无需构造 ES DSL。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("service", Map.of("type", "string", "description", "服务名（APP_NAME），如 my-xhs-gateway/xhs-ai，可选"));
        props.put("level", Map.of("type", "string", "description", "日志级别：ERROR/WARN/INFO，默认 ERROR"));
        props.put("keyword", Map.of("type", "string", "description", "消息关键字（全文匹配），可选"));
        props.put("minutes", Map.of("type", "integer", "description", "时间窗（分钟），默认 60，最大 1440"));
        props.put("size", Map.of("type", "integer", "description", "返回条数，默认 20，最大 100"));
        return ToolSupport.schema(props, List.of());
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String service = ToolSupport.arg(param, "service");
        String level = ToolSupport.arg(param, "level");
        String keyword = ToolSupport.arg(param, "keyword");
        int minutes = ToolSupport.intArg(param, "minutes", 60);
        int size = ToolSupport.intArg(param, "size", 20);
        try {
            String body = LogQueryBuilder.searchBody(service, level == null ? "ERROR" : level, keyword, minutes, size);
            JsonNode root = MAPPER.readTree(esLogClient.search(body));
            long total = root.path("hits").path("total").path("value").asLong(0);
            List<Map<String, Object>> samples = new ArrayList<>();
            for (JsonNode hit : root.path("hits").path("hits")) {
                JsonNode src = hit.path("_source");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("time", src.path("@timestamp").asText(""));
                item.put("service", src.path("APP_NAME").asText(src.path("appName").asText("")));
                item.put("level", src.path("level").asText(""));
                item.put("logger", firstNonBlank(src, "logger", "logger_name", "LOGGER"));
                item.put("message", truncate(src.path("message").asText(""), 300));
                samples.add(item);
            }
            auditService.record(ToolSupport.actor(param), "log.search", "es",
                    Map.of("service", service == null ? "" : service, "level", level == null ? "" : level,
                            "minutes", minutes, "hits", total),
                    "ok", ToolSupport.traceId(param));
            return ToolSupport.result(param, ToolSupport.json(Map.of(
                    "total", total, "minutes", minutes, "samples", samples)));
        } catch (Exception e) {
            log.warn("[工具] log_search 失败: {}", e.getMessage());
            return ToolSupport.error(param, "日志检索失败（请缩小时间窗或稍后重试）");
        }
    }

    static String firstNonBlank(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    static String truncate(String text, int max) {
        return text == null ? "" : (text.length() <= max ? text : text.substring(0, max) + "...");
    }
}
