package com.myxhs.im.consumer;

import com.alibaba.fastjson2.JSON;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.im.dto.RouteMessage;
import com.myxhs.im.handler.ImWebSocketHandler;
import com.myxhs.im.service.OnlineRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 跨实例消息路由消费者
 * <p>
 * IM 服务多实例部署时，用户 A 在实例 1，用户 B 在实例 2。
 * A 发消息给 B → 实例 1 发送 MQ 消息 → 实例 2 消费后推送给 B。
 * </p>
 * <p>
 * 消费模式：BROADCASTING（广播模式）
 * 所有实例都会收到每条消息，每个实例根据 targetServerId 判断是否需要处理。
 * 为什么不用 CLUSTERING？CLUSTERING 模式下消息只会被一个实例消费，
 * 如果被分发到非目标实例，消息就会被丢弃（ACK 但不处理），导致消息丢失。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "IM_ROUTE_TOPIC",
        consumerGroup = "im-route-consumer-group",
        messageModel = MessageModel.BROADCASTING
)
public class ImRouteConsumer implements RocketMQListener<MessageExt> {

    private final ImWebSocketHandler webSocketHandler;
    private final OnlineRouteService onlineRouteService;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            RouteMessage routeMsg = JSON.parseObject(body, RouteMessage.class);

            if (routeMsg == null || routeMsg.getReceiverId() == null) {
                log.warn("[IM路由] 消息格式异常，跳过: msgId={}", msg.getMsgId());
                return;
            }

            // 只处理目标为本实例的消息
            String localServerId = onlineRouteService.getServerId();
            if (!localServerId.equals(routeMsg.getTargetServerId())) {
                // 非目标实例，跳过（广播模式下所有实例都会收到）
                return;
            }

            // 构建推送 JSON（区分普通聊天消息和已读回执）
            String pushJson;
            if (routeMsg.getMsgType() != null && routeMsg.getMsgType() == 99) {
                // msgType=99 表示已读回执，content 字段已经是完整 JSON
                pushJson = routeMsg.getContent();
            } else {
                // 普通聊天消息
                pushJson = JSON.toJSONString(Map.of(
                        "ver", 1, "type", "CHAT",
                        "msgId", routeMsg.getMsgId(),
                        "from", routeMsg.getSenderId(),
                        "content", routeMsg.getContent(),
                        "msgType", routeMsg.getMsgType(),
                        "timestamp", routeMsg.getTimestamp()));
            }

            boolean pushed = webSocketHandler.pushToUser(routeMsg.getReceiverId(), pushJson);
            if (pushed) {
                log.info("[IM路由] 跨实例推送成功: receiverId={}, msgId={}",
                        routeMsg.getReceiverId(), routeMsg.getMsgId());
            } else {
                log.warn("[IM路由] 用户已离线，消息已持久化: receiverId={}", routeMsg.getReceiverId());
                // 消息已在发送端持久化到 DB，这里不需要额外处理
            }

        } catch (Exception e) {
            log.error("[IM路由] 消费异常: mqMsgId={}", msg.getMsgId(), e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
