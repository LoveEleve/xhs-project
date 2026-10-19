package com.myxhs.im.service;

import com.myxhs.im.entity.ChatMessage;
import com.myxhs.im.entity.ChatUserRelation;
import com.myxhs.im.mapper.ChatMessageMapper;
import com.myxhs.im.mapper.ChatUserRelationMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息持久化服务单测（C-013）：消息落库 + 双方会话 upsert 三路径（insert / DuplicateKey 降级 / affected=0 补插）。
 */
class MessagePersistServiceTest {

    private ChatMessageMapper chatMessageMapper;
    private ChatUserRelationMapper chatUserRelationMapper;
    private MessagePersistService service;

    @BeforeAll
    static void initMpLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                ChatUserRelation.class);
    }

    @BeforeEach
    void setUp() {
        chatMessageMapper = mock(ChatMessageMapper.class);
        chatUserRelationMapper = mock(ChatUserRelationMapper.class);
        service = new MessagePersistService(chatMessageMapper, chatUserRelationMapper);
    }

    @Test
    @DisplayName("正常：1 消息 INSERT + 双方会话 INSERT（发送者 unread=0 / 接收者 unread=1）")
    void saveInsertsMessageAndBothRelations() {
        when(chatUserRelationMapper.selectOne(any())).thenReturn(null);

        service.saveMessageWithTransaction(500L, 700L, 9L, 10L, "hello", 0, 1L, LocalDateTime.now());

        ArgumentCaptor<ChatMessage> msgCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageMapper).insert(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getId()).isEqualTo(500L);
        assertThat(msgCaptor.getValue().getSeqNo()).isEqualTo(1L);

        ArgumentCaptor<ChatUserRelation> relCaptor = ArgumentCaptor.forClass(ChatUserRelation.class);
        verify(chatUserRelationMapper, times(2)).insert(relCaptor.capture());
        assertThat(relCaptor.getAllValues()).extracting(ChatUserRelation::getUnreadCount)
                .containsExactly(0, 1); // 发送者 0 / 接收者 1
    }

    @Test
    @DisplayName("并发首条：insert 撞唯一键 → 降级定向更新")
    void duplicateKeyFallsBackToUpdate() {
        when(chatUserRelationMapper.selectOne(any())).thenReturn(null);
        when(chatUserRelationMapper.insert(any(ChatUserRelation.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        when(chatUserRelationMapper.updateOnNewMessage(anyLong(), anyLong(), anyLong(), anyString(),
                anyInt(), anyInt(), any())).thenReturn(1);

        service.saveMessageWithTransaction(500L, 700L, 9L, 10L, "hello", 0, 1L, LocalDateTime.now());

        verify(chatUserRelationMapper, times(2)).updateOnNewMessage(anyLong(), anyLong(), anyLong(),
                anyString(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("极端竞态：定向更新 affected=0 → 补插一次")
    void zeroAffectedReinserts() {
        when(chatUserRelationMapper.selectOne(any())).thenReturn(null);
        when(chatUserRelationMapper.insert(any(ChatUserRelation.class)))
                .thenThrow(new DuplicateKeyException("dup"));
        when(chatUserRelationMapper.updateOnNewMessage(anyLong(), anyLong(), anyLong(), anyString(),
                anyInt(), anyInt(), any())).thenReturn(0);

        service.saveMessageWithTransaction(500L, 700L, 9L, 10L, "hello", 0, 1L, LocalDateTime.now());

        // 每方：首次 insert（撞键）+ 补插 = 2 次
        verify(chatUserRelationMapper, times(4)).insert(any(ChatUserRelation.class));
    }

    @Test
    @DisplayName("lastContent 截断：>100 字符 → 100 + \"...\"")
    void lastContentTruncated() {
        when(chatUserRelationMapper.selectOne(any())).thenReturn(null);
        String longContent = "x".repeat(150);

        service.saveMessageWithTransaction(500L, 700L, 9L, 10L, longContent, 0, 1L, LocalDateTime.now());

        ArgumentCaptor<ChatUserRelation> relCaptor = ArgumentCaptor.forClass(ChatUserRelation.class);
        verify(chatUserRelationMapper, times(2)).insert(relCaptor.capture());
        assertThat(relCaptor.getValue().getLastContent()).hasSize(103).endsWith("...");
    }
}
