package com.myxhs.im.service;

import com.myxhs.im.dto.ImMessage;
import com.myxhs.im.entity.ChatMessage;
import com.myxhs.im.entity.ChatUserRelation;
import com.myxhs.im.handler.ImWebSocketHandler;
import com.myxhs.im.mapper.ChatMessageMapper;
import com.myxhs.im.mapper.ChatUserRelationMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IM 聊天核心服务单测（C-013）
 * <p>
 * 验证口径：发送链路顺序（未读前移——F1）/ 各步降级 + ACK 必达（D3）/ clientMsgId 幂等（F6）/
 * 软删清零透传（D1）/ READ 降级（D4）/ 连接重建（D2）/ 补发排序清理。
 * </p>
 */
@SuppressWarnings("unchecked")
class ChatServiceTest {

    private ChatMessageMapper chatMessageMapper;
    private ChatUserRelationMapper chatUserRelationMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private HashOperations<String, Object, Object> hashOps;
    private ZSetOperations<String, String> zsetOps;
    private OnlineRouteService onlineRouteService;
    private MessagePersistService messagePersistService;
    private RedissonClient redissonClient;
    private RLock rLock;
    private ImWebSocketHandler webSocketHandler;
    private WebSocketSession session;

    private ChatService service;

    @BeforeAll
    static void initMpLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                ChatUserRelation.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                ChatMessage.class);
    }

    @BeforeEach
    void setUp() throws Exception {
        chatMessageMapper = mock(ChatMessageMapper.class);
        chatUserRelationMapper = mock(ChatUserRelationMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        onlineRouteService = mock(OnlineRouteService.class);
        messagePersistService = mock(MessagePersistService.class);
        redissonClient = mock(RedissonClient.class);
        rLock = mock(RLock.class);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        webSocketHandler = mock(ImWebSocketHandler.class);
        session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getId()).thenReturn("sess-1");

        service = new ChatService(chatMessageMapper, chatUserRelationMapper, redisTemplate,
                onlineRouteService, messagePersistService, redissonClient);
        service.setWebSocketHandler(webSocketHandler);
    }

    private static ImMessage chat(long to, String content) {
        return ImMessage.builder().type("CHAT").to(to).content(content).msgType(0).build();
    }

    // ==================== handleChat ====================

    @Test
    @DisplayName("校验：自己发给自己 / 空内容 → NACK，不落库")
    void rejectsSelfAndEmpty() {
        service.handleChat(9L, chat(9L, "hi"), session);
        service.handleChat(9L, chat(10L, "  "), session);

        verify(messagePersistService, never()).saveMessageWithTransaction(anyLong(), anyLong(),
                anyLong(), anyLong(), anyString(), anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("C-013 F1：未读 +1 在路由推送之前（InOrder——幽灵未读竞态消除）")
    void unreadIncrementedBeforeRouting() throws Exception {
        when(onlineRouteService.getRoute(10L)).thenReturn("other-instance");
        when(valueOps.increment(anyString())).thenReturn(7L);

        service.handleChat(9L, chat(10L, "hello"), session);

        InOrder inOrder = inOrder(redisTemplate, session);
        // ① 未读 +1（2 个 ARGV 的脚本）
        inOrder.verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString());
        // ② 离线副本（4 个 ARGV 的脚本）
        inOrder.verify(redisTemplate).execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString());
        // ③ 跨实例路由
        inOrder.verify(redisTemplate).convertAndSend(eq("myxhs:im:route:other-instance"), anyString());
        // ④ ACK 必达
        inOrder.verify(session).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("C-013 D3：路由异常 → 仍发 ACK（消息已持久化，不触发发送方重发）")
    void routeFailureStillAcks() throws Exception {
        when(onlineRouteService.getRoute(10L)).thenThrow(new RuntimeException("Redis down"));

        service.handleChat(9L, chat(10L, "hello"), session); // 不抛

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"type\":\"ACK\"");
    }

    @Test
    @DisplayName("C-013 D3：离线副本写入异常 → 仍尝试推送与 ACK")
    void storeOfflineFailureStillRoutesAndAcks() throws Exception {
        when(onlineRouteService.getRoute(10L)).thenReturn("other-instance");
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis down"));

        service.handleChat(9L, chat(10L, "hello"), session); // 不抛

        verify(redisTemplate).convertAndSend(eq("myxhs:im:route:other-instance"), anyString());
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"type\":\"ACK\"");
    }

    @Test
    @DisplayName("C-013 F6：clientMsgId 重放 → ACK 原 msgId，不重复落库/路由")
    void clientMsgIdReplayAcksOriginal() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn("12345");

        ImMessage msg = chat(10L, "hello");
        msg.setClientMsgId("cli-1");
        service.handleChat(9L, msg, session);

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"msgId\":12345").contains("ACK");
        verify(messagePersistService, never()).saveMessageWithTransaction(anyLong(), anyLong(),
                anyLong(), anyLong(), anyString(), anyInt(), anyLong(), any());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    @DisplayName("C-013 F6：clientMsgId 首次 → SETNX 标记 + 正常流程")
    void clientMsgIdFirstProcess() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(onlineRouteService.getRoute(10L)).thenReturn("other-instance");
        when(valueOps.increment(anyString())).thenReturn(7L);

        ImMessage msg = chat(10L, "hello");
        msg.setClientMsgId("cli-1");
        service.handleChat(9L, msg, session);

        verify(valueOps).setIfAbsent(eq("myxhs:im:msgdedup:9:cli-1"), anyString(), eq(Duration.ofMinutes(5)));
        verify(messagePersistService).saveMessageWithTransaction(anyLong(), anyLong(),
                eq(9L), eq(10L), eq("hello"), eq(0), eq(7L), any());
    }

    @Test
    @DisplayName("C-013 F6：persist 失败 → 删幂等标记（重试可真正重处理）+ NACK")
    void persistFailureDeletesDedupMark() throws Exception {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doThrow(new RuntimeException("DB down")).when(messagePersistService)
                .saveMessageWithTransaction(anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyLong(), any());

        ImMessage msg = chat(10L, "hello");
        msg.setClientMsgId("cli-1");
        service.handleChat(9L, msg, session);

        verify(redisTemplate).delete("myxhs:im:msgdedup:9:cli-1");
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("NACK");
    }

    @Test
    @DisplayName("锁竞争：tryLock 失败 → NACK 发送过于频繁，不落库")
    void lockBusyNack() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any())).thenReturn(false);

        service.handleChat(9L, chat(10L, "hello"), session);

        verify(messagePersistService, never()).saveMessageWithTransaction(anyLong(), anyLong(),
                anyLong(), anyLong(), anyString(), anyInt(), anyLong(), any());
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("发送过于频繁");
    }

    @Test
    @DisplayName("超长消息截断（不切代理对）+ seqNo 来自 Redis INCR")
    void longContentTruncatedAndSeqAssigned() throws Exception {
        when(onlineRouteService.getRoute(10L)).thenReturn("other-instance");
        when(valueOps.increment(anyString())).thenReturn(11L);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) sb.append('a');
        sb.append("\uD83D\uDE00"); // 高代理对紧跟其后

        service.handleChat(9L, chat(10L, sb.toString()), session);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagePersistService).saveMessageWithTransaction(anyLong(), anyLong(),
                eq(9L), eq(10L), contentCaptor.capture(), eq(0), eq(11L), any());
        assertThat(contentCaptor.getValue().length()).isEqualTo(2000);
    }

    // ==================== handleRead / typing / ack ====================

    @Test
    @DisplayName("已读：DB 定向清零 + Redis 清零 + 通知对方（本实例直推）")
    void handleReadLocal() {
        when(onlineRouteService.getRoute(10L)).thenReturn("s1");
        when(onlineRouteService.getServerId()).thenReturn("s1");

        service.handleRead(9L, ImMessage.builder().type("READ").peerId(10L).build(), session);

        verify(chatUserRelationMapper).resetUnread(9L, 10L);
        verify(hashOps).put("myxhs:im:unread:9", "10", "0");
        verify(webSocketHandler).pushToUser(eq(10L), anyString());
    }

    @Test
    @DisplayName("C-013 D4：Redis 清零异常 → READ_NOTIFY 仍送达（降级）")
    void handleReadRedisFailureStillNotifies() {
        when(onlineRouteService.getRoute(10L)).thenReturn("s1");
        when(onlineRouteService.getServerId()).thenReturn("s1");
        doThrow(new RuntimeException("Redis down")).when(hashOps).put(anyString(), anyString(), anyString());

        service.handleRead(9L, ImMessage.builder().type("READ").peerId(10L).build(), session); // 不抛

        verify(webSocketHandler).pushToUser(eq(10L), anyString());
    }

    @Test
    @DisplayName("输入状态：对方离线不发；在线本实例直推")
    void handleTyping() {
        when(onlineRouteService.getRoute(10L)).thenReturn(null);
        service.handleTyping(9L, ImMessage.builder().type("TYPING").peerId(10L).build());
        verify(webSocketHandler, never()).pushToUser(anyLong(), anyString());

        when(onlineRouteService.getRoute(10L)).thenReturn("s1");
        when(onlineRouteService.getServerId()).thenReturn("s1");
        service.handleTyping(9L, ImMessage.builder().type("TYPING").peerId(10L).build());
        verify(webSocketHandler).pushToUser(eq(10L), anyString());
    }

    @Test
    @DisplayName("ACK：ZREM 离线副本")
    void handleAckRemovesOffline() {
        service.handleAck(9L, ImMessage.builder().type("ACK").msgId(500L).build());
        verify(zsetOps).remove("myxhs:im:offline:9", "500");
    }

    // ==================== 会话管理 ====================

    @Test
    @DisplayName("C-013 D1：删除会话 → 软删（SQL 已含 unread_count=0）+ Redis 清零；Redis 失败容忍")
    void deleteConversation() {
        when(chatUserRelationMapper.softDeleteConversation(9L, 10L)).thenReturn(1);
        service.deleteConversation(9L, 10L);
        verify(chatUserRelationMapper).softDeleteConversation(9L, 10L);
        verify(hashOps).put("myxhs:im:unread:9", "10", "0");

        doThrow(new RuntimeException("Redis down")).when(hashOps).put(anyString(), anyString(), anyString());
        service.deleteConversation(9L, 10L); // 不抛
    }

    @Test
    @DisplayName("总未读：Redis 求和；hash 空 → DB fallback")
    void totalUnread() {
        when(hashOps.entries("myxhs:im:unread:9")).thenReturn(Map.of("10", "2", "11", "3"));
        assertThat(service.getTotalUnreadCount(9L)).isEqualTo(5);

        when(hashOps.entries("myxhs:im:unread:9")).thenReturn(Map.of());
        when(chatUserRelationMapper.selectList(any())).thenReturn(List.of(
                new ChatUserRelation().setUserId(9L).setPeerId(10L).setUnreadCount(2),
                new ChatUserRelation().setUserId(9L).setPeerId(11L).setUnreadCount(3)));
        assertThat(service.getTotalUnreadCount(9L)).isEqualTo(5);
    }

    @Test
    @DisplayName("C-013 D2：连接重建——DB 有未读 → 重建 hash + 30d TTL；无未读 → 删键")
    void refreshUnreadCache() {
        when(chatUserRelationMapper.selectList(any())).thenReturn(List.of(
                new ChatUserRelation().setUserId(9L).setPeerId(10L).setUnreadCount(2)));

        service.refreshUnreadCache(9L);

        verify(redisTemplate).delete("myxhs:im:unread:9");
        verify(hashOps).putAll(eq("myxhs:im:unread:9"), any());
        verify(redisTemplate).expire(eq("myxhs:im:unread:9"), eq(Duration.ofDays(30)));

        when(chatUserRelationMapper.selectList(any())).thenReturn(List.of());
        service.refreshUnreadCache(9L);
        verify(redisTemplate, times(2)).delete("myxhs:im:unread:9");
    }

    // ==================== 离线补发 ====================

    @Test
    @DisplayName("补发：seqNo 升序 + DB 缺失副本清理 + OFFLINE 批次")
    void pushOfflineMessages() throws Exception {
        when(zsetOps.range("myxhs:im:offline:9", 0, 999))
                .thenReturn(new java.util.LinkedHashSet<>(List.of("101", "102", "103")));
        ChatMessage m1 = ChatMessage.builder().id(101L).seqNo(2L).senderId(10L).content("b").msgType(0).build();
        ChatMessage m2 = ChatMessage.builder().id(102L).seqNo(1L).senderId(10L).content("a").msgType(0).build();
        when(chatMessageMapper.selectBatchIds(any())).thenReturn(new java.util.ArrayList<>(List.of(m1, m2))); // 103 已不存在

        service.pushOfflineMessages(9L, session);

        verify(zsetOps).remove("myxhs:im:offline:9", "103"); // DB 缺失清理
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("OFFLINE");
        assertThat(payload.indexOf("\"seqNo\":1")).isLessThan(payload.indexOf("\"seqNo\":2")); // 升序
    }
}
