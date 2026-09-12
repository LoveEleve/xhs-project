package com.myxhs.ai.session;

import com.myxhs.ai.web.TraceIdFilter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 会话与消息持久化（M2.0，JdbcTemplate 直写 ai_session/ai_message）
 */
@Repository
@RequiredArgsConstructor
public class SessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public void ensureSession(Long userId, String sessionId, String title) {
        jdbcTemplate.update(
                "INSERT INTO ai_session(user_id, session_id, title) VALUES(?,?,?) "
                        + "ON DUPLICATE KEY UPDATE updated_at = CURRENT_TIMESTAMP(3)",
                userId, sessionId, abbreviate(title, 200));
    }

    public void appendMessage(String sessionId, Long userId, String role, String content,
                              String toolCallsJson, int tokensIn, int tokensOut) {
        jdbcTemplate.update(
                "INSERT INTO ai_message(session_id, user_id, role, content, trace_id, tool_calls, tokens_in, tokens_out) "
                        + "VALUES(?,?,?,?,?,?,?,?)",
                sessionId, userId, role, content, MDC.get(TraceIdFilter.MDC_KEY),
                toolCallsJson, tokensIn, tokensOut);
    }

    public List<Map<String, Object>> listMessages(String sessionId, Long userId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT id, role, content, trace_id, tokens_in, tokens_out, created_at "
                        + "FROM ai_message WHERE session_id = ? AND user_id = ? ORDER BY id DESC LIMIT ?",
                sessionId, userId, Math.min(Math.max(limit, 1), 200));
    }

    public List<Map<String, Object>> listSessions(Long userId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT session_id, title, created_at, updated_at FROM ai_session "
                        + "WHERE user_id = ? ORDER BY updated_at DESC LIMIT ?",
                userId, Math.min(Math.max(limit, 1), 100));
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
