package com.myxhs.ai.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 安全装配：/mcp 认证过滤器（token 来自 MCP_API_KEY 环境变量）。
 */
@Configuration
public class McpSecurityConfig {

    @Bean
    public McpAuthFilter mcpAuthFilter(@Value("${MCP_API_KEY:}") String apiKey) {
        return new McpAuthFilter(apiKey);
    }

    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterRegistration(McpAuthFilter filter) {
        FilterRegistrationBean<McpAuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(filter);
        reg.addUrlPatterns("/mcp", "/mcp/*");
        reg.setOrder(1);
        return reg;
    }
}
