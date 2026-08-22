package com.myxhs.im.handler;

import com.alibaba.fastjson2.JSON;
import com.myxhs.im.dto.ImMessage;
import com.myxhs.im.service.ChatService;
import com.myxhs.im.service.OnlineRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IM WebSocket 消息处理器
 * <p>
 * 核心职责：
 * 1. 管理 userId → WebSocketSession 映射（本实例在线用户）
 * 2. 分发消息到对应的业务处理方法
 * 3. 连接生命周期管理（上线注册路由、下线清理资源）
 * </p>
 * <p>
 * 线程安全：
 * - sessions 使用 ConcurrentHashMap
 * - Spring WebSocket 保证同一 session 的消息串行处理（不会并发调用 handleTextMessage）
 * - 跨 session 的操作（如推送消息给其他用户）通过 ConcurrentHashMap.get() 获取 session 后发送
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImWebSocketHandler extends TextWebSocketHandler {

    /** 本实例在线用户映射：userId → WebSocketSession */
    private final ConcurrentHashMap<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final ChatService chatService;
    private final OnlineRouteService onlineRouteService;

    private static final int MAX_CONNECTIONS = 50000;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (sessions.size() >= MAX_CONNECTIONS) {
            log.warn("[IM] 连接数超限: current={}, max={}", sessions.size(), MAX_CONNECTIONS);
            closeQuietly(session, CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        Long userId = getUserId(session);
        if (userId == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }

        // 踢掉旧连接（同一用户只允许一个 WebSocket 连接）
        WebSocketSession oldSession = sessions.put(userId, session);

        // 先注册新路由，再关闭旧连接。旧连接 afterConnectionClosed 中 remove(userId, oldSession) 失败后不会再误删新路由。
        onlineRouteService.registerRoute(userId);

        if (oldSession != null && oldSession.isOpen()) {
            log.info("[IM] 踢掉旧连接: userId={}, oldSessionId={}", userId, oldSession.getId());
            closeQuietly(oldSession, new CloseStatus(4001, "新设备登录，当前连接已断开"));
        }

        // 推送离线消息
        chatService.pushOfflineMessages(userId, session);

        log.info("[IM] 连接建立: userId={}, sessionId={}, 在线人数={}", userId, session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = getUserId(session);
        if (userId == null) return;

        String payload = message.getPayload();
        if (payload.isBlank()) return;

        try {
            ImMessage imMsg = JSON.parseObject(payload, ImMessage.class);
            if (imMsg == null || imMsg.getType() == null) {
                log.warn("[IM] 消息格式错误: userId={}, payload={}", userId, payload);
                return;
            }

            switch (imMsg.getType().toUpperCase()) {
                case "CHAT" -> chatService.handleChat(userId, imMsg, session);
                case "ACK" -> chatService.handleAck(userId, imMsg);
                case "READ" -> chatService.handleRead(userId, imMsg, session);
                case "TYPING" -> chatService.handleTyping(userId, imMsg);
                case "PING" -> handlePing(userId, session);
                case "LOGOUT" -> closeQuietly(session, CloseStatus.NORMAL);
                default -> log.warn("[IM] 未知消息类型: userId={}, type={}", userId, imMsg.getType());
            }
        } catch (Exception e) {
            log.error("[IM] 消息处理异常: userId={}, payload={}", userId, payload, e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = getUserId(session);
        if (userId != null) {
            // 只移除当前 session（防止新连接被误删）
            sessions.remove(userId, session);
            onlineRouteService.unregisterRoute(userId);
            log.info("[IM] 连接关闭: userId={}, status={}, 在线人数={}", userId, status, sessions.size());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Long userId = getUserId(session);
        log.warn("[IM] 传输错误: userId={}, error={}", userId, exception.getMessage());
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    // ==================== 公开方法（供 Service 层调用） ====================

    /**
     * 向本实例在线用户推送消息
     *
     * @return true=推送成功，false=用户不在线或发送失败
     */
    public boolean pushToUser(Long userId, String jsonMessage) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        try {
            // synchronized 保证同一 session 的发送不会并发（WebSocket 协议要求）
            synchronized (session) {
                session.sendMessage(new TextMessage(jsonMessage));
            }
            return true;
        } catch (IOException e) {
            log.warn("[IM] 推送失败: userId={}, error={}", userId, e.getMessage());
            return false;
        }
    }

    /**
     * 检查用户是否在本实例在线
     */
    public boolean isLocalOnline(Long userId) {
        WebSocketSession session = sessions.get(userId);
        return session != null && session.isOpen();
    }

    /**
     * 获取本实例在线人数
     */
    public int getOnlineCount() {
        return sessions.size();
    }

    // ==================== 私有方法 ====================

    private Long getUserId(WebSocketSession session) {
        Object userId = session.getAttributes().get("userId");
        return userId instanceof Long ? (Long) userId : null;
    }

    /**
     * 处理心跳：回复 PONG + 续期 Redis 路由 TTL
     * <p>
     * 路由 TTL = 3 倍心跳间隔（90s），每次心跳续期。
     * 如果客户端 90s 内没有发送 PING，路由自动过期，
     * 新消息会被存为离线消息而非尝试推送到已断开的连接。
     * </p>
     */
    private void handlePing(Long userId, WebSocketSession session) {
        sendPong(session);
        // 续期 Redis 路由 TTL（防止路由过期导致消息被存为离线）
        // 只续期本实例确认还有效的 session，避免 GC 停顿/网络分区后路由过期再恢复时
        // 本地 session 还在但路由已丢失，导致消息被跨实例转发或存离线
        if (sessions.containsKey(userId) && session.isOpen()) {
            try {
                onlineRouteService.renewRoute(userId);
            } catch (Exception e) {
                log.warn("[IM] 路由续期失败: userId={}", userId);
            }
        }
    }

    private void sendPong(WebSocketSession session) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
            }
        } catch (IOException e) {
            log.warn("[IM] 发送PONG失败: sessionId={}", session.getId());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
        }
    }
}
