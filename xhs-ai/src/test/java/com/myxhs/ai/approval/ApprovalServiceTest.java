package com.myxhs.ai.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApprovalService：状态机 / 主键安全 / 指纹去重 / 失败重试（RV10）
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private AuditService auditService;
    @Mock
    private ApprovalExecutor approvalExecutor;
    @Mock
    private ApprovalEventBus approvalEventBus;

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalService(jdbcTemplate, transactionTemplate, new ObjectMapper(), auditService, approvalExecutor, approvalEventBus);
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        lenient().when(jdbcTemplate.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(invocation -> {
                    KeyHolder keyHolder = invocation.getArgument(1);
                    if (keyHolder instanceof GeneratedKeyHolder generated) {
                        generated.getKeyList().add(Map.of("GENERATED_KEY", 42L));
                    }
                    return 1;
                });
        lenient().doAnswer(invocation -> {
            Consumer<?> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
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
        assertEquals(r1.get("rawInputHash"), r2.get("rawInputHash"));
    }

    @Test
    void createPendingFingerprintDiffersWhenParamsChange() {
        Map<String, Object> a = service.createPending(1L, "s1", "dlq.redeliver", "mq", Map.of("msgId", "A"), "ask", List.of());
        Map<String, Object> b = service.createPending(1L, "s1", "dlq.redeliver", "mq", Map.of("msgId", "B"), "ask", List.of());
        assertNotEquals(a.get("rawInputHash"), b.get("rawInputHash"));
    }

    @Test
    void createPendingReusesExistingPendingWithSameFingerprint() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "risk", "ask")));
        Map<String, Object> result = service.createPending(1L, "s1", "dlq.redeliver", "mq",
                Map.of("msgId", "A"), "ask", List.of());
        assertEquals(7L, result.get("approvalId"));
        assertEquals(true, result.get("reused"));
        verify(jdbcTemplate, never()).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    @Test
    void replyRejectsWhenAlreadyDecided() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row("approved", null)));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reply(1L, 42L, "once", null));
        assertEquals("审批已被处理: approved", e.getMessage());
    }

    @Test
    void replyOnceExecutesApprovedTool() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("pending", null)), List.of(row("approved", null)));
        Map<String, Object> result = service.reply(1L, 42L, "once", "ok");
        assertEquals("approved", result.get("status"));
        verify(approvalExecutor).execute(eq("dlq.redeliver"), any(Map.class), eq(1L), any());
    }

    @Test
    void replyRejectCascadesPendingInSameSession() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row("pending", null)));
        service.reply(1L, 42L, "reject", "no");
        verify(jdbcTemplate).update(contains("级联拒绝"), any(), anyString(), any());
    }

    @Test
    void replyCasConflictThrows() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(row("pending", null)));
        when(jdbcTemplate.update(contains("status='pending'"), any(Object[].class))).thenReturn(0);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reply(1L, 42L, "once", null));
        assertEquals("审批状态已被其他操作变更，请刷新", e.getMessage());
    }

    @Test
    void retryRejectsAlreadyExecuted() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("approved", "{\"executionStatus\":\"executed\"}")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.retryExecution(1L, 42L));
        assertEquals("该审批已执行成功，禁止重复执行", e.getMessage());
    }

    @Test
    void retryRejectsTamperedRawInput() {
        Map<String, Object> tampered = row("approved", "{\"executionStatus\":\"failed\"}");
        tampered.put("raw_input", "{\"group\":\"g\",\"msgId\":\"TAMPERED\"}");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(tampered));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.retryExecution(1L, 42L));
        assertEquals("审批原文指纹不匹配，拒绝执行（疑似数据被篡改）", e.getMessage());
    }

    @Test
    void retryRunsAgainWhenPreviousExecutionFailed() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(row("approved", "{\"executionStatus\":\"failed\"}")));
        Map<String, Object> result = service.retryExecution(1L, 42L);
        assertEquals("executed", result.get("executionStatus"));
        verify(approvalExecutor).execute(eq("dlq.redeliver"), any(Map.class), eq(1L), any());
    }

    private Map<String, Object> row(String status, String result) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 42L);
        row.put("session_id", "s1");
        row.put("user_id", 1L);
        row.put("tool", "dlq.redeliver");
        row.put("risk", "ask");
        row.put("status", status);
        row.put("raw_input", RAW_INPUT);
        try {
            row.put("raw_input_hash", ApprovalFingerprint.of(RAW_INPUT, new ObjectMapper()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        row.put("result", result);
        return row;
    }

    private static final String RAW_INPUT = "{\"group\":\"g\",\"msgId\":\"m\"}";
}
