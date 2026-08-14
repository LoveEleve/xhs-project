package com.myxhs.ai.app.config;

import com.myxhs.ai.app.service.mcp.McpObsBridge;
import com.myxhs.ai.tools.DirectObsToolAccess;
import com.myxhs.ai.tools.ObsToolAccess;
import com.myxhs.ai.tools.PrometheusQueryTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 观测工具访问装配（D4 B3 面）：按 myxhs.ai.tools.mode 选择实现。
 * - mcp（默认）：经 MCP server（my-xhs-ai-mcp）取数（全链路）
 * - direct：直连 Prometheus（单元测试 / 无 MCP 降级）
 */
@Configuration
public class ObsToolAccessConfig {

    @Bean
    public ObsToolAccess obsToolAccess(
            @Value("${myxhs.ai.tools.mode:mcp}") String mode,
            McpObsBridge mcpObsBridge,
            PrometheusQueryTool prometheusQueryTool) {
        if ("direct".equalsIgnoreCase(mode)) {
            return new DirectObsToolAccess(prometheusQueryTool);
        }
        return mcpObsBridge;
    }

    @Bean
    public PrometheusQueryTool prometheusQueryTool(com.fasterxml.jackson.databind.ObjectMapper om,
                                                   @Value("${myxhs.ai.obs.prometheus-url:http://21.130.247.89:19090}") String prometheusUrl) {
        return new PrometheusQueryTool(prometheusUrl, om);
    }
}
