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
                                           String content, int msgType, LocalDateTime now) {
        // 1. 插入消息（按 conversation_id 分片，同一会话的消息在同一分片）
        ChatMessage message = ChatMessage.builder()
                .id(msgId)
                .conversationId(conversationId)
                .senderId(senderId)
                .receiverId(receiverId)
                .content(content)
                .msgType(msgType)
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
            relation = ChatUserRelation.builder()
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
            chatUserRelationMapper.insert(relation);
        } else {
            relation.setLastMessageId(msgId);
            relation.setLastContent(content);
            relation.setLastMsgType(msgType);
            relation.setUnreadCount(relation.getUnreadCount() + unreadIncrement);
            relation.setUpdatedAt(now);
            if (relation.getIsDeleted() == 1) {
                relation.setIsDeleted(0); // 重新激活已删除的会话
            }
            chatUserRelationMapper.updateById(relation);
        }
    }
}
