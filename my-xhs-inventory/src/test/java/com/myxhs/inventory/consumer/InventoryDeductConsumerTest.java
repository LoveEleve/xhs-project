package com.myxhs.inventory.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.inventory.mapper.InventoryMapper;
import com.myxhs.inventory.service.InventoryL2InboxService;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * L2 落账消费者单测（C-008 F4：事务内幂等 + 版本门完成后推进）
 */
@ExtendWith(MockitoExtension.class)
class InventoryDeductConsumerTest {

    @Mock private InventoryMapper inventoryMapper;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private BusinessMetrics businessMetrics;
    @Mock private ValueOperations<String, String> valueOperations;

    private InventoryDeductConsumer consumer;

    private static final String VERSION_KEY = "inventory:event:version:123:456";

    @BeforeEach
    void setUp() {
        // 真实 inbox 服务 + mock mapper（applyOnce 的"INSERT IGNORE + 变更"流程真实执行）
        InventoryL2InboxService inboxService = new InventoryL2InboxService(inventoryMapper);
        consumer = new InventoryDeductConsumer(inventoryMapper, new ObjectMapper(),
                stringRedisTemplate, rocketMQTemplate, businessMetrics, inboxService);
    }

    @Test
    @DisplayName("C-008：旧版本事件（version >= eventTime）→ 跳过（不触碰 inbox/库存）")
    void staleEventSkipped() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn("2000");

        consumer.onMessage(message("{\"orderId\":123,\"skuId\":456,\"quantity\":2,\"action\":\"PRE_DEDUCT\",\"eventTime\":1000}"));

        verify(inventoryMapper, never()).insertL2Event(anyLong(), anyLong(), anyString());
        verify(inventoryMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("C-008：首次事件 → inbox 落账 + 库存变更（同事务）")
    void firstApplicationApplies() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn(null);
        when(inventoryMapper.insertL2Event(123L, 456L, "PRE_DEDUCT")).thenReturn(1);
        when(inventoryMapper.deductStock(456L, 2)).thenReturn(1);

        consumer.onMessage(message("{\"orderId\":123,\"skuId\":456,\"quantity\":2,\"action\":\"PRE_DEDUCT\",\"eventTime\":1000}"));

        verify(inventoryMapper).insertL2Event(123L, 456L, "PRE_DEDUCT");
        verify(inventoryMapper).deductStock(456L, 2);
        verify(businessMetrics).recordInventoryAction("l2_prededuct_ok");
    }

    @Test
    @DisplayName("C-008：重复事件（inbox 命中）→ 跳过库存变更（不双扣）")
    void duplicateApplicationSkipped() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn(null);
        when(inventoryMapper.insertL2Event(123L, 456L, "PRE_DEDUCT")).thenReturn(0);

        consumer.onMessage(message("{\"orderId\":123,\"skuId\":456,\"quantity\":2,\"action\":\"PRE_DEDUCT\",\"eventTime\":1000}"));

        verify(inventoryMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("C-008：RELEASE 条件失败（0 行）→ 事件已完成（inbox 落账），不抛错不重投")
    void releaseConditionFailureMarkedDone() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(VERSION_KEY)).thenReturn(null);
        when(inventoryMapper.insertL2Event(123L, 456L, "RELEASE")).thenReturn(1);
        when(inventoryMapper.releaseStock(456L, 2)).thenReturn(0);

        consumer.onMessage(message("{\"orderId\":123,\"skuId\":456,\"quantity\":2,\"action\":\"RELEASE\",\"eventTime\":1000}"));

        verify(inventoryMapper).insertL2Event(123L, 456L, "RELEASE");
        verify(inventoryMapper).releaseStock(456L, 2);
    }

    private MessageExt message(String body) {
        MessageExt message = new MessageExt();
        message.setMsgId("msg-" + System.nanoTime());
        message.setBody(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
