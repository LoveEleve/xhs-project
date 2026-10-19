package com.myxhs.im.subscriber;

import com.myxhs.im.dto.RouteMessage;
import com.myxhs.im.handler.ImWebSocketHandler;
import com.myxhs.im.service.ChatService;
import com.myxhs.im.service.OnlineRouteService;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跨实例路由订阅器单测（C-013）
 * <p>
 * 验证口径：CHAT 重建 JSON + 推成功删副本/推失败降级；98/99 透传且瞬时信号不降级；97 踢线；
 * 格式异常不抛。
 * </p>
 */
class ImRouteSubscriberTest {

    private ImWebSocketHandler webSocketHandler;
    private ChatService chatService;
    private ImRouteSubscriber subscriber;

    @BeforeEach
    void setUp() {
        webSocketHandler = mock(ImWebSocketHandler.class);
        chatService = mock(ChatService.class);
        subscriber = new ImRouteSubscriber(mock(StringRedisTemplate.class),
                webSocketHandler, mock(OnlineRouteService.class), chatService);
    }

    private static DefaultMessage message(Object routeMsg) {
        return new DefaultMessage("channel".getBytes(StandardCharsets.UTF_8),
                JSON.toJSONString(routeMsg).getBytes(StandardCharsets.UTF_8));
    }

    private static RouteMessage chat(long receiverId, long msgId, int msgType) {
        return RouteMessage.builder().receiverId(receiverId).msgId(msgId).seqNo(7L)
                .senderId(9L).content("hi").msgType(msgType).timestamp(1L).traceId("t-1").build();
    }

    @Test
    @DisplayName("CHAT 推成功：删离线副本 + 载荷含 seqNo")
    void chatPushSuccess() {
        when(webSocketHandler.pushToUser(eq(10L), anyString())).thenReturn(true);

        subscriber.onMessage(message(chat(10L, 500L, 0)), null);

        verify(webSocketHandler).pushToUser(eq(10L), org.mockito.ArgumentMatchers.contains("\"seqNo\":7"));
        verify(chatService).removeOfflineMessage(10L, 500L);
        verify(chatService, never()).storeOfflineMessage(anyLong(), anyLong());
    }

    @Test
    @DisplayName("CHAT 推失败：降级存离线")
    void chatPushFailStoresOffline() {
        when(webSocketHandler.pushToUser(eq(10L), anyString())).thenReturn(false);

        subscriber.onMessage(message(chat(10L, 500L, 0)), null);

        verify(chatService).storeOfflineMessage(10L, 500L);
        verify(chatService, never()).removeOfflineMessage(anyLong(), anyLong());
    }

    @Test
    @DisplayName("99 已读回执：content 透传；推失败不存离线（瞬时信号）")
    void readNotifyPassthrough() {
        when(webSocketHandler.pushToUser(eq(10L), anyString())).thenReturn(false);

        subscriber.onMessage(message(RouteMessage.builder()
                .receiverId(10L).msgId(500L).senderId(9L)
                .content("{\"type\":\"READ_NOTIFY\"}").msgType(99).timestamp(1L).build()), null);

        verify(webSocketHandler).pushToUser(10L, "{\"type\":\"READ_NOTIFY\"}");
        verify(chatService, never()).storeOfflineMessage(anyLong(), anyLong());
        verify(chatService, never()).removeOfflineMessage(anyLong(), anyLong());
    }

    @Test
    @DisplayName("97 踢线：关闭本地连接，不推送")
    void kickClosesLocal() {
        subscriber.onMessage(message(RouteMessage.builder()
                .receiverId(10L).msgType(97).timestamp(1L).build()), null);

        verify(webSocketHandler).closeLocalSession(10L);
        verify(webSocketHandler, never()).pushToUser(anyLong(), anyString());
    }

    @Test
    @DisplayName("格式异常（无 receiverId）→ 忽略不抛")
    void malformedIgnored() {
        subscriber.onMessage(new DefaultMessage("ch".getBytes(StandardCharsets.UTF_8),
                "{\"msgType\":0}".getBytes(StandardCharsets.UTF_8)), null);

        verify(webSocketHandler, never()).pushToUser(anyLong(), anyString());
    }
}
