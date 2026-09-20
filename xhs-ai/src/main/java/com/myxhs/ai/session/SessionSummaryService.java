package com.myxhs.ai.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话滚动摘要（上下文工程-压缩）：把"保留窗口之外"的历史压缩成要点，供状态重建/长会话使用。
 */
@Slf4j
@Service
public class SessionSummaryService {

    private final JdbcTemplate jdbcTemplate;
    private final Model summaryModel;

    @Value("${myxhs.summary.keep-recent:20}")
    private int keepRecent;

    @Value("${myxhs.summary.min-batch:20}")
    private int minBatch;

    @Value("${myxhs.summary.max-input-chars:12000}")
    private int maxInputChars;

    public SessionSummaryService(JdbcTemplate jdbcTemplate,
                                 @Qualifier("lightChatModel") Model summaryModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.summaryModel = summaryModel;
    }

    /** @return 摘要结果（skipped=true 表示批次不足未生成） */
    public Map<String, Object> summarize(String sessionId, String userId, boolean force) {
        List<Map<String, Object>> desc = jdbcTemplate.queryForList(
                "SELECT id, role, content FROM ai_message WHERE session_id=? AND user_id=? ORDER BY id DESC LIMIT 400",
                sessionId, userId == null ? 0L : Long.parseLong(userId));
        List<Map<String, Object>> asc = new ArrayList<>(desc);
        java.util.Collections.reverse(asc);
        long upto = currentUpto(sessionId);
        List<Map<String, Object>> block = SummarySelector.pickBlock(asc, upto, keepRecent, force ? 2 : minBatch);
        if (block.isEmpty()) {
            return Map.of("skipped", true, "reason", "可摘要批次不足", "uptoMessageId", upto);
        }
        String input = render(block);
        String summary;
        try {
            summary = callModel(input);
        } catch (Exception e) {
            log.warn("[会话摘要] 模型调用失败 session={}: {}", sessionId, e.getMessage());
            return Map.of("skipped", true, "reason", "模型调用失败: " + e.getMessage());
        }
        long lastId = ((Number) block.get(block.size() - 1).get("id")).longValue();
        jdbcTemplate.update(
                "INSERT INTO ai_session_summary(session_id, user_id, summary, upto_message_id) VALUES(?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE summary=VALUES(summary), upto_message_id=VALUES(upto_message_id)",
                sessionId, Long.parseLong(userId), summary, lastId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skipped", false);
        result.put("summarized", block.size());
        result.put("uptoMessageId", lastId);
        result.put("summary", summary);
        return result;
    }

    /** 供状态重建注入（无摘要返回 null） */
    public String summaryFor(String sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT summary FROM ai_session_summary WHERE session_id=?", sessionId);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("summary"));
    }

    private long currentUpto(String sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT upto_message_id FROM ai_session_summary WHERE session_id=?", sessionId);
        return rows.isEmpty() ? 0L : ((Number) rows.get(0).get("upto_message_id")).longValue();
    }

    private String render(List<Map<String, Object>> block) {
        StringBuilder sb = new StringBuilder("请把以下运维排障对话压缩成不超过 300 字的要点，保留：关键结论、数字/证据、待办与未决项。\n\n");
        int chars = 0;
        for (Map<String, Object> row : block) {
            String role = "assistant".equals(String.valueOf(row.get("role"))) ? "助手" : "用户";
            String content = String.valueOf(row.get("content"));
            String line = role + ": " + content.replace('\n', ' ') + "\n";
            if (chars + line.length() > maxInputChars) {
                break;
            }
            sb.append(line);
            chars += line.length();
        }
        return sb.toString();
    }

    private String callModel(String prompt) {
        List<Msg> messages = List.of(new UserMessage(prompt));
        List<ChatResponse> responses = summaryModel.stream(messages, List.of(),
                        GenerateOptions.builder().temperature(0.2)
                        // 2026-09-20 修复：800 对 reasoning 模型不够（思考吃满后 content 为空，实测“摘要模型返回空”）
                        .maxTokens(4096).build())
                .collectList().block(Duration.ofSeconds(180)); // 2026-09-20 修复：60s 对 reasoning 模型在负载下不够（实测超时）
        StringBuilder sb = new StringBuilder();
        if (responses != null) {
            for (ChatResponse response : responses) {
                if (response.getContent() == null) {
                    continue;
                }
                for (ContentBlock block : response.getContent()) {
                    if (block instanceof TextBlock text && text.getText() != null) {
                        sb.append(text.getText());
                    }
                }
            }
        }
        if (sb.isEmpty()) {
            throw new IllegalStateException("摘要模型返回空（推理可能被截断，maxTokens=4096 仍空则需换非推理模型）");
        }
        return sb.toString();
    }
}
