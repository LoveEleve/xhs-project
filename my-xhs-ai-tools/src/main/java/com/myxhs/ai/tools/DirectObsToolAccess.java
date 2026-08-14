package com.myxhs.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * 观测工具直连实现（D4 B3 面）：直接查 Prometheus（不经 MCP）。
 * 用于：单元测试（内嵌 HTTP server）、无 MCP 场景降级、对比验证。
 */
public class DirectObsToolAccess implements ObsToolAccess {

    private final PrometheusQueryTool tool;

    public DirectObsToolAccess(PrometheusQueryTool tool) {
        this.tool = tool;
    }

    @Override
    @Tool("查询服务 HTTP 5xx 错误统计（直连 Prometheus，按 uri 聚合，最近 N 小时）")
    public String httpErrors(
            @P("服务名，如 my-xhs-order，空字符串=全部服务") String service,
            @P("最近小时数（1~168）") String hours) {
        return tool.httpErrors(service, hours);
    }

    @Override
    @Tool("查询服务 HTTP 慢端点 top（直连 Prometheus，P95 延迟秒，最近 N 小时）")
    public String httpLatency(
            @P("服务名，如 my-xhs-order，空字符串=全部服务") String service,
            @P("最近小时数（1~168）") String hours) {
        return tool.httpLatency(service, hours);
    }

    @Override
    @Tool("查询 RocketMQ 消费积压（直连 Prometheus，按消费组聚合 lag）")
    public String mqConsumerLag(
            @P("消费组名，空字符串=全部消费组") String group) {
        return tool.mqConsumerLag(group);
    }

    @Override
    @Tool("查询 RocketMQ 死信积压（直连 Prometheus，按 consumer_group 聚合 backlog）")
    public String mqDlqBacklog(
            @P("消费组名，空字符串=全部消费组") String consumerGroup) {
        return tool.mqDlqBacklog(consumerGroup);
    }

    @Override
    @Tool("查询 MySQL 主从复制延迟（直连 Prometheus，Seconds_Behind_Master）")
    public String mysqlReplicationLag() {
        return tool.mysqlReplicationLag();
    }

    @Override
    @Tool("查询 MySQL 死锁事件（直连 Prometheus，累计+最新）")
    public String mysqlDeadlocks() {
        return tool.mysqlDeadlocks();
    }
}
