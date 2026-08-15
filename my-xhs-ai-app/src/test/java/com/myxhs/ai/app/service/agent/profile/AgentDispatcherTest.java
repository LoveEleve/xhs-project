package com.myxhs.ai.app.service.agent.profile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13 领域分派单测：信号矩阵（业务/排障/冲突/无信号/空）。
 */
class AgentDispatcherTest {

    private final AgentDispatcher dispatcher = new AgentDispatcher();

    @Test
    void 业务信号_分派BUSINESS() {
        assertEquals(AgentProfiles.BUSINESS.id(), dispatcher.dispatch("为什么订单量下降了？").id());
        assertEquals(AgentProfiles.BUSINESS.id(), dispatcher.dispatch("支付成功率为什么异常").id());
        assertEquals(AgentProfiles.BUSINESS.id(), dispatcher.dispatch("内容互动量波动原因").id());
        assertEquals(AgentProfiles.BUSINESS.id(), dispatcher.dispatch("分析一下漏斗转化率").id());
    }

    @Test
    void 排障信号_分派OPS() {
        assertEquals(AgentProfiles.OPS.id(), dispatcher.dispatch("为什么最近有 5xx 错误？").id());
        assertEquals(AgentProfiles.OPS.id(), dispatcher.dispatch("MQ 消费积压了").id());
        assertEquals(AgentProfiles.OPS.id(), dispatcher.dispatch("MySQL 主从延迟排查").id());
        assertEquals(AgentProfiles.OPS.id(), dispatcher.dispatch("帮我查一下日志").id());
        assertEquals(AgentProfiles.OPS.id(), dispatcher.dispatch("帮我重投死信消息").id());
        assertEquals(AgentProfiles.OPS.id(), dispatcher.dispatch("traceId 定位").id());
    }

    @Test
    void 无信号或冲突_默认BUSINESS() {
        assertEquals(AgentProfiles.BUSINESS.id(), dispatcher.dispatch("今天天气怎么样").id());
        assertEquals(AgentProfiles.BUSINESS.id(), dispatcher.dispatch("随便聊聊").id());
        assertEquals(AgentProfiles.BUSINESS.id(), dispatcher.dispatch("").id());
        assertEquals(AgentProfiles.BUSINESS.id(), dispatcher.dispatch(null).id());
    }

    @Test
    void 画像工具子集() {
        // 业务画像：业务工具在子集、观测工具不在
        assertTrue(AgentProfiles.BUSINESS.allows(com.myxhs.ai.tools.AgentToolNames.QUERY_ORDER_VOLUME));
        assertTrue(!AgentProfiles.BUSINESS.allows(com.myxhs.ai.tools.AgentToolNames.HTTP_ERRORS));
        assertTrue(!AgentProfiles.BUSINESS.allows(com.myxhs.ai.tools.AgentToolNames.LOG_SEARCH));
        // 排障画像：观测/日志/运维动作在子集、业务工具不在
        assertTrue(AgentProfiles.OPS.allows(com.myxhs.ai.tools.AgentToolNames.HTTP_ERRORS));
        assertTrue(AgentProfiles.OPS.allows(com.myxhs.ai.tools.AgentToolNames.LOG_SEARCH));
        assertTrue(AgentProfiles.OPS.allows(com.myxhs.ai.tools.AgentToolNames.L3_DLQ_REDELIVER));
        assertTrue(!AgentProfiles.OPS.allows(com.myxhs.ai.tools.AgentToolNames.QUERY_ORDER_VOLUME));
        // FULL：全量
        assertTrue(AgentProfiles.FULL.allows(com.myxhs.ai.tools.AgentToolNames.QUERY_ORDER_VOLUME));
        assertTrue(AgentProfiles.FULL.allows(com.myxhs.ai.tools.AgentToolNames.HTTP_ERRORS));
    }
}
