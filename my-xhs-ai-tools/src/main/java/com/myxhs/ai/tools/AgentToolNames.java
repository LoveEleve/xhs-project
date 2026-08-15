package com.myxhs.ai.tools;

/**
 * Agent 工具名常量（M12 单一事实源：catalog/PolicyGuard/测试共用，防字面量漂移）。
 * MCP 侧工具名见 AgentToolCatalog（mcpName 字段）。
 */
public final class AgentToolNames {

    public static final String QUERY_ORDER_VOLUME = "queryOrderVolume";
    public static final String PAYMENT_SUCCESS_RATE = "paymentSuccessRate";
    public static final String CONTENT_INTERACTION = "contentInteraction";
    public static final String BASELINE_WINDOW = "baselineWindow";
    public static final String FUNNEL_CONVERSION = "funnelConversion";
    public static final String PAYMENT_FAILURES = "paymentFailures";
    public static final String NOTE_PUBLISH_EVENTS = "notePublishEvents";
    public static final String HTTP_ERRORS = "httpErrors";
    public static final String HTTP_LATENCY = "httpLatency";
    public static final String MQ_CONSUMER_LAG = "mqConsumerLag";
    public static final String MQ_DLQ_BACKLOG = "mqDlqBacklog";
    public static final String MYSQL_REPLICA_LAG = "mysqlReplicationLag";
    public static final String MYSQL_DEADLOCKS = "mysqlDeadlocks";
    public static final String LOG_SEARCH = "logSearch";

    /** L3 高危动作（V1 无执行器，M11 HITL 挂点） */
    public static final String L3_DLQ_REDELIVER = "dlq.redeliver";
    public static final String L3_SERVICE_RESTART = "service.restart";
    public static final String L3_ORDER_REFUND = "order.refund";

    private AgentToolNames() {
    }
}
