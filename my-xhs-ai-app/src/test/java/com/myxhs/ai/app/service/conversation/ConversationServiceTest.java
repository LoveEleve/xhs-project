package com.myxhs.ai.app.service.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话 Store/Service 单测（H2，MySQL 兼容模式）：
 *  - CRUD + 消息顺序 + 摘要更新
 *  - 多轮上下文注入（摘要在前/历史结论无工具原文/20 轮截断）
 *  - 摘要规则抽取（结论段/标题/截断）
 */
class ConversationServiceTest {

    private JdbcConversationStore store;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:conv;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_conversation (
                  conv_id VARCHAR(32) PRIMARY KEY, user_id VARCHAR(64) NOT NULL DEFAULT 'anonymous',
                  title VARCHAR(200), summary TEXT, message_count INT DEFAULT 0,
                  created_at DATETIME(3), last_activity_at DATETIME(3),
                  KEY idx_user_time (user_id, last_activity_at)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ai_message (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, conv_id VARCHAR(32) NOT NULL,
                  role VARCHAR(16) NOT NULL, content MEDIUMTEXT NOT NULL, run_id VARCHAR(32),
                  refs_json TEXT, created_at DATETIME(3), KEY idx_conv (conv_id, id)
                )""");
        jdbc.update("DELETE FROM ai_message; DELETE FROM ai_conversation;");
        store = new JdbcConversationStore(jdbc);
        service = new ConversationService(store);
    }

    @Test
    void 会话CRUD与消息顺序() {
        String convId = "conv_test_1";
        service.ensureConversation(convId, "u1", "为什么订单量下降了？");
        service.appendUserMessage(convId, "run_1", "为什么订单量下降了？");
        service.appendAssistantMessage(convId, "run_1", "订单量从46降至8，漏斗断点在下单环节", List.of("ev_a", "ev_b"));

        var conv = service.get(convId).orElseThrow();
        assertEquals("u1", conv.userId());
        assertEquals("为什么订单量下降了？", conv.title());
        assertEquals(2, conv.messageCount());

        List<ConversationStore.Message> msgs = service.messages(convId, 100);
        assertEquals(2, msgs.size());
        assertEquals("user", msgs.get(0).role());
        assertEquals("assistant", msgs.get(1).role());
        assertEquals("run_1", msgs.get(1).runId());
    }

    @Test
    void 多轮上下文_摘要在前_无工具原文() {
        String convId = "conv_test_2";
        service.ensureConversation(convId, "u1", "Q1");
        service.appendUserMessage(convId, "run_1", "为什么订单量下降了？");
        service.appendAssistantMessage(convId, "run_1",
                "订单量46→8，断点在支付环节。\n\n证据链：\n[ev_a] queryOrderVolume", List.of("ev_a"));
        service.updateSummary(convId, "订单量46→8，断点在支付环节。\n\n证据链：\n[ev_a] queryOrderVolume");

        var ctx = service.buildContext(convId);
        assertEquals(3, ctx.size(), "摘要 SystemMessage + user + assistant 结论");
        assertEquals("SYSTEM", ctx.get(0).type().name(), "摘要应最先注入");
        assertTrue(text(ctx.get(0)).contains("历史会话摘要"), text(ctx.get(0)));
        // 无工具原文：历史里不得出现工具结果/ev 引用
        String all = ctx.stream().map(ConversationServiceTest::text).reduce("", String::concat);
        assertTrue(!all.contains("window=2026"), "历史注入不得含工具参数");
    }

    private static String text(dev.langchain4j.data.message.ChatMessage m) {
        return m instanceof dev.langchain4j.data.message.AiMessage a ? a.text()
                : m instanceof dev.langchain4j.data.message.UserMessage u ? u.singleText()
                : m instanceof dev.langchain4j.data.message.SystemMessage s ? s.text() : "";
    }

    @Test
    void 多轮上下文_20轮截断() {
        String convId = "conv_test_3";
        service.ensureConversation(convId, "u1", "q");
        for (int i = 0; i < 30; i++) {
            service.appendUserMessage(convId, "run_" + i, "问题" + i);
            service.appendAssistantMessage(convId, "run_" + i, "结论" + i, List.of());
        }
        var ctx = service.buildContext(convId);
        assertEquals(ConversationService.HISTORY_LIMIT, ctx.size(), "最近 20 条（10 轮），无摘要不注入");
        // P0-1 回归：60 条消息取最近 20 条（消息 40..59 = 问题20..结论29），且旧→新顺序
        assertTrue(text(ctx.get(0)).contains("问题20"), "应取最近消息（首条=问题20）: " + text(ctx.get(0)));
        assertTrue(text(ctx.get(1)).contains("结论20"), text(ctx.get(1)));
        assertTrue(text(ctx.get(19)).contains("结论29"), "末条应为最新结论: " + text(ctx.get(19)));
        assertTrue(!text(ctx.get(0)).contains("问题0"), "不得注入最早的消息");
    }

    @Test
    void 摘要规则抽取() {
        assertEquals("首问：Q1\n结论：订单量46→8",
                ConversationService.summarize("Q1", "订单量46→8\n\n证据链：\n[ev_a] queryOrderVolume"));
        // 无"证据链"标记：整段为结论
        assertTrue(ConversationService.summarize("Q1", "简单回答").contains("简单回答"));
        // 超长截断
        String longAnswer = "结论".repeat(ConversationService.SUMMARY_MAX_LEN + 100);
        assertTrue(ConversationService.summarize("Q1", longAnswer).length() <= ConversationService.SUMMARY_MAX_LEN + 1);
    }

    @Test
    void 摘要注入_为空则跳过() {
        String convId = "conv_test_4";
        service.ensureConversation(convId, "u1", "q");
        var ctx = service.buildContext(convId);
        assertEquals(0, ctx.size(), "无历史无摘要 → 空上下文（单轮行为）");
    }

    @Test
    void 摘要_首问标题不随轮次变化() {
        String convId = "conv_test_5";
        service.ensureConversation(convId, "u1", "为什么订单量下降了？");
        service.updateSummary(convId, "第二问的结论：支付成功率 0");
        var conv = service.get(convId).orElseThrow();
        assertTrue(conv.summary().contains("为什么订单量下降了？"), "首问应保留: " + conv.summary());
        assertTrue(!conv.summary().contains("那支付呢"), conv.summary());
        // 标题本身不被覆盖
        assertEquals("为什么订单量下降了？", conv.title());
    }

    @Test
    void PARTIAL答案_证据原文不进会话消息() {
        String convId = "conv_test_6";
        service.ensureConversation(convId, "u1", "q");
        String partial = "调查在 BUDGET_STEPS 时终止（未完成归因），已用步骤 15/15。\n"
                + "已收集证据：\n[ev_99ac342710ce] queryOrderVolume window=2026-08-09~2026-08-15"
                + " 结果={\"status\":\"ok\",\"value\":46}";
        service.appendUserMessage(convId, "run_1", "q");
        service.appendAssistantMessage(convId, "run_1", partial, List.of("ev_99ac342710ce"));
        var msg = service.messages(convId, 10).get(1);
        assertTrue(!msg.content().contains("已收集证据"), msg.content());
        assertTrue(!msg.content().contains("{\"status\":\"ok\",\"value\":46}"), "工具结果原文不得进入会话消息: " + msg.content());
        assertTrue(msg.content().contains("调查在 BUDGET_STEPS"), msg.content());
        // COMPLETED 答案（结论+证据链，无工具原文）原样保留
        String done = "支付成功率 0。\n\n证据链：\n[ev_a] paymentSuccessRate window=2026-08-09~2026-08-15\n反证：无";
        assertEquals(done, ConversationService.cleanForConversation(done));
    }
}
