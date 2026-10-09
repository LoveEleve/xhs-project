package com.myxhs.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.myxhs.im.entity.ChatMessage;
import com.myxhs.im.entity.ChatUserRelation;
import com.myxhs.im.mapper.ChatMessageMapper;
import com.myxhs.im.mapper.ChatUserRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 消息持久化服务（独立类，解决 @Transactional 自调用失效问题）
 * <p>
 * 为什么要独立出来？
 * Spring AOP 基于代理实现，同一个类内部方法调用不会经过代理，
 * 导致 @Transactional 注解不生效。将事务方法抽取到独立 Bean，
 * 由 ChatService 通过代理调用，确保事务生效。
 * </p>
 * <p>
 * 写扩散说明：
 * 当前实现是"共享存储"模式——同一条消息只写一份，按 conversation_id 分片。
 * 这比真正的写扩散（A 和 B 各写一份）更高效，因为私信场景下查询都是按 conversation_id，
 * 一份数据即可满足双方查询需求。如果未来需要"A 删消息不影响 B"，再引入消息删除标记表。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessagePersistService {

    private final ChatMessageMapper chatMessageMapper;
    private final ChatUserRelationMapper chatUserRelationMapper;

    /**
     * 持久化消息 + 更新双方会话（事务保证原子性）
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveMessageWithTransaction(long msgId, long conversationId,
                                           Long senderId, Long receiverId,
                                           String content, int msgType, long seqNo, LocalDateTime now) {
        // 1. 插入消息（按 conversation_id 分片，同一会话的消息在同一分片）
        ChatMessage message = ChatMessage.builder()
                .id(msgId)
                .conversationId(conversationId)
                .senderId(senderId)
                .receiverId(receiverId)
                .content(content)
                .msgType(msgType)
                .seqNo(seqNo)
                .createdAt(now)
                .build();
        chatMessageMapper.insert(message);

        // 2. 更新发送者会话
        String truncatedContent = content.length() > 100 ? content.substring(0, 100) + "..." : content;
        upsertConversation(senderId, receiverId, conversationId, msgId, truncatedContent, msgType, 0, now);

        // 3. 更新接收者会话（未读数 +1）
        upsertConversation(receiverId, senderId, conversationId, msgId, truncatedContent, msgType, 1, now);
    }

    /**
     * 更新或创建会话记录（UPSERT 语义）
     */
    private void upsertConversation(Long userId, Long peerId, long conversationId,
                                    long msgId, String content, int msgType,
                                    int unreadIncrement, LocalDateTime now) {
        ChatUserRelation relation = chatUserRelationMapper.selectOne(
                new LambdaQueryWrapper<ChatUserRelation>()
                        .eq(ChatUserRelation::getUserId, userId)
                        .eq(ChatUserRelation::getPeerId, peerId));

        if (relation == null) {
            try {
                chatUserRelationMapper.insert(buildRelation(userId, peerId, conversationId,
                        msgId, content, msgType, unreadIncrement, now));
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首条消息：uk_user_peer 已被另一事务插入 → 降级为定向更新
                log.info("[IM] 会话并发创建, 降级定向更新: userId={}, peerId={}", userId, peerId);
            }
        }

        // 定向更新：unread_count 原子自增（不整行覆盖 → 不丢未读、不回退 lastMessage）
        int updated = chatUserRelationMapper.updateOnNewMessage(
                userId, peerId, msgId, content, msgType, unreadIncrement, now);
        if (updated == 0) {
            // 极端竞态：并发插入方随后回滚，行不存在 → 补插一次（再撞唯一键则说明已被插入，忽略）
            try {
                chatUserRelationMapper.insert(buildRelation(userId, peerId, conversationId,
                        msgId, content, msgType, unreadIncrement, now));
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                log.debug("[IM] 会话补插撞唯一键(已被并发事务插入): userId={}, peerId={}", userId, peerId);
            }
        }
    }

    private ChatUserRelation buildRelation(Long userId, Long peerId, long conversationId,
                                           long msgId, String content, int msgType,
                                           int unreadIncrement, LocalDateTime now) {
        return ChatUserRelation.builder()
                .id(IdWorker.getId())
                .userId(userId)
                .peerId(peerId)
                .conversationId(conversationId)
                .lastMessageId(msgId)
                .lastContent(content)
                .lastMsgType(msgType)
                .unreadCount(unreadIncrement)
                .isDeleted(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
