package com.myxhs.ai.tools;

import java.util.Map;
import java.util.function.Function;

/**
 * 工具执行器绑定（M12）：catalog 元数据 + 三接口执行器 → 完整注册表。
 * app 与 mcp 各自用此装配（传入各自的三接口实现：桥 / 纯类）。
 * 新增工具 = AgentToolCatalog 加一条 + 此处加一个 case（零改 Harness/PolicyGuard）。
 */
public class AgentToolBinder {

    private AgentToolBinder() {
    }

    public static ToolRegistry build(MetricToolAccess metric, ObsToolAccess obs, LogSearchAccess logSearch) {
        ToolRegistry registry = new ToolRegistry();
        for (ToolSpec spec : AgentToolCatalog.specs()) {
            registry.register(new ToolSpec(spec.name(), spec.mcpName(), spec.description(), spec.level(),
                    spec.schemaJson(), spec.validator(), bind(spec.name(), metric, obs, logSearch)));
        }
        return registry;
    }

    /** 装配点：Agent 工具名 → 执行器（lambda 包装三接口；L3 返回 null=不开放） */
    private static Function<Map<String, String>, String> bind(String name, MetricToolAccess metric,
                                                              ObsToolAccess obs, LogSearchAccess logSearch) {
        return switch (name) {
            case AgentToolNames.QUERY_ORDER_VOLUME -> args -> metric.queryOrderVolume(arg(args, "window"));
            case AgentToolNames.PAYMENT_SUCCESS_RATE -> args -> metric.paymentSuccessRate(arg(args, "window"));
            case AgentToolNames.CONTENT_INTERACTION -> args -> metric.contentInteraction(arg(args, "window"));
            case AgentToolNames.BASELINE_WINDOW -> args -> metric.baselineWindow(arg(args, "window"));
            case AgentToolNames.FUNNEL_CONVERSION -> args -> metric.funnelConversion(arg(args, "window"));
            case AgentToolNames.PAYMENT_FAILURES -> args -> metric.paymentFailures(arg(args, "window"));
            case AgentToolNames.NOTE_PUBLISH_EVENTS -> args -> metric.notePublishEvents(arg(args, "window"));
            case AgentToolNames.HTTP_ERRORS -> args -> obs.httpErrors(arg(args, "service"), arg(args, "hours"));
            case AgentToolNames.HTTP_LATENCY -> args -> obs.httpLatency(arg(args, "service"), arg(args, "hours"));
            case AgentToolNames.MQ_CONSUMER_LAG -> args -> obs.mqConsumerLag(arg(args, "group"));
            case AgentToolNames.MQ_DLQ_BACKLOG -> args -> obs.mqDlqBacklog(arg(args, "consumerGroup"));
            case AgentToolNames.MYSQL_REPLICA_LAG -> args -> obs.mysqlReplicationLag();
            case AgentToolNames.MYSQL_DEADLOCKS -> args -> obs.mysqlDeadlocks();
            case AgentToolNames.LOG_SEARCH -> args -> logSearch == null
                    ? "ERROR: 受控日志检索未装配（logSearchAccess=null）"
                    : logSearch.searchLog(arg(args, "service"), arg(args, "keyword"), arg(args, "tailLines"));
            // L3：不绑定执行器（PolicyGuard 先拒；Harness invoker==null 二次防御）
            case AgentToolNames.L3_DLQ_REDELIVER,
                    AgentToolNames.L3_SERVICE_RESTART,
                    AgentToolNames.L3_ORDER_REFUND -> null;
            default -> args -> "ERROR: 未注册工具 " + name;
        };
    }

    private static String arg(Map<String, String> args, String key) {
        return args == null ? null : args.get(key);
    }
}
