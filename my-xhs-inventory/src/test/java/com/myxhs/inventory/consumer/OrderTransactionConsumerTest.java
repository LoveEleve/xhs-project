package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.mq.MessageIdempotentHelper;
import com.myxhs.inventory.service.InventoryService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void shouldRetryWhenUserIdMissing() {
        MessageExt message = new MessageExt();
        message.setMsgId("msg-1");
        message.setBody("{\"orderNo\":\"ORD-1\",\"skuItems\":[{\"skuId\":1,\"quantity\":2}]}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class);

        verify(idempotentHelper).removeMark("inventory:order:consumed", "msg-1");
        verifyNoInteractions(inventoryService);
    }

    @Test
    void shouldThrowAndMarkAnomalyWhenSkuDoesNotExist() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);
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
        verify(idempotentHelper).removeMark("inventory:order:consumed", "msg-ack");
    }

    @Test
    void shouldRetryWhenSkuItemMalformed() {
        when(idempotentHelper.isFirstProcess(anyString(), anyString(), anyLong())).thenReturn(true);

        MessageExt message = new MessageExt();
        message.setMsgId("msg-2");
        message.setBody("{\"orderNo\":\"ORD-2\",\"userId\":1001,\"skuItems\":[{\"skuId\":1}]}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> consumer.onMessage(message))
                .isInstanceOf(RuntimeException.class);

        verify(idempotentHelper).removeMark("inventory:order:consumed", "msg-2");
        verifyNoInteractions(inventoryService);
    }
}
