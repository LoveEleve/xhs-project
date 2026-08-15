package com.myxhs.ai.app.service.conversation;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话服务（M10）：多轮上下文注入 + 会话摘要记忆。
 * 设计要点（design-m10-conversation.md §4 修订版）：
 *  - 历史注入只含 user/assistant 结论消息，无工具原文（P0-1 方案 A）：
 *    模型无法引用上轮 ev_xxx，想用上轮数据必须重新调用工具（数据可能已变化）
 *  - 注入优先级：记忆摘要 < 会话历史 < 当前问题
 *  - 职责分离（P0-2）：ai_message 会话专用；checkpoint 不反向读会话表
 *  - V1 规则摘要（零成本）：标题 + finalAnswer 结论段，覆盖式更新
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    /** 历史注入上限（最近 N 条 user/assistant；诊断短会话 20 轮足够） */
    public static final int HISTORY_LIMIT = 20;
    /** 摘要长度上限（字符） */
    public static final int SUMMARY_MAX_LEN = 2000;
    /** 首问标题截断长度 */
    public static final int TITLE_MAX_LEN = 40;

    private final ConversationStore store;

    public ConversationService(ConversationStore store) {
        this.store = store;
    }

    /** 生成 convId（32 位 hex，与 runId 风格一致） */
    public static String newConvId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 32);
    }

    /** 确保会话存在；不存在则新建（title=首问截断） */
    public ConversationStore.Conversation ensureConversation(String convId, String userId, String firstQuery) {
        return store.load(convId).orElseGet(() -> {
            store.createConversation(convId, userId, titleOf(firstQuery));
            log.info("[conv] 新建会话 conv={} user={} title={}", convId, userId, firstQuery);
            return store.load(convId).orElseThrow();
        });
    }

    /**
     * 组装多轮上下文：摘要 SystemMessage（若有）+ 最近 HISTORY_LIMIT 条 user/assistant 结论消息。
     * 无工具原文（跨轮证据校验：旧 ev_xxx 对模型不可见）。
     */
    public List<ChatMessage> buildContext(String convId) {
        List<ChatMessage> msgs = new ArrayList<>();
        if (convId == null) {
            return msgs;
        }
        var conv = store.load(convId);
        if (conv.isPresent() && conv.get().summary() != null && !conv.get().summary().isBlank()) {
            msgs.add(SystemMessage.from("历史会话摘要（仅供参考，数据以当前工具查询为准）：\n"
                    + conv.get().summary()));
        }
        if (conv.isPresent()) {
            for (ConversationStore.Message m : store.loadMessages(convId, HISTORY_LIMIT)) {
                msgs.add("user".equals(m.role())
                        ? UserMessage.from(m.content())
                        : dev.langchain4j.data.message.AiMessage.from(m.content()));
            }
        }
        return msgs;
    }

    /** 用户提问落库（提交即写，runId 关联可追溯） */
    public void appendUserMessage(String convId, String runId, String query) {
        if (convId == null) {
            return;
        }
        store.appendMessage(convId, "user", query, runId, null);
    }

    /** assistant 结论落库（终态后写：结论 + 证据 ID 列表）。
     *  净化 PARTIAL 摘要的"已收集证据"段——其含工具结果原文，注入历史会让模型抄旧数据
     *  而不重新调用工具（违反 P0-1：跨轮数据必须重新查询，数据可能已变化）。 */
    public void appendAssistantMessage(String convId, String runId, String answer, List<String> evidenceRefs) {
        if (convId == null) {
            return;
        }
        String refsJson = evidenceRefs == null || evidenceRefs.isEmpty() ? null
                : java.util.Map.of("refs", evidenceRefs).toString();
        store.appendMessage(convId, "assistant", cleanForConversation(answer), runId, refsJson);
    }

    /** 剥掉 PARTIAL 确定性摘要的工具结果原文段（"已收集证据：…"至结尾）；COMPLETED 答案无此段，原样保留 */
    static String cleanForConversation(String answer) {
        if (answer == null) {
            return null;
        }
        String cleaned = answer.replaceAll("(?s)\\n已收集证据：.*", "");
        return cleaned.isBlank() ? answer : cleaned;
    }

    /** 会话摘要更新（V1 规则抽取：首问标题 + 当前结论段，覆盖式）。
     *  首问取会话持久化 title（首次提问），不随后续轮次变化。 */
    public void updateSummary(String convId, String finalAnswer) {
        if (convId == null) {
            return;
        }
        String title = store.load(convId)
                .map(ConversationStore.Conversation::title).orElse(null);
        String summary = summarize(title, finalAnswer);
        store.updateSummary(convId, summary);
    }

    public Optional<ConversationStore.Conversation> get(String convId) {
        return store.load(convId);
    }

    public List<ConversationStore.Message> messages(String convId, int limit) {
        return store.loadMessages(convId, limit);
    }

    public List<ConversationStore.Conversation> list(String userId, int limit) {
        return store.listByUser(userId, limit);
    }

    static String titleOf(String query) {
        String t = query == null ? "" : query.strip().replaceAll("\\s+", " ");
        return t.length() <= TITLE_MAX_LEN ? t : t.substring(0, TITLE_MAX_LEN) + "…";
    }

    /** 规则摘要：首问 + finalAnswer 结论段（"证据链：" 之前），截断 2000 字符 */
    static String summarize(String firstQuery, String finalAnswer) {
        String conclusion = "";
        if (finalAnswer != null) {
            int idx = finalAnswer.indexOf("证据链：");
            conclusion = idx > 0 ? finalAnswer.substring(0, idx) : finalAnswer;
        }
        String s = "首问：" + titleOf(firstQuery) + "\n结论：" + conclusion.strip();
        return s.length() <= SUMMARY_MAX_LEN ? s : s.substring(0, SUMMARY_MAX_LEN) + "…";
    }
}
