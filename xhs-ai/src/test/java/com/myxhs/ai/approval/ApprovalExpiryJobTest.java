package com.myxhs.ai.approval;

import com.myxhs.ai.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批超时 fail-closed（M2.x）
 */
@ExtendWith(MockitoExtension.class)
class ApprovalExpiryJobTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private AuditService auditService;
    @Mock
    private ApprovalEventBus approvalEventBus;
    @InjectMocks
    private ApprovalExpiryJob job;

    @Test
    void expirePendingMarksExpiredAndPublishes() throws Exception {
        ReflectionTestUtils.setField(job, "timeoutMinutes", 10);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "session_id", "s1", "user_id", 1L, "tool", "dlq.redeliver")));
        when(jdbcTemplate.update(contains("status='expired'"), any(Object[].class))).thenReturn(1);

        job.expirePending();

        verify(auditService).record(any(), contains("approval.expired"), contains("7"), any(), contains("expired"));
        verify(approvalEventBus).publish(contains("\"status\":\"expired\""));
    }
}
