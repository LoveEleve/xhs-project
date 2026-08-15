package com.myxhs.ai.tools;

import java.util.List;

/**
 * Agent 工具清单（M12 单一事实源）：14 个可用工具 + 3 个 L3 预留。
 * 只定义元数据与参数校验规则（validator）；执行器（invoker）由各模块装配
 * （app 绑 MCP 桥 / direct 实现，mcp 绑纯工具类）——catalog 内 invoker=null。
 */
public class AgentToolCatalog {

    private static final String WINDOW_SCHEMA = """
            {"type":"object","properties":{"window":{"type":"string","description":"时间窗 yyyy-MM-dd~yyyy-MM-dd，跨度≤31天"}},"required":["window"]}
            """;
    private static final String OBS_SCHEMA = """
            {"type":"object","properties":{"service":{"type":"string","description":"服务名，如 my-xhs-order；空=全部"},"hours":{"type":"string","description":"最近小时数 1~168"}},"required":["hours"]}
            """;
    private static final String MQ_SCHEMA = """
            {"type":"object","properties":{"group":{"type":"string","description":"消费组名，如 cart-sync-consumer-group；空=全部"}}}
            """;
    private static final String NOARG_SCHEMA = "{\"type\":\"object\"}";
    private static final String LOG_SEARCH_SCHEMA = """
            {"type":"object","properties":{"service":{"type":"string","description":"白名单服务名，如 my-xhs-order"},"keyword":{"type":"string","description":"检索关键词，字母数字与常见符号，长度≤100"},"tailLines":{"type":"string","description":"最近多少行内检索（1~5000，默认 500）"}},"required":["service","keyword"]}
            """;
    private static final String L3_SCHEMA = "{\"type\":\"object\"}";

    private AgentToolCatalog() {
    }

    /** 完整工具清单（元数据 + 校验规则；invoker 由装配方绑定） */
    public static List<ToolSpec> specs() {
        return List.of(
                spec(AgentToolNames.QUERY_ORDER_VOLUME, "order.query_volume", "查询下单量（口径：排除已删、含取消/退款，按创建时间）",
                        AccessLevel.L1, WINDOW_SCHEMA, windowValidator()),
                spec(AgentToolNames.PAYMENT_SUCCESS_RATE, "payment.success_rate", "查询支付成功率（口径：成功/(成功+失败)，排除待支付/退款；渠道为 Mock）",
                        AccessLevel.L1, WINDOW_SCHEMA, windowValidator()),
                spec(AgentToolNames.CONTENT_INTERACTION, "content.interaction", "查询内容互动量（口径：点赞/收藏/评论/分享，曝光单列）",
                        AccessLevel.L1, WINDOW_SCHEMA, windowValidator()),
                spec(AgentToolNames.BASELINE_WINDOW, "baseline.window", "计算对比基线窗口（上一同长窗口，确定性；模型不得自行推算基线）",
                        AccessLevel.L1, WINDOW_SCHEMA, windowValidator()),
                spec(AgentToolNames.FUNNEL_CONVERSION, "funnel.conversion", "电商漏斗各环节量（浏览/加购/下单/支付，窗口内）",
                        AccessLevel.L1, WINDOW_SCHEMA, windowValidator()),
                spec(AgentToolNames.PAYMENT_FAILURES, "payment.failures", "支付失败事件（PAY_FAIL 按失败码聚合，窗口内）",
                        AccessLevel.L1, WINDOW_SCHEMA, windowValidator()),
                spec(AgentToolNames.NOTE_PUBLISH_EVENTS, "content.publish_events", "内容发布事件数（PUBLISH 按天，窗口内）",
                        AccessLevel.L1, WINDOW_SCHEMA, windowValidator()),
                // 观测面（L2）：log.search 排观测首位（MCP 契约顺序，McpContractTest 锁定）
                spec(AgentToolNames.LOG_SEARCH, "log.search", "检索服务日志（白名单服务最近 N 行内过滤 keyword；M9-1 受控检索）",
                        AccessLevel.L2, LOG_SEARCH_SCHEMA, logSearchValidator()),
                spec(AgentToolNames.HTTP_ERRORS, "service.http_errors", "服务 HTTP 5xx 错误统计（按 uri 聚合，最近 N 小时）",
                        AccessLevel.L2, OBS_SCHEMA, hoursValidator()),
                spec(AgentToolNames.HTTP_LATENCY, "service.http_latency", "服务 HTTP 慢端点 top（P95 延迟秒，最近 N 小时）",
                        AccessLevel.L2, OBS_SCHEMA, hoursValidator()),
                spec(AgentToolNames.MQ_CONSUMER_LAG, "mq.consumer_lag", "RocketMQ 消费积压（按消费组聚合 lag，空=全部）",
                        AccessLevel.L2, MQ_SCHEMA, groupValidator()),
                spec(AgentToolNames.MQ_DLQ_BACKLOG, "mq.dlq_backlog", "RocketMQ 死信积压（按 consumer_group 聚合 backlog，空=全部；-1 为应用侧哨兵值=无 DLQ）",
                        AccessLevel.L2, MQ_SCHEMA, groupValidator()),
                spec(AgentToolNames.MYSQL_REPLICA_LAG, "mysql.replication_lag", "MySQL 主从复制延迟（Seconds_Behind_Master，全部从库）",
                        AccessLevel.L2, NOARG_SCHEMA, null),
                spec(AgentToolNames.MYSQL_DEADLOCKS, "mysql.deadlocks", "MySQL 死锁事件（累计 total + 最新 new_events）",
                        AccessLevel.L2, NOARG_SCHEMA, null),
                // L3 高危动作：V1 无执行器（PolicyGuard requiresApproval 恒拒绝；M11 HITL 挂执行器）
                spec(AgentToolNames.L3_DLQ_REDELIVER, "mq.dlq_redeliver", "MQ 死信消息重投（命令模板写死 + 参数白名单，需人工审批）",
                        AccessLevel.L3, L3_SCHEMA, null),
                spec(AgentToolNames.L3_SERVICE_RESTART, null, "服务重启（需人工审批，V1 未开放）",
                        AccessLevel.L3, L3_SCHEMA, null),
                spec(AgentToolNames.L3_ORDER_REFUND, null, "订单退款（需人工审批，V1 未开放）",
                        AccessLevel.L3, L3_SCHEMA, null));
    }

    private static ToolSpec spec(String name, String mcpName, String desc, AccessLevel level,
                                 String schema, java.util.function.Function<java.util.Map<String, String>, String> validator) {
        return new ToolSpec(name, mcpName, desc, level, schema, validator, null);
    }

    private static java.util.function.Function<java.util.Map<String, String>, String> windowValidator() {
        return args -> ToolParamValidators.validateWindow(args == null ? null : args.get("window"));
    }

    private static java.util.function.Function<java.util.Map<String, String>, String> hoursValidator() {
        return args -> ToolParamValidators.validateHours(args == null ? null : args.get("hours"));
    }

    private static java.util.function.Function<java.util.Map<String, String>, String> groupValidator() {
        return args -> {
            String group = args == null ? null : args.get("group");
            String consumerGroup = args == null ? null : args.get("consumerGroup");
            String g = group != null ? group : consumerGroup;
            return ToolParamValidators.validateGroup(g);
        };
    }

    private static java.util.function.Function<java.util.Map<String, String>, String> logSearchValidator() {
        // 与原 PolicyGuard 行为一致：仅校验 keyword（tailLines 由工具侧 parseTailLines clamp，恒合法）
        return args -> ToolParamValidators.validateKeyword(args == null ? null : args.get("keyword"));
    }
}
