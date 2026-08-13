package com.myxhs.ai.mcp.config;

import com.myxhs.ai.tools.ContentInteractionTool;
import com.myxhs.ai.tools.OrderMetricsTool;
import com.myxhs.ai.tools.PaymentMetricsTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 指标工具装配（复用 my-xhs-ai-tools 纯类；与 my-xhs-ai-app 同构）。
 */
@Configuration
public class MetricToolsConfig {

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
}
