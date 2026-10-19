package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.inventory.service.InventoryService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderTransactionConsumerTest {

    @Mock private InventoryService inventoryService;
    @Mock private MessageIdempotentHelper idempotentHelper;
    @Mock private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;
    @Mock private org.springframework.data.redis.core.SetOperations<String, String> setOperations;

    private OrderTransactionConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderTransactionConsumer(stringRedisTemplate, inventoryService, new ObjectMapper(), idempotentHelper);
    }

    @Test
    @DisplayName("C-008 F4：已完成（完成态标记命中）→ 重投跳过，不触碰业务")
    void shouldSkipWhenAlreadyProcessed() {
        when(idempotentHelper.isProcessed("inventory:order:consumed", "msg-0")).thenReturn(true);

        MessageExt message = new MessageExt();
        message.setMsgId("msg-0");
        message.setBody("{\"orderNo\":\"ORD-0\",\"userId\":1001,\"skuItems\":[{\"skuId\":1,\"quantity\":2}]}".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(message);

        verifyNoInteractions(inventoryService);
        verify(idempotentHelper, never()).markProcessed(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("C-008 F4：处理成功 → 完成后置标记（先处理、后置标记）")
    void shouldMarkProcessedAfterSuccess() {
        MessageExt message = new MessageExt();
        message.setMsgId("msg-ok");
        message.setBody("{\"orderNo\":\"ORD-OK\",\"userId\":1001,\"skuItems\":[{\"skuId\":1,\"quantity\":2}]}".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(message);

        InOrder inOrder = inOrder(inventoryService, idempotentHelper);
        inOrder.verify(inventoryService).preDeduct(any());
        inOrder.verify(idempotentHelper).markProcessed("inventory:order:consumed", "msg-ok", 86400L);
    }

    @Test
    @DisplayName("C-008 F4：失败不置标记（重投可重新处理；无前置标记需清理）")
    void shouldNotMarkProcessedOnFailure() {
        MessageExt message = new MessageExt();
        message.setMsgId("msg-1");
        message.setBody("{\"orderNo\":\"ORD-1\",\"skuItems\":[{\"skuId\":1,\"quantity\":2}]}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class);

        verify(idempotentHelper, never()).markProcessed(anyString(), anyString(), anyLong());
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("RV31：SKU不存在 → 写异常集合 + 抛错（不置完成标记）")
    void shouldThrowAndMarkAnomalyWhenSkuDoesNotExist() {
        doThrow(new com.myxhs.common.exception.BizException(com.myxhs.common.response.ResultCode.PARAM_INVALID, "SKU不存在"))
                .when(inventoryService).preDeduct(any());
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);

        MessageExt message = new MessageExt();
        message.setMsgId("msg-ack");
        message.setBody("{\"orderNo\":\"ORD-3\",\"userId\":1001,\"skuItems\":[{\"skuId\":6,\"quantity\":1}]}".getBytes(StandardCharsets.UTF_8));

        // RV31 语义：不可恢复坏消息写异常集合 + 抛错进入重试→DLQ（而非静默 ACK）
        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("不可恢复");
        verify(setOperations).add("myxhs:inventory:anomaly:sku-missing", "ORD-3");
        verify(idempotentHelper, never()).markProcessed(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("字段非法（skuItems 缺 quantity）→ 抛错重试（不置完成标记）")
    void shouldRetryWhenSkuItemMalformed() {
        MessageExt message = new MessageExt();
        message.setMsgId("msg-2");
        message.setBody("{\"orderNo\":\"ORD-2\",\"userId\":1001,\"skuItems\":[{\"skuId\":1}]}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class);

        verify(idempotentHelper, never()).markProcessed(anyString(), anyString(), anyLong());
        verifyNoInteractions(inventoryService);
    }
}
