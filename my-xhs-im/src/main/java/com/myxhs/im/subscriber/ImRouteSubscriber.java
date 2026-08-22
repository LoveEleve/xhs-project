package com.myxhs.im.subscriber;

import com.alibaba.fastjson2.JSON;
import com.myxhs.im.dto.RouteMessage;
import com.myxhs.im.handler.ImWebSocketHandler;
import com.myxhs.im.service.ChatService;
import com.myxhs.im.service.OnlineRouteService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 基于 Redis Pub/Sub 的跨实例 IM 消息路由订阅器
 * <p>
 * 【M4 改造】替代 {@code ImRouteConsumer}（RocketMQ BROADCASTING 模式）。
 * 旧方案：每个实例都收到 MQ 消息，N-1 个实例做无效过滤，浪费 CPU。
 * 新方案：每实例订阅专属 Channel <code>im:route:{serverId}</code>，
 * 消息精准投递到目标实例，零浪费。
 * </p>
 * <p>
 * 降级策略：
 * - 消息已在发送端持久化到 DB（ChatService 先写 DB 再路由）
 * - 用户重连时拉取离线消息（ChatService.pushOfflineMessages）
 * - 推送失败时降级存离线消息（ZSet，MAX 1000 条，7 天过期）
 * </p>
 * <p>
 * 线程安全：
 * - RedisMessageListenerContainer 使用独立连接，与业务 Redis 连接隔离
 * - pushToUser 内部有 synchronized(session) 保护，多线程安全
 * - 回调在 Redis 订阅线程中执行，不阻塞业务线程池
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImRouteSubscriber implements MessageListener {

    private final StringRedisTemplate stringRedisTemplate;
    private final ImWebSocketHandler webSocketHandler;
    private final OnlineRouteService onlineRouteService;
    private final ChatService chatService;

    private RedisMessageListenerContainer container;

    /**
     * 启动 Redis Pub/Sub 订阅
     * <p>
     * 订阅本实例专属 Channel <code>im:route:{serverId}</code>，
     * 只有目标为本实例的消息才会被投递到此 Channel，无需 client-side 过滤。
     * </p>
     */
    @PostConstruct
    public void start() {
        String localServerId = onlineRouteService.getServerId();
        String channel = "myxhs:im:route:" + localServerId;

        container = new RedisMessageListenerContainer();
        container.setConnectionFactory(stringRedisTemplate.getConnectionFactory());
        container.addMessageListener(this, new ChannelTopic(channel));
        container.afterPropertiesSet();
        container.start();

        log.info("[IM路由] Redis Pub/Sub 订阅已启动: channel={}, serverId={}", channel, localServerId);
    }

    /**
     * 容器关闭时释放订阅连接
     */
    @PreDestroy
    public void stop() {
        if (container != null) {
            container.stop();
            log.info("[IM路由] Redis Pub/Sub 订阅已停止");
        }
    }

    /**
     * Redis Pub/Sub 消息回调
     * <p>
     * 消息格式：RouteMessage JSON 字符串，与旧 MQ 方案完全兼容。
     * msgType=99 为已读回执（content 已是完整 JSON），其他为普通聊天消息。
     * </p>
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            RouteMessage routeMsg = JSON.parseObject(body, RouteMessage.class);

            if (routeMsg == null || routeMsg.getReceiverId() == null) {
                log.warn("[IM路由] Pub/Sub消息格式异常，跳过");
                return;
            }

            // 构建推送 JSON
            String pushJson;
            int msgType = routeMsg.getMsgType() != null ? routeMsg.getMsgType() : 0;
            if (msgType == 99 || msgType == 98) {
                // msgType=99(已读回执)/98(TYPING)：content 字段已经是完整 JSON
                pushJson = routeMsg.getContent();
            } else {
                // 普通聊天消息（构建 CHAT JSON，包含 seqNo 保证跨实例消息有序）
                pushJson = JSON.toJSONString(Map.of(
                        "ver", 1, "type", "CHAT",
                        "msgId", routeMsg.getMsgId(),
                        "seqNo", routeMsg.getSeqNo(),
                        "from", routeMsg.getSenderId(),
                        "content", routeMsg.getContent(),
                        "msgType", msgType,
                        "timestamp", routeMsg.getTimestamp()));
            }

            boolean pushed = webSocketHandler.pushToUser(routeMsg.getReceiverId(), pushJson);
            if (pushed) {
                log.debug("[IM路由] 跨实例推送成功: receiverId={}, msgId={}",
                        routeMsg.getReceiverId(), routeMsg.getMsgId());
            } else {
                // 推送失败，降级：仅普通聊天消息存离线。已读回执/输入状态属于瞬时信号，不进入离线重放。
                if (routeMsg.getMsgId() != null && msgType != 99 && msgType != 98) {
                    chatService.storeOfflineMessage(routeMsg.getReceiverId(), routeMsg.getMsgId());
                    log.info("[IM路由] 用户已离线，降级存离线: receiverId={}, msgId={}, msgType={}",
                            routeMsg.getReceiverId(), routeMsg.getMsgId(), msgType);
                } else {
                    log.info("[IM路由] 用户已离线，瞬时信号不存离线: receiverId={}, msgId={}, msgType={}",
                            routeMsg.getReceiverId(), routeMsg.getMsgId(), msgType);
                }
            }
        } catch (Exception e) {
            log.error("[IM路由] Pub/Sub回调异常", e);
        }
    }
}
