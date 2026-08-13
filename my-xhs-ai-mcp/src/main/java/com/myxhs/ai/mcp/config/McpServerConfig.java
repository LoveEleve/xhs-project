package com.myxhs.ai.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.tools.BaselineWindowTool;
import com.myxhs.ai.tools.ContentInteractionTool;
import com.myxhs.ai.tools.OrderMetricsTool;
import com.myxhs.ai.tools.PaymentMetricsTool;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Map;
import java.util.function.Function;

/**
 * MCP 服务装配（D2）：Streamable HTTP 暴露 3 个只读指标工具（复用 my-xhs-ai-tools）。
 * 端点 /mcp；工具参数统一 window(yyyy-MM-dd~yyyy-MM-dd)。
 * 安全：工具只读（SELECT 账号），口径/容错在 tools 模块内。
 */
@Configuration
public class McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfig.class);

    private static final String WINDOW_SCHEMA = """
            {"type":"object","properties":{"window":{"type":"string","description":"时间窗 yyyy-MM-dd~yyyy-MM-dd，跨度≤31天"}},"required":["window"]}
            """;

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
                                       BaselineWindowTool baselineWindowTool) {
        return McpServer.sync(transport)
                .serverInfo("my-xhs-ai-mcp", "1.0.0")
                .tools(
                        toolSpec(jsonMapper, "order.query_volume",
                                "查询下单量（口径：排除已删、含取消/退款，按创建时间）",
                                orderMetricsTool::queryOrderVolume),
                        toolSpec(jsonMapper, "payment.success_rate",
                                "查询支付成功率（口径：成功/(成功+失败)，排除待支付/退款；渠道为 Mock）",
                                paymentMetricsTool::paymentSuccessRate),
                        toolSpec(jsonMapper, "content.interaction",
                                "查询内容互动量（口径：点赞/收藏/评论/分享，曝光单列）",
                                contentInteractionTool::contentInteraction),
                        toolSpec(jsonMapper, "baseline.window",
                                "计算对比基线窗口（上一同长窗口，确定性，模型不得自行推算）",
                                baselineWindowTool::baselineWindow)
                )
                .build();
    }

    private static McpServerFeatures.SyncToolSpecification toolSpec(McpJsonMapper mapper, String name, String desc,
                                                                    Function<String, String> fn) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(name)
                .description(desc)
                .inputSchema(mapper, WINDOW_SCHEMA)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        Map<String, Object> args = request.arguments();
                        String window = args == null ? null : String.valueOf(args.get("window"));
                        if (window == null || window.isBlank()) {
                            return new McpSchema.CallToolResult("缺少参数 window（yyyy-MM-dd~yyyy-MM-dd）", true);
                        }
                        long start = System.currentTimeMillis();
                        String result = fn.apply(window);
                        // 审计：工具调用记录（工具名/窗口/耗时/成功）
                        log.info("[mcp-audit] tool={} window={} ok costMs={}", name, window, System.currentTimeMillis() - start);
                        return new McpSchema.CallToolResult(result, false);
                    } catch (Exception e) {
                        log.warn("[mcp-audit] tool={} error: {}", name, e.getMessage());
                        return new McpSchema.CallToolResult("工具调用失败: " + e.getMessage(), true);
                    }
                })
                .build();
    }
}
