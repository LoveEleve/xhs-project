package com.myxhs.im.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myxhs.im.dto.ConversationVO;
import com.myxhs.im.dto.ImMessage;
import com.myxhs.im.dto.RouteMessage;
import com.myxhs.im.entity.ChatMessage;
import com.myxhs.im.entity.ChatUserRelation;
import com.myxhs.im.handler.ImWebSocketHandler;
import com.myxhs.im.mapper.ChatMessageMapper;
import com.myxhs.im.mapper.ChatUserRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * IM 聊天核心服务
 * <p>
 * 核心职责：
 * 1. 处理聊天消息（写扩散双写 + 路由投递 + 未读计数）
 * 2. 处理已读回执（清零未读 + 通知对方）
 * 3. 处理离线消息（暂存 Redis List + 上线推送 + ACK 确认删除）
 * 4. 会话列表查询 + 历史消息查询
 * </p>
 * <p>
 * 写扩散一致性保证：
 * - A 和 B 的消息使用同一个 conversation_id 做分片键
 * - 同分片下可用本地事务保证 4 个操作（2 条消息 + 2 条会话更新）的原子性
 * - 事务失败则回滚，发送 NACK 给发送者
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatUserRelationMapper chatUserRelationMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final OnlineRouteService onlineRouteService;
    private final MessagePersistService messagePersistService;

    /** 使用 setter 注入打破循环依赖：ChatService ↔ ImWebSocketHandler */
    private ImWebSocketHandler webSocketHandler;

    @org.springframework.beans.factory.annotation.Autowired
    public void setWebSocketHandler(@Lazy ImWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    private static final String UNREAD_KEY_PREFIX = "im:unread:";
    private static final String OFFLINE_KEY_PREFIX = "im:offline:";
    private static final int MAX_OFFLINE_MESSAGES = 1000;

    // ==================== WebSocket 消息处理 ====================

    /**
     * 处理聊天消息
     * <p>
     * 流程：
     * 1. 参数校验
     * 2. 写扩散双写（事务保证原子性）
     * 3. 路由投递（本实例直推 / 跨实例 MQ / 离线暂存）
     * 4. 更新未读计数（Redis Hash HINCRBY）
     * 5. 发送 ACK 给发送者
     * </p>
     */
    public void handleChat(Long senderId, ImMessage imMsg, WebSocketSession senderSession) {
        Long receiverId = imMsg.getTo();
        if (receiverId == null || receiverId.equals(senderId)) {
            sendJson(senderSession, Map.of("ver", 1, "type", "NACK", "reason", "无效的接收者"));
            return;
        }
        String content = imMsg.getContent();
        if (content == null || content.isBlank()) {
            sendJson(senderSession, Map.of("ver", 1, "type", "NACK", "reason", "消息内容不能为空"));
            return;
        }
        if (content.length() > 2000) {
            content = content.substring(0, 2000); // 截断超长消息
        }
        int msgType = imMsg.getMsgType() != null ? imMsg.getMsgType() : 0;

        // 生成消息 ID 和会话 ID
        long msgId = IdWorker.getId();
        long conversationId = generateConversationId(senderId, receiverId);
        LocalDateTime now = LocalDateTime.now();

        // 1. 持久化消息 + 更新会话（通过独立 Bean 调用，确保 @Transactional 生效）
        try {
            messagePersistService.saveMessageWithTransaction(msgId, conversationId, senderId, receiverId, content, msgType, now);
        } catch (Exception e) {
            log.error("[IM] 写扩散双写失败: senderId={}, receiverId={}", senderId, receiverId, e);
            sendJson(senderSession, Map.of("ver", 1, "type", "NACK", "msgId", msgId, "reason", "发送失败，请重试"));
            return;
        }

        // 2. 路由投递
        long timestamp = System.currentTimeMillis();
        String chatJson = JSON.toJSONString(Map.of(
                "ver", 1, "type", "CHAT", "msgId", msgId,
                "from", senderId, "content", content,
                "msgType", msgType, "timestamp", timestamp));

        String targetServerId = onlineRouteService.getRoute(receiverId);
        if (targetServerId != null && targetServerId.equals(onlineRouteService.getServerId())) {
            // 同实例直推
            boolean pushed = webSocketHandler.pushToUser(receiverId, chatJson);
            if (!pushed) {
                // 推送失败（session 已关闭），存离线
                storeOfflineMessage(receiverId, msgId);
            }
        } else if (targetServerId != null) {
            // 跨实例通过 MQ 路由
            RouteMessage routeMsg = RouteMessage.builder()
                    .receiverId(receiverId)
                    .targetServerId(targetServerId)
                    .msgId(msgId)
                    .senderId(senderId)
                    .content(content)
                    .msgType(msgType)
                    .timestamp(timestamp)
                    .build();
            try {
                rocketMQTemplate.convertAndSend("IM_ROUTE_TOPIC", routeMsg);
            } catch (Exception e) {
                log.error("[IM] MQ 路由失败，降级存离线: receiverId={}", receiverId, e);
                storeOfflineMessage(receiverId, msgId);
            }
        } else {
            // 用户不在线，存离线消息
            storeOfflineMessage(receiverId, msgId);
        }

        // 3. 更新未读计数（Redis Hash：一个 Key 管理用户所有会话的未读数）
        try {
            stringRedisTemplate.opsForHash().increment(
                    UNREAD_KEY_PREFIX + receiverId, String.valueOf(senderId), 1);
        } catch (Exception e) {
            log.warn("[IM] 未读计数更新失败: receiverId={}, senderId={}", receiverId, senderId);
        }

        // 4. 发送 ACK 给发送者
        sendJson(senderSession, Map.of("ver", 1, "type", "ACK", "msgId", msgId, "timestamp", timestamp));
    }

    /**
     * 处理消息 ACK（离线消息确认）
     * <p>
     * 使用 ZREM（O(log N)）替代 LREM（O(N)）：
     * 离线消息最多 1000 条时，LREM 需要扫描整个 List（O(1000)），
     * 而 ZREM 利用跳表只需 O(log 1000) ≈ 10 次比较。
     * 高频 ACK 场景下性能差异显著。
     * </p>
     */
    public void handleAck(Long userId, ImMessage imMsg) {
        if (imMsg.getMsgId() == null) return;
        // 从离线 Sorted Set 中删除已确认的消息
        String key = OFFLINE_KEY_PREFIX + userId;
        stringRedisTemplate.opsForZSet().remove(key, String.valueOf(imMsg.getMsgId()));
    }

    /**
     * 处理已读回执
     * <p>
     * 1. 更新 DB 未读数为 0
     * 2. 清零 Redis 未读计数
     * 3. 通知对方"已读"（支持跨实例路由）
     * </p>
     */
    public void handleRead(Long userId, ImMessage imMsg, WebSocketSession session) {
        Long peerId = imMsg.getPeerId();
        if (peerId == null) return;

        // 1. 更新 DB 未读数为 0
        ChatUserRelation relation = chatUserRelationMapper.selectOne(
                new LambdaQueryWrapper<ChatUserRelation>()
                        .eq(ChatUserRelation::getUserId, userId)
                        .eq(ChatUserRelation::getPeerId, peerId));
        if (relation != null && relation.getUnreadCount() > 0) {
            relation.setUnreadCount(0);
            chatUserRelationMapper.updateById(relation);
        }

        // 2. 清零 Redis 未读计数
        stringRedisTemplate.opsForHash().put(UNREAD_KEY_PREFIX + userId, String.valueOf(peerId), "0");

        // 3. 通知对方"已读"（支持跨实例路由，与聊天消息相同的路由逻辑）
        String readNotify = JSON.toJSONString(Map.of(
                "ver", 1, "type", "READ_NOTIFY", "peerId", userId,
                "msgId", imMsg.getMsgId() != null ? imMsg.getMsgId() : 0));

        String targetServerId = onlineRouteService.getRoute(peerId);
        if (targetServerId != null && targetServerId.equals(onlineRouteService.getServerId())) {
            // 同实例直推
            webSocketHandler.pushToUser(peerId, readNotify);
        } else if (targetServerId != null) {
            // 跨实例通过 MQ 路由（复用 IM_ROUTE_TOPIC）
            RouteMessage routeMsg = RouteMessage.builder()
                    .receiverId(peerId)
                    .targetServerId(targetServerId)
                    .msgId(imMsg.getMsgId() != null ? imMsg.getMsgId() : 0L)
                    .senderId(userId)
                    .content(readNotify)
                    .msgType(99) // 99=已读回执，ImRouteConsumer特殊处理
                    .timestamp(System.currentTimeMillis())
                    .build();
            try {
                rocketMQTemplate.convertAndSend("IM_ROUTE_TOPIC", routeMsg);
            } catch (Exception e) {
                log.warn("[IM] 已读回执MQ路由失败: peerId={}", peerId, e);
            }
        }
        // 对方不在线时不需要推送已读回执（下次上线拉取会话列表时会看到最新状态）
    }

    /**
     * 处理输入状态通知
     */
    public void handleTyping(Long senderId, ImMessage imMsg) {
        Long peerId = imMsg.getPeerId();
        if (peerId == null) return;

        String typingJson = JSON.toJSONString(Map.of(
                "ver", 1, "type", "TYPING", "peerId", senderId, "isTyping", true));
        webSocketHandler.pushToUser(peerId, typingJson);
    }

    // ==================== 离线消息 ====================

    /**
     * 暂存离线消息 ID（不存完整消息，节省 Redis 内存）
     * <p>
     * 使用 Sorted Set（score=timestamp），而非 List：
     * - ZADD O(log N)，LREM O(N)
     * - 客户端 ACK 时用 ZREM 删除，比 LREM 快数倍
     * - 天然按时间排序，推送时无需额外排序
     * </p>
     */
    private void storeOfflineMessage(Long userId, long msgId) {
        String key = OFFLINE_KEY_PREFIX + userId;
        double score = System.currentTimeMillis();
        stringRedisTemplate.opsForZSet().add(key, String.valueOf(msgId), score);
        // 限制最大数量（保留最新的 N 条），防止 Redis 内存溢出
        long size = stringRedisTemplate.opsForZSet().size(key);
        if (size > MAX_OFFLINE_MESSAGES) {
            stringRedisTemplate.opsForZSet().removeRange(key, 0, size - MAX_OFFLINE_MESSAGES - 1);
        }
        // 设置 7 天过期
        stringRedisTemplate.expire(key, Duration.ofDays(7));
    }

    /**
     * 用户上线后推送离线消息
     * <p>
     * 从 Redis Sorted Set 获取离线消息 ID 列表（按时间排序）→ 从 DB 查询完整消息 → 批量推送。
     * 推送后不直接删除，等待客户端逐条 ACK 确认后再 ZREM（O(log N)，比 LREM O(N) 快）。
     * </p>
     */
    public void pushOfflineMessages(Long userId, WebSocketSession session) {
        String key = OFFLINE_KEY_PREFIX + userId;
        Set<String> msgIdStrs = stringRedisTemplate.opsForZSet().range(key, 0, MAX_OFFLINE_MESSAGES - 1);
        if (msgIdStrs == null || msgIdStrs.isEmpty()) return;

        List<Long> msgIds = msgIdStrs.stream().map(Long::valueOf).collect(Collectors.toList());

        // 从 DB 批量查询消息详情
        List<ChatMessage> messages = chatMessageMapper.selectBatchIds(msgIds);
        if (messages.isEmpty()) return;

        // 构建离线消息推送
        List<Map<String, Object>> msgList = messages.stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("msgId", m.getId());
                    map.put("from", m.getSenderId());
                    map.put("content", m.getContent());
                    map.put("msgType", m.getMsgType());
                    map.put("timestamp", m.getCreatedAt() != null
                            ? m.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : 0);
                    return map;
                })
                .collect(Collectors.toList());

        String batchJson = JSON.toJSONString(Map.of(
                "ver", 1, "type", "OFFLINE", "msgs", msgList, "total", msgList.size()));

        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(batchJson));
            }
        } catch (IOException e) {
            log.warn("[IM] 离线消息推送失败: userId={}", userId, e);
        }

        log.info("[IM] 推送离线消息: userId={}, count={}", userId, msgList.size());
    }

    // ==================== REST API 查询 ====================

    /**
     * 获取会话列表
     */
    public Page<ChatUserRelation> getConversationList(Long userId, int page, int size) {
        return chatUserRelationMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<ChatUserRelation>()
                        .eq(ChatUserRelation::getUserId, userId)
                        .eq(ChatUserRelation::getIsDeleted, 0)
                        .orderByDesc(ChatUserRelation::getUpdatedAt));
    }

    /**
     * 获取与某人的聊天记录（按时间倒序分页）
     */
    public Page<ChatMessage> getMessageHistory(Long userId, Long peerId, int page, int size) {
        long conversationId = generateConversationId(userId, peerId);
        return chatMessageMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByDesc(ChatMessage::getCreatedAt));
    }

    /**
     * 标记与某人的消息全部已读
     */
    public void markAllRead(Long userId, Long peerId) {
        // 1. 更新 DB 未读数为 0
        ChatUserRelation relation = chatUserRelationMapper.selectOne(
                new LambdaQueryWrapper<ChatUserRelation>()
                        .eq(ChatUserRelation::getUserId, userId)
                        .eq(ChatUserRelation::getPeerId, peerId));
        if (relation != null && relation.getUnreadCount() > 0) {
            relation.setUnreadCount(0);
            chatUserRelationMapper.updateById(relation);
        }

        // 2. 清零 Redis 未读计数
        stringRedisTemplate.opsForHash().put(UNREAD_KEY_PREFIX + userId, String.valueOf(peerId), "0");
    }

    /**
     * 获取总未读消息数
     */
    public int getTotalUnreadCount(Long userId) {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(UNREAD_KEY_PREFIX + userId);
        if (entries.isEmpty()) {
            // Redis 缓存不存在，从 DB 查询
            return getUnreadCountFromDb(userId);
        }
        return entries.values().stream()
                .mapToInt(v -> {
                    try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
                })
                .sum();
    }

    /**
     * 从 DB 查询未读总数（Redis 降级时使用）
     */
    private int getUnreadCountFromDb(Long userId) {
        List<ChatUserRelation> relations = chatUserRelationMapper.selectList(
                new LambdaQueryWrapper<ChatUserRelation>()
                        .eq(ChatUserRelation::getUserId, userId)
                        .eq(ChatUserRelation::getIsDeleted, 0)
                        .gt(ChatUserRelation::getUnreadCount, 0));
        return relations.stream().mapToInt(ChatUserRelation::getUnreadCount).sum();
    }

    // ==================== 工具方法 ====================

    /**
     * 生成会话 ID：min(A,B) << 32 | max(A,B)
     * <p>
     * 保证确定性：无论 A 发消息还是 B 发消息，conversation_id 相同。
     * 用于分片键，保证同一会话的消息落在同一分片。
     * </p>
     */
    public static long generateConversationId(Long userIdA, Long userIdB) {
        long min = Math.min(userIdA, userIdB);
        long max = Math.max(userIdA, userIdB);
        return (min << 32) | max;
    }

    private void sendJson(WebSocketSession session, Map<String, Object> data) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(JSON.toJSONString(data)));
            }
        } catch (IOException e) {
            log.warn("[IM] 发送消息失败: sessionId={}", session.getId());
        }
    }
}
