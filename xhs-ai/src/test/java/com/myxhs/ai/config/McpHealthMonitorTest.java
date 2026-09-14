package com.myxhs.ai.config;

import io.agentscope.harness.agent.tools.McpServerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpHealthMonitorTest {

    @Test
    void markerFromScopedNpxPackage() {
        McpServerConfig config = new McpServerConfig();
        config.setCommand("/opt/node20/bin/npx");
        config.setArgs(List.of("-y", "@elastic/mcp-server-elasticsearch@0.1.1"));
        assertThat(McpHealthMonitor.processMarker(config)).isEqualTo("mcp-server-elasticsearch");
    }

    @Test
    void markerFallsBackToCommandBasename() {
        McpServerConfig config = new McpServerConfig();
        config.setCommand("/opt/mcp-servers/prometheus-mcp-server");
        config.setArgs(List.of("--web.listen-address=127.0.0.1:0", "--mcp.tools=query"));
        assertThat(McpHealthMonitor.processMarker(config)).isEqualTo("prometheus-mcp-server");
    }
}
