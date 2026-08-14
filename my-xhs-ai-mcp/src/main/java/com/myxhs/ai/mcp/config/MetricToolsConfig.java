package com.myxhs.ai.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.tools.BaselineWindowTool;
import com.myxhs.ai.tools.ContentInteractionTool;
import com.myxhs.ai.tools.EventAnalyticsTool;
import com.myxhs.ai.tools.OrderMetricsTool;
import com.myxhs.ai.tools.PaymentMetricsTool;
import com.myxhs.ai.tools.PrometheusQueryTool;
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

    @Bean
    public BaselineWindowTool baselineWindowTool(ObjectMapper objectMapper) {
        return new BaselineWindowTool(objectMapper);
    }

    /** B3 面观测工具（L2 只读，PromQL 查 Prometheus；URL 指向远端 19090） */
    @Bean
    public PrometheusQueryTool prometheusQueryTool(ObjectMapper objectMapper,
                                                   @Value("${myxhs.ai.obs.prometheus-url:http://21.130.247.89:19090}") String prometheusUrl) {
        return new PrometheusQueryTool(prometheusUrl, objectMapper);
    }

    /** A 面事件流水工具（A1 漏斗 / A2 支付失败 / A3 发布） */
    @Bean
    public EventAnalyticsTool eventAnalyticsTool(JdbcTemplate jdbc, OrderMetricsTool orderMetricsTool,
                                                 PaymentMetricsTool paymentMetricsTool) {
        return new EventAnalyticsTool(jdbc, orderMetricsTool, paymentMetricsTool);
    }
}
