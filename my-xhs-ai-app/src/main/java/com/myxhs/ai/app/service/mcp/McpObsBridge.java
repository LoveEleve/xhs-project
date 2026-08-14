package com.myxhs.ai.app.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.tools.ObsToolAccess;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 观测工具桥接（D4 B3 面）：经 MCP 调 service.http_errors / service.http_latency（L2 只读）。
 */
@Component
public class McpObsBridge implements ObsToolAccess {

    private final McpClient client;

    public McpObsBridge(ObjectMapper om,
                        @Value("${myxhs.ai.mcp.url:http://localhost:19021/mcp}") String mcpUrl,
                        @Value("${MCP_API_KEY:}") String apiKey) {
        this.client = new McpClient(om, mcpUrl, apiKey);
    }

    @Override
    @Tool("查询服务 HTTP 5xx 错误统计（经 MCP，按 uri 聚合，最近 N 小时）")
    public String httpErrors(
            @P("服务名，如 my-xhs-order，空字符串=全部服务") String service,
            @P("最近小时数（1~168）") String hours) {
        return client.callTool("service.http_errors", Map.of("service", str(service), "hours", str(hours)));
    }

    @Override
    @Tool("查询服务 HTTP 慢端点 top（经 MCP，P95 延迟秒，最近 N 小时）")
    public String httpLatency(
            @P("服务名，如 my-xhs-order，空字符串=全部服务") String service,
            @P("最近小时数（1~168）") String hours) {
        return client.callTool("service.http_latency", Map.of("service", str(service), "hours", str(hours)));
    }

    @Override
    @Tool("查询 RocketMQ 消费积压（经 MCP，按消费组聚合 lag）")
    public String mqConsumerLag(
            @P("消费组名，空字符串=全部消费组") String group) {
        return client.callTool("mq.consumer_lag", Map.of("group", str(group)));
    }

    @Override
    @Tool("查询 RocketMQ 死信积压（经 MCP，按 consumer_group 聚合 backlog）")
    public String mqDlqBacklog(
            @P("消费组名，空字符串=全部消费组") String consumerGroup) {
        return client.callTool("mq.dlq_backlog", Map.of("consumerGroup", str(consumerGroup)));
    }

    @Override
    @Tool("查询 MySQL 主从复制延迟（经 MCP，Seconds_Behind_Master）")
    public String mysqlReplicationLag() {
        return client.callTool("mysql.replication_lag", Map.of());
    }

    @Override
    @Tool("查询 MySQL 死锁事件（经 MCP，累计+最新）")
    public String mysqlDeadlocks() {
        return client.callTool("mysql.deadlocks", Map.of());
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
