package com.myxhs.ai.app.service.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JdbcConversationStore（M10）：my_xhs_ai 库 ai_conversation/ai_message 两表（写账号 myxhs_ai_rw）。
 * 落库失败不阻断主流程（诊断执行与会话持久化解耦），但错误级别记录（可追溯性损失需可见）。
 */
public class JdbcConversationStore implements ConversationStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcConversationStore.class);

    private final JdbcTemplate jdbc;

    public JdbcConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createConversation(String convId, String userId, String title) {
        jdbc.update("INSERT INTO ai_conversation (conv_id, user_id, title, summary, message_count,"
                        + " created_at, last_activity_at) VALUES (?,?,?,NULL,0,?,?)",
                convId, userId, title, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<Conversation> load(String convId) {
        List<Conversation> rows = jdbc.query("SELECT * FROM ai_conversation WHERE conv_id=?",
                (rs, i) -> toConversation(rs), convId);
        return rows.stream().findFirst();
    }

    /** 最近 limit 条消息（升序：旧→新）。
     *  先逆序取最近 N 条再正序——直接 ORDER BY id ASC LIMIT 会取到最早的 N 条（P0-1 修复）。 */
    @Override
    public List<Message> loadMessages(String convId, int limit) {
        return jdbc.query("SELECT * FROM (SELECT * FROM ai_message WHERE conv_id=?"
                        + " ORDER BY id DESC LIMIT ?) t ORDER BY id ASC",
                (rs, i) -> toMessage(rs), convId, limit);
    }

    @Override
    public void appendMessage(String convId, String role, String content, String runId, String refsJson) {
        try {
            jdbc.update("INSERT INTO ai_message (conv_id, role, content, run_id, refs_json, created_at)"
                    + " VALUES (?,?,?,?,?,?)",
                    convId, role, content, runId, refsJson, Timestamp.from(Instant.now()));
            jdbc.update("UPDATE ai_conversation SET message_count=message_count+1, last_activity_at=?"
                    + " WHERE conv_id=?", Timestamp.from(Instant.now()), convId);
        } catch (Exception e) {
            log.error("[convstore] 消息落库失败 conv={} role={} err={}", convId, role, e.getMessage());
        }
    }

    @Override
    public void updateSummary(String convId, String summary) {
        try {
            jdbc.update("UPDATE ai_conversation SET summary=?, last_activity_at=? WHERE conv_id=?",
                    summary, Timestamp.from(Instant.now()), convId);
        } catch (Exception e) {
            log.error("[convstore] 摘要更新失败 conv={} err={}", convId, e.getMessage());
        }
    }

    @Override
    public void touch(String convId) {
        try {
            jdbc.update("UPDATE ai_conversation SET last_activity_at=? WHERE conv_id=?",
                    Timestamp.from(Instant.now()), convId);
        } catch (Exception e) {
            log.warn("[convstore] 心跳更新失败 conv={} err={}", convId, e.getMessage());
        }
    }

    @Override
    public List<Conversation> listByUser(String userId, int limit) {
        return jdbc.query("SELECT * FROM ai_conversation WHERE user_id=? ORDER BY last_activity_at DESC LIMIT ?",
                (rs, i) -> toConversation(rs), userId, limit);
    }

    private Conversation toConversation(ResultSet rs) throws SQLException {
        return new Conversation(
                rs.getString("conv_id"), rs.getString("user_id"), rs.getString("title"),
                rs.getString("summary"), rs.getInt("message_count"),
                ts(rs.getTimestamp("created_at")), ts(rs.getTimestamp("last_activity_at")));
    }

    private Message toMessage(ResultSet rs) throws SQLException {
        return new Message(
                rs.getLong("id"), rs.getString("conv_id"), rs.getString("role"),
                rs.getString("content"), rs.getString("run_id"), rs.getString("refs_json"),
                ts(rs.getTimestamp("created_at")));
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
