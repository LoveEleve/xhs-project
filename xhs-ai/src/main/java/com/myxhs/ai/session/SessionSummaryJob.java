package com.myxhs.ai.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 滚动摘要任务：定期为活跃长会话生成/刷新摘要（每轮至多 N 个，避免模型费用突刺）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSummaryJob {

    private final JdbcTemplate jdbcTemplate;
    private final SessionSummaryService summaryService;

    @Value("${myxhs.summary.batch:5}")
    private int batch;

    @Value("${myxhs.summary.min-messages:50}")
    private int minMessages;

    @Scheduled(fixedDelayString = "${myxhs.summary.interval-ms:600000}", initialDelay = 120000)
    public void run() {
        List<Map<String, Object>> sessions;
        try {
            sessions = jdbcTemplate.queryForList(
                    "SELECT session_id, user_id, COUNT(*) c FROM ai_message "
                            + "WHERE created_at > NOW() - INTERVAL 1 DAY "
                            + "GROUP BY session_id, user_id HAVING c >= ? ORDER BY MAX(id) DESC LIMIT ?",
                    minMessages, batch);
        } catch (Exception e) {
            log.warn("[会话摘要] 任务扫描失败: {}", e.getMessage());
            return;
        }
        for (Map<String, Object> row : sessions) {
            String sessionId = String.valueOf(row.get("session_id"));
            try {
                Map<String, Object> result = summaryService.summarize(sessionId,
                        String.valueOf(row.get("user_id")), false);
                boolean skipped = Boolean.TRUE.equals(result.get("skipped"));
                log.info("[会话摘要] session={} -> {}", sessionId,
                        skipped ? "skipped(" + result.get("reason") + ")" : "ok, summarized=" + result.get("summarized"));
            } catch (Exception e) {
                log.warn("[会话摘要] session={} 失败: {}", sessionId, e.getMessage());
            }
        }
    }
}
