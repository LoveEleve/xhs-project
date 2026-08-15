package com.myxhs.ai.app.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.tools.LogSearchAccess;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 受控日志检索桥接（M9-1）：经 MCP 调 log.search（白名单校验在 MCP/工具侧）。
 */
@Component
public class McpLogBridge implements LogSearchAccess {

    private final McpClient client;

    public McpLogBridge(ObjectMapper om,
                        @Value("${myxhs.ai.mcp.url:http://localhost:19021/mcp}") String mcpUrl,
                        @Value("${MCP_API_KEY:}") String apiKey) {
        this.client = new McpClient(om, mcpUrl, apiKey);
    }

    @Override
    @Tool("检索服务日志（经 MCP，白名单服务最近 N 行内过滤 keyword）")
    public String searchLog(
            @P("白名单服务名，如 my-xhs-order") String service,
            @P("检索关键词，字母数字与常见符号，长度≤100") String keyword,
            @P("最近多少行内检索（1~5000，默认 500）") String tailLines) {
        return client.callTool("log.search",
                Map.of("service", str(service), "keyword", str(keyword), "tailLines", str(tailLines)));
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
