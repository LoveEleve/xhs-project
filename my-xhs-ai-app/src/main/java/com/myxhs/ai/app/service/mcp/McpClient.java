package com.myxhs.ai.app.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 薄协议客户端（D2 起自研，D4 B3 面复用）：initialize → Mcp-Session-Id → tools/call。
 * 实现：JDK HttpClient + Jackson 按 MCP Streamable HTTP 协议；会话自愈（一次性重置重试）。
 * 为何不直接用 SDK client-jdk-http-client：0.18.3 该 artifact 是 15MB fat jar（内嵌未 relocate
 * 的 jackson 冲突）→ 薄协议客户端自实现，SDK core 的干净 jar 供类型参考。
 * 认证：MCP_API_KEY 环境变量（与 my-xhs-ai-mcp 同源），无则直连（dev）。
 */
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String ACCEPT = "application/json, text/event-stream";

    private final ObjectMapper om;
    private final HttpClient http;
    private final String mcpUrl;
    private final String apiKey;
    private final AtomicLong idSeq = new AtomicLong(1);

    private volatile String sessionId;

    public McpClient(ObjectMapper om, String mcpUrl, String apiKey) {
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.mcpUrl = mcpUrl;
        this.apiKey = apiKey;
    }

    /** 调用工具；会话失效自动重置重试一次 */
    public String callTool(String name, Map<String, Object> args) {
        try {
            ensureInitialized();
            return doCallTool(name, args);
        } catch (Exception e) {
            log.warn("[mcp] 调用失败，重置会话重试: tool={}, err={}", name, e.getMessage());
            sessionId = null;
            ensureInitialized();
            return doCallTool(name, args);
        }
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

    private String doCallTool(String name, Map<String, Object> args) {
        JsonNode resp = post(jsonRpc("tools/call", Map.of("name", name, "arguments", args)));
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
