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

    /**
     * WS 容器帧大小上限：单条文本消息 2000 字符（UTF-8 最坏约 8KB），
     * 64KB 足够且能拦住"单帧巨包"（超限帧在解析前即被容器拒绝，保护堆内存）。
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean createWebSocketContainer() {
        org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean container =
                new org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        return container;
    }
}
