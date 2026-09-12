package com.myxhs.ai.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApprovalService：审批状态机 + raw_input 指纹（canonical 哈希）
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private AuditService auditService;
    @Mock
    private ApprovalExecutor approvalExecutor;

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalService(jdbcTemplate, new ObjectMapper(), auditService, approvalExecutor);
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        lenient().when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(42L);
    }

    @Test
    void createPendingReturnsIdAndStableFingerprintRegardlessOfKeyOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("group", "cart-event-sink-group");
        first.put("msgId", "M-1");

        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("msgId", "M-1");
        reversed.put("group", "cart-event-sink-group");

        Map<String, Object> r1 = service.createPending(1L, "s1", "dlq.redeliver", "mq.redeliver", first, "ask", List.of("dlq:x"));
        Map<String, Object> r2 = service.createPending(1L, "s1", "dlq.redeliver", "mq.redeliver", reversed, "ask", List.of("dlq:x"));

        assertEquals(42L, r1.get("approvalId"));
        assertEquals("pending", r1.get("status"));
        assertEquals("ask", r1.get("risk"));
        assertEquals(r1.get("rawInputHash"), r2.get("rawInputHash"));
    }

    @Test
    void createPendingFingerprintDiffersWhenParamsChange() {
        Map<String, Object> a = service.createPending(1L, "s1", "dlq.redeliver", "mq", Map.of("msgId", "A"), "ask", List.of());
        Map<String, Object> b = service.createPending(1L, "s1", "dlq.redeliver", "mq", Map.of("msgId", "B"), "ask", List.of());
        assertNotEquals(a.get("rawInputHash"), b.get("rawInputHash"));
    }

    @Test
    void replyRejectsWhenAlreadyDecided() {
        doReturn(List.of(row("approved"))).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reply(1L, 42L, "once", null));
        assertEquals("审批已被处理: approved", e.getMessage());
    }

    @Test
    void replyOnceExecutesApprovedTool() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("pending")), List.of(row("approved")));
        Map<String, Object> result = service.reply(1L, 42L, "once", "ok");
        assertEquals("approved", result.get("status"));
        verify(approvalExecutor).execute(eq("dlq.redeliver"), any(Map.class), eq(1L), any());
    }

    @Test
    void replyRejectCascadesPendingInSameSession() {
        doReturn(List.of(row("pending"))).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
        service.reply(1L, 42L, "reject", "no");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("级联拒绝"),
                any(), anyString(), any());
    }

    private Map<String, Object> row(String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 42L);
        row.put("session_id", "s1");
        row.put("user_id", 1L);
        row.put("tool", "dlq.redeliver");
        row.put("risk", "ask");
        row.put("status", status);
        row.put("raw_input", "{\"group\":\"g\",\"msgId\":\"m\"}");
        return row;
    }
}
