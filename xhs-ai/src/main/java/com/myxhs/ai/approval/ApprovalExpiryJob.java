package com.myxhs.ai.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批超时 fail-closed（D02：无应答超时→expired，不悬挂）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalExpiryJob {

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;
    private final ApprovalEventBus approvalEventBus;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.approval.timeout-minutes:10}")
    private int timeoutMinutes;

    @Scheduled(initialDelayString = "${ai.approval.expiry-initial-delay-ms:30000}",
            fixedDelayString = "${ai.approval.expiry-interval-ms:30000}")
    public void expirePending() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, session_id, user_id, tool FROM ai_approval "
                        + "WHERE status='pending' AND requested_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL ? MINUTE)",
                timeoutMinutes);
        if (rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            try {
                int updated = jdbcTemplate.update(
                        "UPDATE ai_approval SET status='expired', decided_at=CURRENT_TIMESTAMP(3), decision_reason=? "
                                + "WHERE id=? AND status='pending'",
                        "超时未处理（fail-closed，超时阈值 " + timeoutMinutes + " 分钟）", id);
                if (updated > 0) {
                    auditService.record(null, "approval.expired", row.get("tool") + ":" + id,
                            Map.of("sessionId", String.valueOf(row.get("session_id"))),
                            "expired(+" + timeoutMinutes + "min)");
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("approvalId", id);
                    event.put("status", "expired");
                    event.put("sessionId", row.get("session_id"));
                    event.put("tool", row.get("tool"));
                    approvalEventBus.publish(objectMapper.writeValueAsString(event));
                    log.warn("[审批] 超时 fail-closed: id={} tool={}", id, row.get("tool"));
                }
            } catch (Exception e) {
                log.warn("[审批] 超时处理失败 id={}: {}", id, e.getMessage());
            }
        }
    }
}
