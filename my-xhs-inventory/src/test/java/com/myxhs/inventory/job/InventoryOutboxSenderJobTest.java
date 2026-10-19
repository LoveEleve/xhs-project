package com.myxhs.inventory.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxhs.inventory.mapper.InventoryMapper;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryOutboxSenderJobTest {

    @Mock private InventoryMapper inventoryMapper;
    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock lock;

    private InventoryOutboxSenderJob job;

    @BeforeEach
    void setUp() throws InterruptedException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        job = new InventoryOutboxSenderJob(inventoryMapper, rocketMQTemplate, objectMapper, redissonClient,
                mock(com.myxhs.common.metrics.BusinessMetrics.class));
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 4, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void shouldMarkOutboxByBusinessKey() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 99L);
        row.put("order_id", 123L);
        row.put("sku_id", 456L);
        row.put("action", "CONFIRM");
        row.put("quantity", 2);
        row.put("event_time", 1729000000000L); // C-008 R7：原始 eventTime（补发必须精确复用）
        row.put("created_at", Timestamp.valueOf(LocalDateTime.now()));
        when(inventoryMapper.selectPendingOutbox(any(LocalDateTime.class), eq(200))).thenReturn(List.of(row));

        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:CONFIRM"),
                org.mockito.ArgumentMatchers.<org.springframework.messaging.Message<?>>any(), eq(3000L)))
                .thenReturn(sendResult);

        job.sendOutboxEvents();

        // C-008 F2：按 (order,sku,action) 匹配（消除 eventId 失配 0 行静默失效）
        verify(inventoryMapper).markOutboxSent(123L, 456L, "CONFIRM");
        // C-008 R7：补发 payload 的 eventTime 精确复用行内原始值（事件级幂等键一致）
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<org.springframework.messaging.Message<String>> captor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.messaging.Message.class);
        verify(rocketMQTemplate).syncSend(eq("INVENTORY_TOPIC:CONFIRM"), captor.capture(), eq(3000L));
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPayload())
                .contains("\"eventTime\":1729000000000");
    }
}
