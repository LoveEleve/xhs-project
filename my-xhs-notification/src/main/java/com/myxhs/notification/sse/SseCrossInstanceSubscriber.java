package com.myxhs.notification.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;

/**
 * SSE 跨实例推送 Redis Pub/Sub 消费者
 * <p>
 * 订阅 Redis Channel（notify:sse:channel），收到跨实例推送消息后：
 * 1. 解析消息中的 userId 和 event 类型
 * 2. 检查目标用户是否在本实例在线
 * 3. 在线则推送给本实例的 SseEmitter
 * <p>
 * 为什么用 Redis Pub/Sub 而不是 RocketMQ？
 * - SSE 推送是"即发即忘"场景，不需要持久化和重试
 * - Pub/Sub 延迟更低（毫秒级 vs MQ 的数十毫秒级）
 * - 不需要关心消息堆积问题
 * - 实现更轻量，不需要额外的 Consumer Group 配置
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseCrossInstanceSubscriber implements MessageListener {

    private final SseEmitterManager sseEmitterManager;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String NOTIFY_SSE_CHANNEL = "notify:sse:channel";

    private RedisMessageListenerContainer container;

    /**
     * 应用启动后注册 Redis Pub/Sub 订阅
     * <p>
     * 【修复M17】afterPropertiesSet() 仅验证配置，不启动监听线程。
     * 必须同时调用 start() 才能真正订阅 Redis Channel。
     * </p>
     */
    @PostConstruct
    public void init() {
        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        container.addMessageListener(this, new ChannelTopic(NOTIFY_SSE_CHANNEL));
        container.afterPropertiesSet();
        container.start();
        log.info("[SSE] 跨实例推送订阅已启动: channel={}", NOTIFY_SSE_CHANNEL);
    }

    @PreDestroy
    public void shutdown() {
        if (container != null) {
            container.stop();
            log.info("[SSE] 跨实例推送订阅已停止");
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            @SuppressWarnings("unchecked")
            Map<String, Object> msgMap = objectMapper.readValue(body, Map.class);

            Long userId = toLong(msgMap.get("userId"));
            String eventName = (String) msgMap.get("event");
            Object data = msgMap.get("data");

            if (userId == null || eventName == null) {
                log.warn("[SSE] 跨实例消息格式异常: body={}", body);
                return;
            }

            // 委托给 SseEmitterManager 处理（只有目标用户在本实例在线时才推送）
            sseEmitterManager.handleCrossInstanceMessage(userId, eventName, data);

        } catch (Exception e) {
            log.error("[SSE] 跨实例消息处理异常", e);
        }
    }

    private Long toLong(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try { return Long.parseLong(obj.toString()); } catch (Exception e) { return null; }
    }
}
