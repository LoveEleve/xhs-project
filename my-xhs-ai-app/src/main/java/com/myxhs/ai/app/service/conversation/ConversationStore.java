package com.myxhs.ai.app.service.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话 Store（M10 Conversation + Session Memory）：ai_conversation/ai_message 两表。
 * 与 Run Store 职责分离（P0-2）：ai_message 会话专用（注入/展示/追溯），
 * ai_step.messages_snapshot 恢复专用（执行态）——单向流：run 终态 → 写 ai_message。
 */
public interface ConversationStore {

    record Conversation(String convId, String userId, String title, String summary,
                        int messageCount, Instant createdAt, Instant lastActivityAt) {
    }

    record Message(long id, String convId, String role, String content,
                   String runId, String refsJson, Instant createdAt) {
    }

    void createConversation(String convId, String userId, String title);

    Optional<Conversation> load(String convId);

    /** 最近 limit 条消息（升序：旧→新） */
    List<Message> loadMessages(String convId, int limit);

    /** 追加会话消息（user 提问 / assistant 结论，无工具原文） */
    void appendMessage(String convId, String role, String content, String runId, String refsJson);

    void updateSummary(String convId, String summary);

    void touch(String convId);

    /** 会话列表（按 last_activity_at 倒序） */
    List<Conversation> listByUser(String userId, int limit);
}
