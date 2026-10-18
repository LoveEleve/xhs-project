package com.myxhs.cart.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.cart.service.CartOrderLinkageService;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 取消回补消费者单测（C-006）
 */
class OrderTerminationCartConsumerTest {

    private CartOrderLinkageService linkageService;
    private OrderTerminationCartConsumer consumer;

    @BeforeEach
    void setUp() {
        linkageService = mock(CartOrderLinkageService.class);
        consumer = new OrderTerminationCartConsumer(linkageService, new ObjectMapper());
    }

    private MessageExt message(String json) {
        MessageExt msg = new MessageExt();
        msg.setBody(json.getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    @Test
    @DisplayName("终结消息（含 items）→ 解析并触发回补")
    void terminationWithItemsTriggersRestore() {
        consumer.onMessage(message("{\"eventVersion\":2,\"orderNo\":\"ORD-1\",\"userId\":\"1001\","
                + "\"reason\":\"USER_CANCELLED\",\"items\":[{\"skuId\":\"1\",\"quantity\":2}]}"));

        org.mockito.ArgumentCaptor<List<CartOrderLinkageService.LinkageItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(linkageService).handleOrderTerminated(eq("ORD-1"), eq(1001L), captor.capture());
        List<CartOrderLinkageService.LinkageItem> items = captor.getValue();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).skuId()).isEqualTo(1L);
        assertThat(items.get(0).quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("旧版事件（无 items 字段）→ 传空列表（联动服务按跳过处理，不重试）")
    void legacyEventPassesEmptyItems() {
        consumer.onMessage(message("{\"eventVersion\":1,\"orderNo\":\"ORD-1\",\"userId\":\"1001\"}"));

        org.mockito.ArgumentCaptor<List<CartOrderLinkageService.LinkageItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(linkageService).handleOrderTerminated(eq("ORD-1"), eq(1001L), captor.capture());
        assertThat(captor.getValue()).isEmpty();
    }
}
