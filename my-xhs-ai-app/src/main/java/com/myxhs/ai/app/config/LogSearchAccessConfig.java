package com.myxhs.ai.app.config;

import com.myxhs.ai.app.service.mcp.McpLogBridge;
import com.myxhs.ai.tools.DirectLogSearchAccess;
import com.myxhs.ai.tools.LogSearchAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 受控日志检索装配（M9-1）：按 myxhs.ai.tools.mode 选择实现。
 * - mcp（默认）：经 MCP server（my-xhs-ai-mcp）调 log.search（全链路，白名单在 MCP 侧）
 * - direct：直读白名单日志文件（单元测试 / 无 MCP 降级）
 * 白名单键 myxhs.ai.log-search.files：service=绝对路径,service2=路径2（不在白名单的服务一律拒绝）
 */
@Configuration
public class LogSearchAccessConfig {

    private static final Logger log = LoggerFactory.getLogger(LogSearchAccessConfig.class);

    @Bean
    public LogSearchAccess logSearchAccess(
            @Value("${myxhs.ai.tools.mode:mcp}") String mode,
            McpLogBridge mcpLogBridge,
            @Value("${myxhs.ai.log-search.files:}") String filesCsv) {
        if ("direct".equalsIgnoreCase(mode)) {
            return new DirectLogSearchAccess(parseWhitelist(filesCsv));
        }
        return mcpLogBridge;
    }

    /** 解析白名单 CSV：service=绝对路径,... */
    public static Map<String, String> parseWhitelist(String filesCsv) {
        Map<String, String> whitelist = new LinkedHashMap<>();
        if (filesCsv == null) {
            return whitelist;
        }
        for (String entry : filesCsv.split(",")) {
            int eq = entry.indexOf('=');
            if (eq > 0 && eq < entry.length() - 1) {
                whitelist.put(entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
            }
        }
        if (whitelist.isEmpty()) {
            log.warn("[log-search] 白名单为空（myxhs.ai.log-search.files 未配置），log.search 将全部拒绝");
        }
        return whitelist;
    }
}
