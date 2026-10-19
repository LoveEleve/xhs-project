package com.myxhs.im.handler;

import com.myxhs.im.service.ChatService;
import com.myxhs.im.service.OnlineRouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IM WebSocket 处理器单测（C-013）
 * <p>
 * 验证口径：连接上限老用户豁免（F3）/ 连接建立（踢本地+跨实例+重建+补发）/ 关闭守卫（双参 remove）/
 * 消息分发 / 心跳续期。
 * </p>
 */
@SuppressWarnings("unchecked")
class ImWebSocketHandlerTest {

    private ChatService chatService;
    private OnlineRouteService onlineRouteService;
    private ImWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        onlineRouteService = mock(OnlineRouteService.class);
        handler = new ImWebSocketHandler(chatService, onlineRouteService);
    }

    private static WebSocketSession session(long userId, boolean open) {
        WebSocketSession s = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("userId", userId);
        when(s.getAttributes()).thenReturn(attrs);
        when(s.getId()).thenReturn("sess-" + userId);
        when(s.isOpen()).thenReturn(open);
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, WebSocketSession> sessionsMap() {
        return (Map<Long, WebSocketSession>) ReflectionTestUtils.getField(handler, "sessions");
    }

    @Test
    @DisplayName("C-013 F3：满员但老用户重连 → 豁免（put 替换不增长）")
    void capExemptionForExistingUser() throws Exception {
        Map<Long, WebSocketSession> map = sessionsMap();
        WebSocketSession one = session(1L, true);
        for (long i = 0; i < 50_000; i++) map.put(i, one);
        when(onlineRouteService.registerRoute(9L)).thenReturn(null);

        WebSocketSession newSession = session(9L, true);
        handler.afterConnectionEstablished(newSession);

        verify(newSession, never()).close(any(CloseStatus.class));
        verify(chatService).refreshUnreadCache(9L);
        verify(chatService).pushOfflineMessages(eq(9L), eq(newSession));
        assertThat(map.get(9L)).isSameAs(newSession);
    }

    @Test
    @DisplayName("满员 + 新用户 → 拒绝关闭（SERVICE_OVERLOAD）")
    void capRejectsNewUser() throws Exception {
        Map<Long, WebSocketSession> map = sessionsMap();
        WebSocketSession one = session(1L, true);
        for (long i = 0; i < 50_000; i++) map.put(i, one);

        WebSocketSession newSession = session(999_999L, true);
        handler.afterConnectionEstablished(newSession);

        verify(newSession).close(any(CloseStatus.class));
        verify(chatService, never()).refreshUnreadCache(999_999L);
    }

    @Test
    @DisplayName("无 userId → 拒绝（POLICY_VIOLATION）")
    void noUserIdRejected() throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getAttributes()).thenReturn(new HashMap<>());
        when(s.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(s);

        verify(s).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("连接建立：本地踢旧 + 跨实例踢线 + 重建未读 + 补发")
    void establishedKicksAndRestores() throws Exception {
        WebSocketSession oldLocal = session(9L, true);
        sessionsMap().put(9L, oldLocal);
        when(onlineRouteService.registerRoute(9L)).thenReturn("prev-instance");
        when(onlineRouteService.getServerId()).thenReturn("me");

        WebSocketSession newSession = session(9L, true);
        handler.afterConnectionEstablished(newSession);

        verify(oldLocal).close(any(CloseStatus.class));                 // 本地踢旧
        verify(onlineRouteService).kickRemote("prev-instance", 9L);    // 跨实例踢线
        verify(chatService).refreshUnreadCache(9L);                    // C-013 D2 重建
        verify(chatService).pushOfflineMessages(9L, newSession);
    }

    @Test
    @DisplayName("关闭守卫：被替换的旧连接不注销路由；当前连接正常注销")
    void closedGuard() {
        WebSocketSession current = session(9L, true);
        sessionsMap().put(9L, current);

        WebSocketSession stale = session(9L, false);
        handler.afterConnectionClosed(stale, CloseStatus.NORMAL);
        verify(onlineRouteService, never()).unregisterRoute(9L);

        handler.afterConnectionClosed(current, CloseStatus.NORMAL);
        verify(onlineRouteService).unregisterRoute(9L);
        assertThat(sessionsMap()).doesNotContainKey(9L);
    }

    @Test
    @DisplayName("消息分发：CHAT/ACK/READ/TYPING 路由到 service；未知类型不抛")
    void dispatch() throws Exception {
        WebSocketSession s = session(9L, true);
        sessionsMap().put(9L, s);

        handler.handleTextMessage(s, new TextMessage("{\"type\":\"CHAT\",\"to\":10,\"content\":\"hi\"}"));
        verify(chatService).handleChat(eq(9L), any(), eq(s));

        handler.handleTextMessage(s, new TextMessage("{\"type\":\"ACK\",\"msgId\":5}"));
        verify(chatService).handleAck(eq(9L), any());

        handler.handleTextMessage(s, new TextMessage("{\"type\":\"READ\",\"peerId\":10}"));
        verify(chatService).handleRead(eq(9L), any(), eq(s));

        handler.handleTextMessage(s, new TextMessage("{\"type\":\"TYPING\",\"peerId\":10}"));
        verify(chatService).handleTyping(eq(9L), any());

        handler.handleTextMessage(s, new TextMessage("{\"type\":\"UNKNOWN_X\"}")); // 不抛
    }

    @Test
    @DisplayName("心跳：PONG + 路由续期（仅在册且 session 打开）")
    void pingRenews() throws Exception {
        WebSocketSession s = session(9L, true);
        sessionsMap().put(9L, s);

        handler.handleTextMessage(s, new TextMessage("{\"type\":\"PING\"}"));

        verify(s, org.mockito.Mockito.atLeastOnce()).sendMessage(any(TextMessage.class));
        verify(onlineRouteService).renewRoute(9L);
    }

    @Test
    @DisplayName("pushToUser：在册且打开 → 发送 true；否则 false")
    void pushToUser() throws Exception {
        WebSocketSession s = session(9L, true);
        sessionsMap().put(9L, s);

        assertThat(handler.pushToUser(9L, "{}")).isTrue();
        verify(s).sendMessage(any(TextMessage.class));

        assertThat(handler.pushToUser(10L, "{}")).isFalse();
    }
}
