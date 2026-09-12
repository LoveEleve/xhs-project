package com.myxhs.ai.config;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端管理（M1.5 接入验证；M2 接入 AgentScope Toolkit 供 Agent 调用）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpClientManager {

    private final McpProperties properties;
    private final Map<String, McpClientWrapper> clients = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public Set<String> serverNames() {
        return Collections.unmodifiableSet(properties.getServers().keySet());
    }

    /** 获取（懒加载初始化）指定 MCP server 客户端 */
    public McpClientWrapper client(String name) {
        McpProperties.Server server = properties.getServers().get(name);
        if (server == null) {
            throw new IllegalArgumentException("未知 MCP server: " + name);
        }
        if (!server.isEnabled()) {
            throw new IllegalStateException("MCP server 未启用: " + name);
        }
        try {
            return clients.computeIfAbsent(name, key -> create(key, server));
        } catch (Exception e) {
            errors.put(name, e.getMessage());
            throw e;
        }
    }

    private McpClientWrapper create(String name, McpProperties.Server server) {
        log.info("[MCP] 初始化 server={} transport={}", name, server.getTransport());
        Duration timeout = Duration.ofSeconds(server.getTimeoutSeconds());
        McpClientBuilder builder = McpClientBuilder.create(name)
                .timeout(timeout)
                .initializationTimeout(timeout);
        switch (server.getTransport().toLowerCase()) {
            case "sse" -> builder.sseTransport(server.getUrl());
            case "streamable-http", "http" -> builder.streamableHttpTransport(server.getUrl());
            default -> builder.stdioTransport(server.getCommand(), server.getArgs(), server.getEnv());
        }
        if (!server.getHeaders().isEmpty()) {
            builder.headers(server.getHeaders());
        }
        McpClientWrapper wrapper = builder.buildSync();
        if (!wrapper.isInitialized()) {
            wrapper.initialize().block(timeout.plusSeconds(10));
        }
        log.info("[MCP] server={} 初始化完成", name);
        return wrapper;
    }

    public List<Map<String, Object>> listTools(String name) {
        McpClientWrapper wrapper = client(name);
        List<McpSchema.Tool> tools = wrapper.listTools().block(Duration.ofSeconds(60));
        List<Map<String, Object>> result = new ArrayList<>();
        if (tools == null) {
            return result;
        }
        for (McpSchema.Tool tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("inputSchema", tool.inputSchema());
            result.add(item);
        }
        return result;
    }

    public McpSchema.CallToolResult callTool(String name, String tool, Map<String, Object> arguments) {
        McpClientWrapper wrapper = client(name);
        McpSchema.CallToolResult result = wrapper
                .callTool(tool, arguments == null ? Map.of() : arguments)
                .block(Duration.ofSeconds(120));
        if (result == null) {
            throw new IllegalStateException("MCP 调用返回空结果: " + name + "/" + tool);
        }
        return result;
    }

    /** 各 server 状态（供健康检查/排障） */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        properties.getServers().forEach((name, server) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enabled", server.isEnabled());
            item.put("transport", server.getTransport());
            item.put("initialized", clients.containsKey(name) && clients.get(name).isInitialized());
            String error = errors.get(name);
            if (error != null) {
                item.put("error", error);
            }
            status.put(name, item);
        });
        return status;
    }

    @PreDestroy
    public void destroy() {
        clients.forEach((name, wrapper) -> {
            try {
                wrapper.close();
                log.info("[MCP] server={} 已关闭", name);
            } catch (Exception e) {
                log.warn("[MCP] server={} 关闭异常: {}", name, e.getMessage());
            }
        });
        clients.clear();
    }
}
