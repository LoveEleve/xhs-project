package com.myxhs.ai.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.tools.AgentToolBinder;
import com.myxhs.ai.tools.BaselineWindowTool;
import com.myxhs.ai.tools.ContentInteractionTool;
import com.myxhs.ai.tools.DirectLogSearchAccess;
import com.myxhs.ai.tools.DirectMetricToolAccess;
import com.myxhs.ai.tools.DirectObsToolAccess;
import com.myxhs.ai.tools.EventAnalyticsTool;
import com.myxhs.ai.tools.OrderMetricsTool;
import com.myxhs.ai.tools.PaymentMetricsTool;
import com.myxhs.ai.tools.PrometheusQueryTool;
import com.myxhs.ai.tools.ToolRegistry;
import com.myxhs.ai.tools.ToolSpec;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * MCP 服务装配（D2 + M12 注册表化）：Streamable HTTP 暴露注册表中的可用工具
 * （catalog 元数据 + Direct 执行器，AgentToolBinder 单一事实源）。
 * 工具列表 = 注册表导出（新增工具注册即上 MCP，零改本类）；L3 工具不上线（M11 HITL）。
 * 端点 /mcp；必填参数来自 spec.schemaJson 的 required（与 app 侧 PolicyGuard 校验职责分离）。
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public WebMvcStreamableServerTransportProvider mcpTransportProvider(McpJsonMapper jsonMapper) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/mcp")
                .build();
    }

    /** 把 transport 的 /mcp 路由注册进 Spring MVC */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcStreamableServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean
    public McpSyncServer mcpSyncServer(WebMvcStreamableServerTransportProvider transport,
                                       McpJsonMapper jsonMapper,
                                       OrderMetricsTool orderMetricsTool,
                                       PaymentMetricsTool paymentMetricsTool,
                                       ContentInteractionTool contentInteractionTool,
                                       BaselineWindowTool baselineWindowTool,
                                       PrometheusQueryTool prometheusQueryTool,
                                       EventAnalyticsTool eventAnalyticsTool,
                                       DirectLogSearchAccess directLogSearchAccess) {
        // M12：注册表 = catalog 元数据 + Direct 三接口执行器（与 app 侧桥接同源装配）
        ToolRegistry registry = AgentToolBinder.build(
                new DirectMetricToolAccess(orderMetricsTool, paymentMetricsTool, contentInteractionTool,
                        baselineWindowTool, eventAnalyticsTool),
                new DirectObsToolAccess(prometheusQueryTool),
                directLogSearchAccess);
        var server = McpServer.sync(transport)
                .serverInfo("my-xhs-ai-mcp", "1.0.0");
        var specs = registry.all().stream()
                .filter(ToolSpec::usable) // L3 未开放不上线
                .map(spec -> toolSpecFrom(jsonMapper, spec))
                .toList();
        log.info("[mcp] 工具列表（注册表导出）: {} 个: {}", specs.size(),
                specs.stream().map(s -> s.tool().name()).toList());
        return server.tools(specs.toArray(new McpServerFeatures.SyncToolSpecification[0])).build();
    }

    /** 从注册表 spec 生成 MCP 工具（schema + 必填检查 + 执行器 + 审计；校验职责在 app 侧 PolicyGuard） */
    private static McpServerFeatures.SyncToolSpecification toolSpecFrom(McpJsonMapper mapper, ToolSpec spec) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(spec.mcpName())
                .description(spec.description())
                .inputSchema(mapper, spec.schemaJson())
                .build();
        Set<String> required = parseRequired(spec.schemaJson());
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> args = request.arguments();
                        for (String r : required) {
                            if (args == null || args.get(r) == null) {
                                return new McpSchema.CallToolResult("缺少参数 " + r, true);
                            }
                        }
                        long start = System.currentTimeMillis();
                        String result = spec.invoker().apply(strMap(args));
                        // 审计：工具调用记录（工具名/参数/耗时/成功）
                        log.info("[mcp-audit] tool={} args={} ok costMs={}",
                                spec.mcpName(), args, System.currentTimeMillis() - start);
                        return new McpSchema.CallToolResult(result, false);
                    } catch (Exception e) {
                        log.warn("[mcp-audit] tool={} error: {}", spec.mcpName(), e.getMessage());
                        return new McpSchema.CallToolResult("工具调用失败: " + e.getMessage(), true);
                    }
                })
                .build();
    }

    /** schemaJson 的 required 数组（必填参数）；解析失败=无必填 */
    private static Set<String> parseRequired(String schemaJson) {
        Set<String> required = new LinkedHashSet<>();
        if (schemaJson == null) {
            return required;
        }
        try {
            var node = new ObjectMapper().readTree(schemaJson).path("required");
            node.forEach(e -> required.add(e.asText()));
        } catch (Exception ignored) {
        }
        return required;
    }

    private static Map<String, String> strMap(Map<String, Object> args) {
        Map<String, String> m = new LinkedHashMap<>();
        if (args != null) {
            args.forEach((k, v) -> m.put(k, v == null ? null : String.valueOf(v)));
        }
        return m;
    }

    /** 受控日志检索实现 bean（白名单 service→文件路径，配置注入；不在白名单的服务不可检索） */
    @Bean
    public DirectLogSearchAccess directLogSearchAccess(
            @org.springframework.beans.factory.annotation.Value("${myxhs.ai.log-search.files:}") String filesCsv) {
        Map<String, String> whitelist = new LinkedHashMap<>();
        for (String entry : filesCsv.split(",")) {
            int eq = entry.indexOf('=');
            if (eq > 0 && eq < entry.length() - 1) {
                whitelist.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
            }
        }
        if (whitelist.isEmpty()) {
            log.warn("[log.search] 白名单为空（myxhs.ai.log-search.files 未配置），log.search 将全部拒绝");
        }
        return new DirectLogSearchAccess(whitelist);
    }
}
