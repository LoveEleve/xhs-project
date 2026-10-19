package com.myxhs.inventory.job;

import com.myxhs.inventory.dto.request.ConfirmDeductRequest;
import com.myxhs.inventory.mapper.InventoryMapper;
import com.myxhs.inventory.service.InventoryService;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PreDeductTimeoutJob 单元测试（C-008：per-sku 索引扫描 + 确认标记改判）
 * <p>
 * 重点：到期记录释放前先查"确认标记"——已付款订单的迟到预扣记录
 * （支付确认早于预扣落）补确认而非释放（防已付款订单库存被放回，超卖方向）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreDeductTimeoutJobTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private DefaultRedisScript<Long> releaseScript;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock rLock;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private Cursor<String> cursor;

    private PreDeductTimeoutJob job;

    private static final String INDEX_KEY = "inventory:prededuct:index:{10001}";

    @BeforeEach
    void setUp() throws InterruptedException {
        job = new PreDeductTimeoutJob(stringRedisTemplate, releaseScript, redissonClient,
                rocketMQTemplate, objectMapper, inventoryMapper, inventoryService);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        // C-008：SCAN per-sku 索引（单键：inventory:prededuct:index:{skuId}）
        when(stringRedisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> consumer = inv.getArgument(0);
            consumer.accept(INDEX_KEY);
            return null;
        }).when(cursor).forEachRemaining(any());
    }

    @Test
    @DisplayName("超时扫描 - 已确认订单的迟到预扣记录：按 (sku,order) 补确认而非释放")
    void expiredRecord_withConfirmedMarker_settlesAsConfirm() {
        when(zSetOperations.rangeByScore(eq(INDEX_KEY), anyDouble(), anyDouble()))
                .thenReturn(Set.of("123456"));
        when(inventoryService.hasConfirmedMarker(123456L)).thenReturn(true);

        job.releaseExpiredPreDeductions();

        // C-008：补确认请求携带单项 items（skuId 从索引键解析）
        ArgumentCaptor<ConfirmDeductRequest> captor = ArgumentCaptor.forClass(ConfirmDeductRequest.class);
        verify(inventoryService).confirmDeduct(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(123456L);
        assertThat(captor.getValue().getItems()).hasSize(1);
        assertThat(captor.getValue().getItems().get(0).getSkuId()).isEqualTo(10001L);
        // 绝不释放
        verify(stringRedisTemplate, never()).execute(eq(releaseScript), anyList(), anyString());
    }

    @Test
    @DisplayName("超时扫描 - 无确认标记（真弃单）：按 (sku,order) 释放 + RELEASE 事件")
    void expiredRecord_withoutConfirmedMarker_releases() {
        when(zSetOperations.rangeByScore(eq(INDEX_KEY), anyDouble(), anyDouble()))
                .thenReturn(Set.of("123456"));
        when(inventoryService.hasConfirmedMarker(123456L)).thenReturn(false);
        when(stringRedisTemplate.execute(eq(releaseScript), anyList(), anyString()))
                .thenReturn(2L);
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (Exception ignored) {
        }
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:RELEASE"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        job.releaseExpiredPreDeductions();

        // 单键家族：KEYS 同 {skuId} tag，1 个 ARGV=orderId
        verify(stringRedisTemplate).execute(
                eq(releaseScript),
                argThat(keys -> keys.get(0).equals("inventory:{10001}:stock")
                        && keys.get(1).equals("inventory:{10001}:prededuct:123456")
                        && keys.get(2).equals("inventory:{10001}:prededuct:index")),
                eq("123456"));
        verify(inventoryService, never()).confirmDeduct(any());
    }
}
