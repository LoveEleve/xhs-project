package com.myxhs.ai.app.config;

import com.myxhs.ai.app.service.mcp.McpToolBridge;
import com.myxhs.ai.tools.ContentInteractionTool;
import com.myxhs.ai.tools.DirectMetricToolAccess;
import com.myxhs.ai.tools.EventAnalyticsTool;
import com.myxhs.ai.tools.MetricToolAccess;
import com.myxhs.ai.tools.OrderMetricsTool;
import com.myxhs.ai.tools.PaymentMetricsTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 指标工具访问装配（D2 收尾）：按 myxhs.ai.tools.mode 选择实现。
 * - mcp（默认）：Agent/路由经 MCP server（my-xhs-ai-mcp）取数（全链路）
 * - direct：直连 MySQL（单元测试 H2 / 无 MCP 降级 / 对比验证）
 */
@Configuration
public class MetricToolAccessConfig {

    @Bean
    public MetricToolAccess metricToolAccess(
            @Value("${myxhs.ai.tools.mode:mcp}") String mode,
            McpToolBridge mcpToolBridge,
            OrderMetricsTool orderMetricsTool,
            PaymentMetricsTool paymentMetricsTool,
            ContentInteractionTool contentInteractionTool,
            EventAnalyticsTool eventAnalyticsTool) {
        if ("direct".equalsIgnoreCase(mode)) {
            return new DirectMetricToolAccess(orderMetricsTool, paymentMetricsTool,
                    contentInteractionTool, new com.myxhs.ai.tools.BaselineWindowTool(), eventAnalyticsTool);
        }
        return mcpToolBridge;
    }

    // 直连模式需要的工具 bean（mcp 模式也可保留，用于 mcp server 侧验证/降级）
    @Bean
    public OrderMetricsTool orderMetricsTool(JdbcTemplate jdbc,
                                             @Value("${myxhs.ai.metric.order.shards:}") String shardsCsv) {
        return new OrderMetricsTool(jdbc, shardsCsv);
    }

    @Bean
    public PaymentMetricsTool paymentMetricsTool(JdbcTemplate jdbc) {
        return new PaymentMetricsTool(jdbc);
    }

    @Bean
    public ContentInteractionTool contentInteractionTool(JdbcTemplate jdbc) {
        return new ContentInteractionTool(jdbc);
    }

    @Bean
    public EventAnalyticsTool eventAnalyticsTool(JdbcTemplate jdbc, OrderMetricsTool orderMetricsTool,
                                                 PaymentMetricsTool paymentMetricsTool) {
        return new EventAnalyticsTool(jdbc, orderMetricsTool, paymentMetricsTool);
    }
}
