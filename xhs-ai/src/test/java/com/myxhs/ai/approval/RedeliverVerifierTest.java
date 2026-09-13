package com.myxhs.ai.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.audit.AuditService;
import com.myxhs.ai.mq.DlqAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重投核验三分支（M2.x/RV18）：再入 DLQ / 位点已消费 / 无再入无位点证据
 */
@ExtendWith(MockitoExtension.class)
class RedeliverVerifierTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private DlqAdminService dlqAdminService;
    @Mock
    private AuditService auditService;

    private RedeliverVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new RedeliverVerifier(jdbcTemplate, new ObjectMapper(), dlqAdminService, auditService);
    }

    private String resultJson() throws Exception {
        Map<String, Object> settlement = new LinkedHashMap<>();
        settlement.put("status", "pending_verification");
        settlement.put("group", "cart-event-sink-group");
        settlement.put("newMsgId", "UNIQ-A");
        settlement.put("offsetMsgId", "OFFSET-A");
        settlement.put("originTopic", "CART_TOPIC");
        settlement.put("brokerName", "broker-a");
        settlement.put("queueId", 0);
        settlement.put("queueOffset", 100L);
        settlement.put("sentAt", System.currentTimeMillis() - 11 * 60 * 1000L);
        Map<String, Object> execution = Map.of("settlement", settlement);
        return new ObjectMapper().writeValueAsString(Map.of("execution", execution));
    }

    @Test
    void marksReenteredWhenDlqContainsOffsetMsgId() throws Exception {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(Map.of("id", 1L, "result", resultJson())));
        when(dlqAdminService.findByOriginMsgId(eq("cart-event-sink-group"), eq("OFFSET-A")))
                .thenReturn(Optional.of(Map.of("msgId", "X")));

        verifier.verifyPending();

        verify(auditService).record(any(), contains("reentered_dlq"), contains("approval=1"), any(), any());
        verify(jdbcTemplate).update(contains("UPDATE ai_approval SET result=?"), any(Object[].class));
    }

    @Test
    void marksConsumedWhenQueueOffsetAdvanced() throws Exception {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(Map.of("id", 2L, "result", resultJson())));
        when(dlqAdminService.findByOriginMsgId(anyString(), anyString())).thenReturn(Optional.empty());
        when(dlqAdminService.queueProgress("cart-event-sink-group", "CART_TOPIC", "broker-a", 0))
                .thenReturn(Map.of("found", true, "consumerOffset", 101L));

        verifier.verifyPending();

        verify(auditService).record(any(), contains("verified_consumed"), contains("approval=2"), any(), any());
    }

    @Test
    void marksNoReentryWhenQueueNotAdvanced() throws Exception {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(Map.of("id", 3L, "result", resultJson())));
        when(dlqAdminService.findByOriginMsgId(anyString(), anyString())).thenReturn(Optional.empty());
        when(dlqAdminService.queueProgress(eq("cart-event-sink-group"), anyString(), anyString(), anyInt()))
                .thenReturn(Map.of("found", true, "consumerOffset", 100L));

        verifier.verifyPending();

        verify(auditService).record(any(), contains("verified_no_reentry"), contains("approval=3"), any(), any());
    }
}
