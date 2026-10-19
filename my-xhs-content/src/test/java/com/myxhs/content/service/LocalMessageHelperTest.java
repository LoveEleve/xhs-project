package com.myxhs.content.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.content.entity.LocalMessage;
import com.myxhs.content.mapper.LocalMessageMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LocalMessageHelper 单测（C-010：同事务入队 + afterCommit 发送 + 成功标记）
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalMessageHelperTest {

    @Mock private LocalMessageMapper localMessageMapper;
    @Mock private RocketMQTemplate rocketMQTemplate;

    private LocalMessageHelper helper;

    @BeforeEach
    void setUp() {
        helper = new LocalMessageHelper(localMessageMapper, rocketMQTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("C-010：非事务调用 → 抛出（保证业务与消息同事务）")
    void enqueueOutsideTransactionThrows() {
        try (MockedStatic<TransactionSynchronizationManager> ts =
                     mockStatic(TransactionSynchronizationManager.class)) {
            ts.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(false);
            assertThatThrownBy(() -> helper.enqueue("FEED_TOPIC", id -> Map.of("noteId", 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("必须在事务内");
        }
    }

    @Test
    @DisplayName("C-010：事务内入队 → 占位插入 + 回填 body + afterCommit 发送 + markSent")
    void enqueueInsertsBackfillsAndSendsAfterCommit() {
        try (MockedStatic<TransactionSynchronizationManager> ts =
                     mockStatic(TransactionSynchronizationManager.class)) {
            ts.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            ArgumentCaptor<TransactionSynchronization> syncCaptor =
                    ArgumentCaptor.forClass(TransactionSynchronization.class);
            ts.when(() -> TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()))
                    .thenAnswer(invocation -> null);
            java.util.List<String> insertedBodies = new java.util.ArrayList<>();
            doAnswer(invocation -> {
                LocalMessage m = invocation.getArgument(0);
                insertedBodies.add(m.getBody()); // 时点记录（对象后续被回填修改）
                m.setId(9L);
                return 1;
            }).when(localMessageMapper).insert(any(LocalMessage.class));

            helper.enqueue("FEED_TOPIC", id -> Map.of("localMsgId", id));

            // 事务内：插入占位（"{}"）+ 回填 body
            assertThat(insertedBodies).containsExactly("{}");
            ArgumentCaptor<LocalMessage> rowCaptor = ArgumentCaptor.forClass(LocalMessage.class);
            verify(localMessageMapper).updateById(rowCaptor.capture());
            assertThat(rowCaptor.getValue().getBody()).contains("\"localMsgId\":9");

            // afterCommit：发送（Message 重载）+ 成功回调 → markSent
            syncCaptor.getValue().afterCommit();
            ArgumentCaptor<org.springframework.messaging.Message<?>> msgCaptor =
                    ArgumentCaptor.forClass(org.springframework.messaging.Message.class);
            ArgumentCaptor<org.apache.rocketmq.client.producer.SendCallback> cbCaptor =
                    ArgumentCaptor.forClass(org.apache.rocketmq.client.producer.SendCallback.class);
            verify(rocketMQTemplate).asyncSend(eq("FEED_TOPIC"), msgCaptor.capture(), cbCaptor.capture());
            assertThat(msgCaptor.getValue().getPayload().toString()).contains("localMsgId");
            cbCaptor.getValue().onSuccess(null);
            verify(localMessageMapper).markSent(9L);
        }
    }

    @Test
    @DisplayName("C-010：afterCommit 发送异常 → 不抛出（行保留 → 补发接管）")
    void enqueueSendFailureKeepsRow() {
        try (MockedStatic<TransactionSynchronizationManager> ts =
                     mockStatic(TransactionSynchronizationManager.class)) {
            ts.when(TransactionSynchronizationManager::isSynchronizationActive).thenReturn(true);
            ArgumentCaptor<TransactionSynchronization> syncCaptor =
                    ArgumentCaptor.forClass(TransactionSynchronization.class);
            ts.when(() -> TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()))
                    .thenAnswer(invocation -> null);
            doAnswer(invocation -> { LocalMessage m = invocation.getArgument(0); m.setId(9L); return 1; })
                    .when(localMessageMapper).insert(any(LocalMessage.class));
            doThrow(new RuntimeException("broker down"))
                    .when(rocketMQTemplate).asyncSend(anyString(), any(org.springframework.messaging.Message.class), any());

            helper.enqueue("SOCIAL_TOPIC:UNCOMMENT", id -> Map.of("noteId", 1));

            // afterCommit 内异常被吞（日志），不向调用方抛出
            syncCaptor.getValue().afterCommit();
            verify(localMessageMapper, never()).markSent(anyLong());
        }
    }
}
