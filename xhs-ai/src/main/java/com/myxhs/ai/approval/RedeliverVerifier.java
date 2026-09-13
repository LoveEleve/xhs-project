package com.myxhs.ai.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.mq.DlqAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 重投延迟核验器（RV10 R3：替代 3s 快照，窗口内检测"再次进入 DLQ"）
 * <p>判定：窗口内按 DLQ_ORIGIN_MESSAGE_ID = 重投新 msgId 匹配；命中→消费失败；超窗未命中→视为成功。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedeliverVerifier {

    private static final long VERIFY_WINDOW_MS = 10 * 60 * 1000L;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DlqAdminService dlqAdminService;
    private final AuditService auditService;

    @Scheduled(initialDelayString = "${ai.verification.initial-delay-ms:60000}",
            fixedDelayString = "${ai.verification.interval-ms:30000}")
    public void verifyPending() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, result FROM ai_approval WHERE tool='dlq.redeliver' AND status='approved' "
                        + "AND result LIKE '%pending_verification%' ORDER BY id DESC LIMIT 20");
        if (rows.isEmpty()) {
            return;
        }
        for (Map<String, Object> row : rows) {
            try {
                verifyOne(row);
            } catch (Exception e) {
                log.warn("[核验] 审批 id={} 核验失败: {}", row.get("id"), e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyOne(Map<String, Object> row) throws Exception {
        Long id = ((Number) row.get("id")).longValue();
        Object resultJson = row.get("result");
        if (resultJson == null) {
            return;
        }
        Map<String, Object> payload = objectMapper.readValue(String.valueOf(resultJson),
                new TypeReference<Map<String, Object>>() {
                });
        Map<String, Object> execution = asMap(payload.get("execution"));
        Map<String, Object> settlement = execution == null ? null : asMap(execution.get("settlement"));
        if (settlement == null || !"pending_verification".equals(settlement.get("status"))) {
            return;
        }
        String group = String.valueOf(settlement.get("group"));
        String newMsgId = String.valueOf(settlement.get("newMsgId"));
        Object sentAtObj = settlement.get("sentAt");
        if (sentAtObj == null) {
            return;
        }
        long sentAt = ((Number) sentAtObj).longValue();

        boolean reentered = dlqAdminService.findByOriginMsgId(group, newMsgId).isPresent();
        if (reentered) {
            settlement.put("status", "reentered_dlq");
            settlement.put("verdict", "重投消息消费失败，已再次进入 DLQ，请排查消费者");
        } else if (System.currentTimeMillis() - sentAt > VERIFY_WINDOW_MS) {
            // 消费位点核验：位点推进视为已消费
            Long before = settlement.get("consumerOffsetBefore") == null ? null
                    : ((Number) settlement.get("consumerOffsetBefore")).longValue();
            Long after = null;
            try {
                after = dlqAdminService.consumerOffsetSum(group);
            } catch (Exception e) {
                log.warn("[核验] 消费位点读取失败 group={}: {}", group, e.getMessage());
            }
            settlement.put("consumerOffsetAfter", after);
            if (before != null && after != null && after > before) {
                settlement.put("status", "verified_consumed");
                settlement.put("verdict", "消费位点由 " + before + " 推进到 " + after + "，消息已被消费");
            } else {
                settlement.put("status", "verified_no_reentry");
                settlement.put("verdict", "窗口内未再入 DLQ；位点未观察到推进（消费慢或消费者未订阅），建议人工复核");
            }
        } else {
            return;
        }
        settlement.put("verifiedAt", System.currentTimeMillis());
        jdbcTemplate.update("UPDATE ai_approval SET result=? WHERE id=?",
                objectMapper.writeValueAsString(payload), id);
        auditService.record(null, "dlq.redeliver.verification." + settlement.get("status"),
                "approval=" + id, settlement, String.valueOf(settlement.get("verdict")));
        log.info("[核验] 审批 id={} 结论={} verdict={}", id, settlement.get("status"), settlement.get("verdict"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
