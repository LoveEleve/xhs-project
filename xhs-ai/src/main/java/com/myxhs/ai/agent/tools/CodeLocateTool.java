package com.myxhs.ai.agent.tools;

import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.code.CodeLocateService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码定位（read-only；v1 轻量解析：文件扫描 + 符号匹配）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CodeLocateTool implements AgentTool {

    private final CodeLocateService codeLocateService;
    private final AuditService auditService;

    @Override
    public String getName() {
        return "code_locate";
    }

    @Override
    public String getDescription() {
        return "按类/方法/关键字在代码库中定位位置（返回 文件:行号 + 片段）。"
                + "回答\"某逻辑在哪个类/方法\"类问题必须用它取证并引用具体位置。";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", Map.of("type", "string", "description", "类名/方法名/关键字，如 InventoryService.doPreDeduct"));
        props.put("limit", Map.of("type", "integer", "description", "返回条数（默认 8，最大 20）"));
        return ToolSupport.schema(props, List.of("query"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String query = ToolSupport.arg(param, "query");
        if (query == null || query.isBlank()) {
            return ToolSupport.error(param, "缺少必填参数 query");
        }
        try {
            int limit = 8;
            String limitArg = ToolSupport.arg(param, "limit");
            if (limitArg != null) {
                try {
                    limit = Integer.parseInt(limitArg);
                } catch (NumberFormatException ignored) {
                }
            }
            Map<String, Object> result = codeLocateService.locate(query, limit);
            Object matches = result.get("matches");
            int count = matches instanceof List<?> list ? list.size() : 0;
            auditService.record(ToolSupport.actor(param), "code.locate", "query=" + query,
                    Map.of("matches", count), "ok", ToolSupport.traceId(param));
            log.info("[代码定位] query={}, matches={}", query, count);
            return ToolSupport.result(param, ToolSupport.json(result));
        } catch (Exception e) {
            log.warn("[代码定位] 失败 query={}: {}", query, e.getMessage());
            return ToolSupport.error(param, "代码定位失败（内部错误）");
        }
    }
}
