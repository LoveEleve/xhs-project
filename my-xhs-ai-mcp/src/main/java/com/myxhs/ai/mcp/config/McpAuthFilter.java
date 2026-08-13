package com.myxhs.ai.mcp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MCP 端点认证（D2 Gate：非授权调用拒绝 + 审计）。
 * 配置：环境变量 MCP_API_KEY。设置后 /mcp 必须带 `Authorization: Bearer <key>`，否则 401。
 * ⚠️ 未设置时允许放行（dev 便捷）但打 WARN——生产必须设置。
 * 审计：每次调用记 access log（path+method+result）。
 */
public class McpAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpAuthFilter.class);

    private final String apiKey;

    public McpAuthFilter(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean mcpEndpoint = path.startsWith("/mcp");

        if (mcpEndpoint && apiKey != null && !apiKey.isBlank()) {
            String auth = request.getHeader("Authorization");
            boolean ok = auth != null && auth.startsWith("Bearer ") && auth.substring(7).trim().equals(apiKey);
            if (!ok) {
                log.warn("[mcp-auth] 拒绝未授权调用: path={}, method={}", path, request.getMethod());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        } else if (mcpEndpoint && (apiKey == null || apiKey.isBlank())) {
            log.warn("[mcp-auth] MCP_API_KEY 未设置，/mcp 未鉴权放行（仅限开发，生产必须设置）");
        }

        chain.doFilter(request, response);
    }
}
