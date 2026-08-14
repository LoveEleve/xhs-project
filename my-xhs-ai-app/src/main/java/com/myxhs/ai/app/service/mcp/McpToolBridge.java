package com.myxhs.ai.app.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import com.myxhs.ai.tools.MetricToolAccess;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP 工具桥接（D2）：my-xhs-ai-app 作为 MCP client 调 my-xhs-ai-mcp 的真实只读业务工具。
 * 协议层在 McpClient（D4 重构抽出，供观测桥接复用）。
 */
@Component
public class McpToolBridge implements MetricToolAccess {

    private final McpClient client;

    public McpToolBridge(ObjectMapper om,
                         @Value("${myxhs.ai.mcp.url:http://localhost:19021/mcp}") String mcpUrl,
                         @Value("${MCP_API_KEY:}") String apiKey) {
        this.client = new McpClient(om, mcpUrl, apiKey);
    }

    @Override
    @Tool("查询下单量（经 MCP：口径=排除已删、含取消/退款，窗口≤31天）")
    public String queryOrderVolume(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，如 2026-08-01~2026-08-07") String window) {
        return client.callTool("order.query_volume", Map.of("window", window));
    }

    @Override
    @Tool("查询支付成功率（经 MCP：成功/(成功+失败)，排除待支付/退款；渠道 Mock）")
    public String paymentSuccessRate(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return client.callTool("payment.success_rate", Map.of("window", window));
    }

    @Override
    @Tool("查询内容互动量（经 MCP：赞/藏/评/分享，曝光单列）")
    public String contentInteraction(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return client.callTool("content.interaction", Map.of("window", window));
    }

    @Override
    @Tool("计算对比基线窗口（经 MCP：上一同长窗口，确定性；模型不得自行推算基线）")
    public String baselineWindow(
            @P("当前时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return client.callTool("baseline.window", Map.of("window", window));
    }

    @Override
    @Tool("查询电商漏斗各环节量（经 MCP：浏览/加购/下单/支付，窗口内）")
    public String funnelConversion(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return client.callTool("funnel.conversion", Map.of("window", window));
    }

    @Override
    @Tool("查询支付失败事件（经 MCP：按失败码聚合，窗口内）")
    public String paymentFailures(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return client.callTool("payment.failures", Map.of("window", window));
    }

    @Override
    @Tool("查询内容发布事件数（经 MCP：按天，窗口内）")
    public String notePublishEvents(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return client.callTool("content.publish_events", Map.of("window", window));
    }
}
