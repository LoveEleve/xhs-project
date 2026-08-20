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
        job = new InventoryOutboxSenderJob(inventoryMapper, rocketMQTemplate, objectMapper, redissonClient);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 4, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void shouldMarkOutboxByEventId() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 99L);
        row.put("order_id", 123L);
        row.put("sku_id", 456L);
        row.put("action", "CONFIRM");
        row.put("quantity", 2);
        row.put("created_at", Timestamp.valueOf(LocalDateTime.now()));
        when(inventoryMapper.selectPendingOutbox(any(LocalDateTime.class), eq(200))).thenReturn(List.of(row));

        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:CONFIRM"),
                org.mockito.ArgumentMatchers.<org.springframework.messaging.Message<?>>any(), eq(3000L)))
                .thenReturn(sendResult);

        job.sendOutboxEvents();

        verify(inventoryMapper).markOutboxSent(99L);
    }
}
