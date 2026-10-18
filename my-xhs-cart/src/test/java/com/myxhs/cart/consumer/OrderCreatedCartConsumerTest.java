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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 下单清车消费者单测（C-006）
 */
class OrderCreatedCartConsumerTest {

    private CartOrderLinkageService linkageService;
    private OrderCreatedCartConsumer consumer;

    @BeforeEach
    void setUp() {
        linkageService = mock(CartOrderLinkageService.class);
        consumer = new OrderCreatedCartConsumer(linkageService, new ObjectMapper());
    }

    private MessageExt message(String json) {
        MessageExt msg = new MessageExt();
        msg.setBody(json.getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    @Test
    @DisplayName("CART 来源创建消息 → 解析并触发清车（Long 字符串编码兼容）")
    void cartOrderTriggersClear() {
        consumer.onMessage(message("{\"orderNo\":\"ORD-1\",\"userId\":\"1001\","
                + "\"skuItems\":[{\"skuId\":\"1\",\"quantity\":2},{\"skuId\":2,\"quantity\":1}],\"source\":\"CART\"}"));

        org.mockito.ArgumentCaptor<List<CartOrderLinkageService.LinkageItem>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(linkageService).handleOrderCreated(eq("ORD-1"), eq(1001L), eq("CART"), captor.capture());
        List<CartOrderLinkageService.LinkageItem> items = captor.getValue();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).skuId()).isEqualTo(1L);
        assertThat(items.get(0).quantity()).isEqualTo(2);
        assertThat(items.get(1).skuId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("缺关键字段（无 skuItems）→ 抛错触发重试（对齐 inventory 消费者校验风格）")
    void malformedThrows() {
        assertThatThrownBy(() -> consumer.onMessage(
                message("{\"orderNo\":\"ORD-1\",\"userId\":\"1001\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("可重试");

        verify(linkageService, never()).handleOrderCreated(anyString(), anyLong(), anyString(), any());
    }
}
