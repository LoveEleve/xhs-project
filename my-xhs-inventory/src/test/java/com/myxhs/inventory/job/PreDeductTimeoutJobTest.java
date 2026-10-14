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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PreDeductTimeoutJob 单元测试（04B 收尾：确认标记改判）
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
    private HashOperations<String, Object, Object> hashOperations;

    private PreDeductTimeoutJob job;

    @BeforeEach
    void setUp() {
        job = new PreDeductTimeoutJob(stringRedisTemplate, releaseScript, redissonClient,
                rocketMQTemplate, objectMapper, inventoryMapper, inventoryService);
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("超时扫描 - 已确认订单的迟到预扣记录：补确认而非释放")
    void expiredRecord_withConfirmedMarker_settlesAsConfirm() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(zSetOperations.rangeByScore(eq("inventory:prededuct:index"), anyDouble(), anyDouble()))
                .thenReturn(Set.of("123456"));
        when(inventoryService.hasConfirmedMarker(123456L)).thenReturn(true);

        job.releaseExpiredPreDeductions();

        verify(inventoryService).confirmDeduct(any(ConfirmDeductRequest.class));
        verify(stringRedisTemplate, never()).execute(eq(releaseScript), anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("超时扫描 - 无确认标记（真弃单）：按原逻辑释放")
    void expiredRecord_withoutConfirmedMarker_releases() throws Exception {
        when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(zSetOperations.rangeByScore(eq("inventory:prededuct:index"), anyDouble(), anyDouble()))
                .thenReturn(Set.of("123456"));
        when(inventoryService.hasConfirmedMarker(123456L)).thenReturn(false);
        Map<Object, Object> entries = new HashMap<>();
        entries.put("10001", "2");
        when(hashOperations.entries("inventory:prededuct:123456")).thenReturn(entries);
        when(hashOperations.get("inventory:prededuct:123456", "10001:bucket")).thenReturn("0");
        when(stringRedisTemplate.execute(eq(releaseScript), anyList(), anyString(), anyString()))
                .thenReturn(1L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:RELEASE"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        job.releaseExpiredPreDeductions();

        verify(stringRedisTemplate).execute(eq(releaseScript), anyList(), eq("10001"), eq("123456"));
        verify(inventoryService, never()).confirmDeduct(any());
    }
}
