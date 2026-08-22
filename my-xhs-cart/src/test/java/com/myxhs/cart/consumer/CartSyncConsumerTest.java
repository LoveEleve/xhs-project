package com.myxhs.cart.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxhs.cart.dto.event.CartSyncEvent;
import com.myxhs.cart.entity.CartItem;
import com.myxhs.cart.mapper.CartItemMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartSyncConsumerTest {

    @Mock
    private CartItemMapper cartItemMapper;
    @Mock
    private IdGeneratorUtil idGeneratorUtil;

    private ObjectMapper objectMapper;
    private CartSyncConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        consumer = new CartSyncConsumer(cartItemMapper, idGeneratorUtil, objectMapper);
    }

    @Test
    @DisplayName("CLEAR 之后的旧 ADD 事件应被屏障拦截")
    void oldAddEventShouldBeBlockedByClearBarrier() throws Exception {
        long userId = 1001L;
        long skuId = 2001L;
        Instant clearTs = Instant.now();

        CartSyncEvent clear = CartSyncEvent.builder()
                .userId(userId)
                .action("CLEAR")
                .quantity(0)
                .checked(0)
                .build();
        clear.setTimestamp(clearTs);
        clear.setClearBarrierTs(clearTs.toEpochMilli());
        consumer.onMessage(buildMsg(clear));

        CartSyncEvent oldAdd = CartSyncEvent.builder()
                .userId(userId)
                .skuId(skuId)
                .quantity(2)
                .checked(1)
                .action("ADD")
                .build();
        oldAdd.setTimestamp(clearTs.minusMillis(1));
        consumer.onMessage(buildMsg(oldAdd));

        verify(cartItemMapper, never()).insert(any(CartItem.class));
    }

    @Test
    @DisplayName("CLEAR 之后的新 ADD 事件仍可正常写入")
    void newAddEventAfterClearShouldPass() throws Exception {
        long userId = 1002L;
        long skuId = 2002L;
        Instant clearTs = Instant.now();

        CartSyncEvent clear = CartSyncEvent.builder()
                .userId(userId)
                .action("CLEAR")
                .quantity(0)
                .checked(0)
                .build();
        clear.setTimestamp(clearTs);
        clear.setClearBarrierTs(clearTs.toEpochMilli());
        consumer.onMessage(buildMsg(clear));

        when(idGeneratorUtil.nextId()).thenReturn(1L);
        CartSyncEvent newAdd = CartSyncEvent.builder()
                .userId(userId)
                .skuId(skuId)
                .quantity(2)
                .checked(1)
                .action("ADD")
                .build();
        newAdd.setTimestamp(clearTs.plusMillis(1));
        consumer.onMessage(buildMsg(newAdd));

        verify(cartItemMapper).insert(any(CartItem.class));
    }

    private MessageExt buildMsg(CartSyncEvent event) throws Exception {
        MessageExt msg = new MessageExt();
        msg.setMsgId("msg-" + System.nanoTime());
        msg.setBody(objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
        return msg;
    }
}
