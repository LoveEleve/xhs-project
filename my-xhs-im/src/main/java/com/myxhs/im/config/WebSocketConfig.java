package com.myxhs.im.config;

import com.myxhs.im.handler.ImWebSocketHandler;
import com.myxhs.im.handler.ImHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置
 * <p>
 * 基于 Spring MVC WebSocket（Tomcat NIO），而非 WebFlux。
 * 原因：MyBatis Plus 是阻塞式的，混用 Reactive 和阻塞 IO 会导致复杂度爆炸。
 * Tomcat NIO 模式下 WebSocket 连接不占用线程（只在有消息时分配线程处理），
 * 单机 10 万连接完全可行。
 * </p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ImWebSocketHandler imWebSocketHandler;
    private final ImHandshakeInterceptor handshakeInterceptor;

    @Value("${im.websocket.path:/api/im/ws}")
    private String wsPath;

    @Value("${im.websocket.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(imWebSocketHandler, wsPath)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins.split(","));
    }
}
