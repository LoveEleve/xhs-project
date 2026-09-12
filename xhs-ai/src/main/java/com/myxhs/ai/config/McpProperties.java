package com.myxhs.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server 配置（M1.5：官方 Elastic/Prometheus/Grafana MCP）
 * <p>见 docs/design/05-mcp.md、docs/design/09-feature-driven-ecosystem.md</p>
 */
@Data
@ConfigurationProperties(prefix = "ai.mcp")
public class McpProperties {

    private Map<String, Server> servers = new LinkedHashMap<>();

    @Data
    public static class Server {
        /** 是否启用 */
        private boolean enabled = true;
        /** stdio / sse / streamable-http */
        private String transport = "stdio";
        /** stdio 启动命令 */
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new HashMap<>();
        /** sse / streamable-http 地址 */
        private String url;
        private Map<String, String> headers = new HashMap<>();
        /** 初始化/调用超时（秒） */
        private long timeoutSeconds = 60;
    }
}
