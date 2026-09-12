package com.myxhs.ai.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HITL 审批服务（M2.0：pending/approved/rejected + always 授权 + 级联拒绝）
 * <p>状态机与三段式详见 docs/design/02；超时 fail-closed 与跨实例恢复在 M2.x 补全。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;
    private final ApprovalExecutor approvalExecutor;

    /** 创建待审批（含 raw_input 指纹） */
    public Map<String, Object> createPending(Long userId, String sessionId, String tool, String kind,
                                             Map<String, Object> rawInput, String risk, List<String> patterns) {
        try {
            String rawJson = objectMapper.writeValueAsString(rawInput);
            String hash = sha256(canonical(objectMapper.writeValueAsString(rawInput)));
            jdbcTemplate.update(
                    "INSERT INTO ai_approval(session_id, user_id, tool, kind, raw_input, raw_input_hash, patterns, risk, status) "
                            + "VALUES(?,?,?,?,?,?,?,?, 'pending')",
                    sessionId, userId, tool, kind, rawJson, hash,
                    objectMapper.writeValueAsString(patterns == null ? List.of() : patterns), risk);
            Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            auditService.record(userId, "approval.created", tool + ":" + id, rawInput, "pending, risk=" + risk);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("approvalId", id);
            result.put("status", "pending");
            result.put("risk", risk);
            result.put("rawInputHash", hash);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("创建审批失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> list(Long userId, String status, String sessionId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, session_id, user_id, tool, kind, raw_input, risk, status, "
                        + "requested_at, decided_by, decided_at, decision_reason, result "
                        + "FROM ai_approval WHERE user_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            args.add(sessionId);
        }
        sql.append(" ORDER BY id DESC LIMIT 100");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 审批回复：once/always → 执行并挂 settlement；reject → 级联拒绝同会话 pending */
    public Map<String, Object> reply(Long userId, Long id, String reply, String message) {
        if (!List.of("once", "always", "reject").contains(reply)) {
            throw new IllegalArgumentException("reply 必须是 once/always/reject");
        }
        Map<String, Object> row = load(id);
        long owner = ((Number) row.get("user_id")).longValue();
        if (userId != 0L && owner != userId) {
            throw new IllegalArgumentException("无权操作该审批");
        }
        if (!"pending".equals(row.get("status"))) {
            throw new IllegalStateException("审批已被处理: " + row.get("status"));
        }
        String newStatus = "reject".equals(reply) ? "rejected" : "approved";
        int updated = jdbcTemplate.update(
                "UPDATE ai_approval SET status=?, decided_by=?, decided_at=CURRENT_TIMESTAMP(3), decision_reason=? "
                        + "WHERE id=? AND status='pending'",
                newStatus, userId, message, id);
        if (updated == 0) {
            throw new IllegalStateException("审批状态已被其他操作变更，请刷新");
        }

        String sessionId = String.valueOf(row.get("session_id"));
        String tool = String.valueOf(row.get("tool"));
        if ("always".equals(reply)) {
            String pattern = grantPattern(sessionId);
            jdbcTemplate.update(
                    "INSERT INTO ai_session_grant(user_id, session_id, permission, pattern) VALUES(?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE revoked_at = NULL",
                    owner, sessionId, tool, pattern);
        }
        if ("reject".equals(reply)) {
            jdbcTemplate.update(
                    "UPDATE ai_approval SET status='rejected', decided_by=?, decided_at=CURRENT_TIMESTAMP(3), "
                            + "decision_reason='级联拒绝(同会话)' WHERE session_id=? AND status='pending' AND id<>?",
                    userId, sessionId, id);
        }

        Map<String, Object> result = null;
        if ("approved".equals(newStatus)) {
            Map<String, Object> rawInput = parseRawInput(row);
            result = approvalExecutor.execute(tool, rawInput, userId);
            try {
                jdbcTemplate.update("UPDATE ai_approval SET result=? WHERE id=?",
                        objectMapper.writeValueAsString(result), id);
            } catch (Exception e) {
                log.warn("[审批] 结果回写失败 id={}, err={}", id, e.getMessage());
            }
        }
        auditService.record(userId, "approval." + reply, tool + ":" + id, Map.of("sessionId", sessionId),
                "status=" + newStatus);

        Map<String, Object> response = load(id);
        response.put("execution", result);
        return response;
    }

    public boolean hasGrant(Long userId, String sessionId, String permission) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_session_grant WHERE user_id=? AND session_id=? AND permission=? AND revoked_at IS NULL",
                Integer.class, userId, sessionId, permission);
        return count != null && count > 0;
    }

    // ---------- internal ----------

    private Map<String, Object> load(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM ai_approval WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("审批不存在: " + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> parseRawInput(Map<String, Object> row) {
        try {
            Object raw = row.get("raw_input");
            if (raw == null) {
                return Map.of();
            }
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("审批参数解析失败: " + e.getMessage(), e);
        }
    }

    private String grantPattern(String sessionId) {
        return "session:" + sessionId;
    }

    private String canonical(String json) throws Exception {
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
        return objectMapper.writeValueAsString(new java.util.TreeMap<>(map));
    }

    private String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
