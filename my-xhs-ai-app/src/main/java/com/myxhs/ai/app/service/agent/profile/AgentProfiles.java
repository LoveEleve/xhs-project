package com.myxhs.ai.app.service.agent.profile;

import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.tools.AgentToolNames;

import java.util.Set;

/**
 * 双 Agent 画像（M13 PoC）：BUSINESS（业务归因）/ OPS（技术排障）/ FULL（单 Agent 现状基线）。
 * prompt = 通用规则段 + 领域工具列表段（AgentHarness.SYSTEM_BASE_RULES 拆分，FULL 与现状逐字一致）。
 */
public final class AgentProfiles {

    private static final AgentBudget BUDGET = AgentBudget.defaults();

    private static final Set<String> BUSINESS_TOOLS = Set.of(
            AgentToolNames.QUERY_ORDER_VOLUME, AgentToolNames.PAYMENT_SUCCESS_RATE,
            AgentToolNames.CONTENT_INTERACTION, AgentToolNames.BASELINE_WINDOW,
            AgentToolNames.FUNNEL_CONVERSION, AgentToolNames.PAYMENT_FAILURES,
            AgentToolNames.NOTE_PUBLISH_EVENTS);

    private static final Set<String> OPS_TOOLS = Set.of(
            AgentToolNames.HTTP_ERRORS, AgentToolNames.HTTP_LATENCY,
            AgentToolNames.MQ_CONSUMER_LAG, AgentToolNames.MQ_DLQ_BACKLOG,
            AgentToolNames.MQ_DLQ_QUERY,
            AgentToolNames.MYSQL_REPLICA_LAG, AgentToolNames.MYSQL_DEADLOCKS,
            AgentToolNames.LOG_SEARCH, AgentToolNames.L3_DLQ_REDELIVER);

    private static final Set<String> ALL_TOOLS = new java.util.HashSet<>(BUSINESS_TOOLS);
    static {
        ALL_TOOLS.addAll(OPS_TOOLS);
    }

    public static final AgentProfile BUSINESS = new AgentProfile("BUSINESS",
            com.myxhs.ai.app.service.agent.harness.AgentHarness.PROMPT_HEAD
                    + com.myxhs.ai.app.service.agent.harness.AgentHarness.BUSINESS_TOOL_LIST
                    + "\n" + com.myxhs.ai.app.service.agent.harness.AgentHarness.PROMPT_TAIL
                    + "\n你是业务归因 Agent：聚焦订单/支付/内容/漏斗的业务指标下降或波动归因，"
                    + "使用窗口对比（当前 vs 基线）定位断点；不涉及服务观测/日志排障（那是技术排障 Agent 的职责）。",
            BUSINESS_TOOLS, BUDGET);

    public static final AgentProfile OPS = new AgentProfile("OPS",
            "你是 my-xhs 运营诊断 Agent。目标是查清用户问题，通过多步工具调查归因。\n"
                    + "可用工具（技术排障域，只读；观测窗口语义=最近 N 小时，服务名如 my-xhs-order，空=全部）：\n"
                    + com.myxhs.ai.app.service.agent.harness.AgentHarness.OPS_TOOL_LIST
                    + "\n" + com.myxhs.ai.app.service.agent.harness.AgentHarness.PROMPT_TAIL
                    + "\n你是技术排障 Agent：聚焦服务 5xx/延迟/MQ 积压/死信/MySQL 主从/死锁/日志检索与 traceId 链路定位；"
                    + "不涉及业务指标归因（那是业务归因 Agent 的职责）。",
            OPS_TOOLS, BUDGET);

    /** 单 Agent 现状（全工具，prompt 与历史逐字一致）——对比基线 */
    public static final AgentProfile FULL = new AgentProfile("FULL",
            com.myxhs.ai.app.service.agent.harness.AgentHarness.SYSTEM_PROMPT,
            ALL_TOOLS, BUDGET);

    private static final java.util.Map<String, AgentProfile> BY_ID = java.util.Map.of(
            BUSINESS.id(), BUSINESS,
            OPS.id(), OPS,
            FULL.id(), FULL);

    /** 按 id 查找（resume 从 versionsJson 恢复画像；未知/缺失返回 null=全量语义） */
    public static AgentProfile byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    private AgentProfiles() {
    }
}
