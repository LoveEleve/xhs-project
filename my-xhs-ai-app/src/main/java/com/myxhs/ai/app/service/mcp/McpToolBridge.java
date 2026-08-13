package com.myxhs.ai.app.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import com.myxhs.ai.tools.MetricToolAccess;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 工具桥接（D2）：my-xhs-ai-app 作为 MCP client 调 my-xhs-ai-mcp 的真实只读工具。
 * 实现：JDK HttpClient + Jackson 按 MCP Streamable HTTP 协议（initialize → Mcp-Session-Id → tools/call）。
 * ⚠️ 为何不直接用 SDK client-jdk-http-client：0.18.3 该 artifact 是 15MB fat jar（内嵌未 relocate 的
 *    新版 jackson，与项目 2.16.1 classpath 冲突）→ 薄协议客户端自实现，SDK core 的干净 jar 供类型参考。
 * 认证：MCP_API_KEY 环境变量（与 my-xhs-ai-mcp 同源），无则直连（dev）。
 */
@Component
public class McpToolBridge implements MetricToolAccess {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);
    private static final String ACCEPT = "application/json, text/event-stream";

    private final ObjectMapper om;
    private final HttpClient http;
    private final String mcpUrl;
    private final String apiKey;
    private final AtomicLong idSeq = new AtomicLong(1);

    private volatile String sessionId;

    public McpToolBridge(ObjectMapper om,
                         @Value("${myxhs.ai.mcp.url:http://localhost:19021/mcp}") String mcpUrl,
                         @Value("${MCP_API_KEY:}") String apiKey) {
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.mcpUrl = mcpUrl;
        this.apiKey = apiKey;
    }

    private synchronized void ensureInitialized() {
        if (sessionId != null) {
            return;
        }
        try {
            JsonNode init = post(jsonRpc("initialize", Map.of(
                    "protocolVersion", "2025-03-26",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "my-xhs-ai-app", "version", "1.0.0"))));
            if (init.has("error")) {
                throw new IllegalStateException("MCP initialize 失败: " + init.get("error"));
            }
            log.info("[mcp] initialize ok, server={}", init.path("result").path("serverInfo"));
            postNotify("notifications/initialized", Map.of());
        } catch (Exception e) {
            sessionId = null;
            throw new IllegalStateException("MCP 连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Tool("查询下单量（经 MCP：口径=排除已删、含取消/退款，窗口≤31天）")
    public String queryOrderVolume(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd，如 2026-08-01~2026-08-07") String window) {
        return callTool("order.query_volume", window);
    }

    @Override
    @Tool("查询支付成功率（经 MCP：成功/(成功+失败)，排除待支付/退款；渠道 Mock）")
    public String paymentSuccessRate(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return callTool("payment.success_rate", window);
    }

    @Override
    @Tool("查询内容互动量（经 MCP：赞/藏/评/分享，曝光单列）")
    public String contentInteraction(
            @P("时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return callTool("content.interaction", window);
    }

    @Override
    @Tool("计算对比基线窗口（经 MCP：上一同长窗口，确定性；模型不得自行推算基线）")
    public String baselineWindow(
            @P("当前时间窗，格式 yyyy-MM-dd~yyyy-MM-dd") String window) {
        return callTool("baseline.window", window);
    }

    private String callTool(String name, String window) {
        try {
            ensureInitialized();
            return doCallTool(name, window);
        } catch (Exception e) {
            // 会话可能失效/MCP 服务重启：重置会话并重试一次（一次性自愈）
            log.warn("[mcp] 调用失败，重置会话重试: tool={}, err={}", name, e.getMessage());
            sessionId = null;
            ensureInitialized();
            return doCallTool(name, window);
        }
    }

    private String doCallTool(String name, String window) {
        JsonNode resp = post(jsonRpc("tools/call", Map.of("name", name, "arguments", Map.of("window", window))));
        JsonNode err = resp.get("error");
        if (err != null) {
            throw new IllegalStateException("MCP tools/call " + name + " 失败: " + err);
        }
        JsonNode content = resp.path("result").path("content");
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode c : content) {
                if ("text".equals(c.path("type").asText())) {
                    sb.append(c.path("text").asText());
                }
            }
        }
        if (resp.path("result").path("isError").asBoolean(false)) {
            throw new IllegalStateException("工具返回错误: " + sb);
        }
        return sb.toString();
    }

    private JsonNode post(ObjectNode message) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(mcpUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Accept", ACCEPT)
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(message)));
            if (apiKey != null && !apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            if (sessionId != null) {
                b.header("Mcp-Session-Id", sessionId);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            String session = resp.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (session != null && !session.isBlank()) {
                this.sessionId = session;
            }
            String body = resp.body();
            // Streamable HTTP 可能返回 SSE 格式（event:/data: 行）或纯 JSON：汇总所有 data: 行，无则用原 body
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\n")) {
                String t = line.trim();
                if (t.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(t.substring(5).trim());
                }
            }
            body = data.length() > 0 ? data.toString() : body;
            return om.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("MCP HTTP 调用失败: " + e.getMessage(), e);
        }
    }

    private void postNotify(String method, Map<String, Object> params) {
        ObjectNode n = om.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        if (!params.isEmpty()) {
            n.set("params", om.valueToTree(params));
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(mcpUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Accept", ACCEPT)
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(n)));
            if (apiKey != null && !apiKey.isBlank()) {
                b.header("Authorization", "Bearer " + apiKey);
            }
            if (sessionId != null) {
                b.header("Mcp-Session-Id", sessionId);
            }
            http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[mcp] 通知发送失败: {}", e.getMessage());
        }
    }

    private ObjectNode jsonRpc(String method, Map<String, Object> params) {
        ObjectNode n = om.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("id", idSeq.incrementAndGet());
        n.put("method", method);
        if (params != null && !params.isEmpty()) {
            n.set("params", om.valueToTree(params));
        }
        return n;
    }
}
