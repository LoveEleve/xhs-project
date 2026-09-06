package com.myxhs.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.ResultCode;
import com.myxhs.inventory.dto.request.ConfirmDeductRequest;
import com.myxhs.inventory.dto.request.InventoryInitRequest;
import com.myxhs.inventory.dto.request.PreDeductRequest;
import com.myxhs.inventory.dto.request.RefundRestoreRequest;
import com.myxhs.inventory.dto.request.ReleaseStockRequest;
import com.myxhs.inventory.entity.Inventory;
import com.myxhs.inventory.hot.HotSkuDetector;
import com.myxhs.inventory.mapper.InventoryMapper;
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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.redisson.api.RedissonClient;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * InventoryService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：StringRedisTemplate, RocketMQTemplate, InventoryMapper,
 * DefaultRedisScript, BusinessMetrics, HotSkuDetector
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private InventoryMapper inventoryMapper;
    @Mock
    private DefaultRedisScript<Long> preDeductScript;
    @Mock
    private DefaultRedisScript<Long> releaseScript;
    @Mock
    private DefaultRedisScript<Long> confirmScript;
    @Mock
    private BusinessMetrics businessMetrics;
    @Mock
    private HotSkuDetector hotSkuDetector;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private ObjectMapper objectMapper;
    private InventoryService inventoryService;

    private static final Long SKU_ID = 10001L;
    private static final Long ORDER_ID = 123456L;
    private static final Long USER_ID = 1001L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        inventoryService = new InventoryService(
                stringRedisTemplate, rocketMQTemplate, inventoryMapper, objectMapper,
                preDeductScript, releaseScript, confirmScript, businessMetrics, redissonClient, hotSkuDetector,
                mock(com.myxhs.inventory.feign.ProductFeignClient.class)
        );
        // 注入 @Value 字段（非 final，不在 Lombok 构造函数中）
        ReflectionTestUtils.setField(inventoryService, "defaultBucketCount", 2);
        ReflectionTestUtils.setField(inventoryService, "hotBucketCount", 8);
        ReflectionTestUtils.setField(inventoryService, "preDeductExpireSeconds", 1800);

        // 默认 mock stringRedisTemplate 返回操作接口
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    // ==================== initStock ====================

    @Test
    @DisplayName("库存初始化 - 正常初始化设置可用库存正确")
    void initStockSuccess() {
        // SETNX 幂等检查成功
        when(valueOperations.setIfAbsent(
                eq("inventory:init:lock:10001"), eq("1"), any(java.time.Duration.class)))
                .thenReturn(true);
        // MySQL 中无已有记录
        when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);

        InventoryInitRequest request = new InventoryInitRequest();
        request.setSkuId(SKU_ID);
        request.setTotalStock(100);

        inventoryService.initStock(request);

        // 验证初始化锁被调用
        verify(valueOperations).setIfAbsent(eq("inventory:init:lock:10001"), eq("1"), any(java.time.Duration.class));
        // 验证分桶写入：100 / 2 = 50
        verify(valueOperations).set(eq("inventory:{10001}:bucket:0"), eq("50"));
        verify(valueOperations).set(eq("inventory:{10001}:bucket:1"), eq("50"));
        // 验证 totalKey 和 bucketCount 写入
        verify(valueOperations).set(eq("inventory:{10001}:total"), eq("100"));
        verify(valueOperations).set(eq("inventory:bucket:count:{10001}"), eq("2"));
        // 验证 MySQL 记录创建
        verify(inventoryMapper).insert(any(Inventory.class));
    }

    @Test
    @DisplayName("库存初始化 - 重复初始化不会加倍库存，应抛出异常")
    void initStockDuplicatePrevention() {
        // 获得初始化锁后，两个完整初始化标记均存在
        when(valueOperations.setIfAbsent(
                eq("inventory:init:lock:10001"), eq("1"), any(java.time.Duration.class)))
                .thenReturn(true);
        when(stringRedisTemplate.hasKey("inventory:{10001}:total")).thenReturn(true);
        when(stringRedisTemplate.hasKey("inventory:bucket:count:{10001}")).thenReturn(true);

        InventoryInitRequest request = new InventoryInitRequest();
        request.setSkuId(SKU_ID);
        request.setTotalStock(100);

        assertThatThrownBy(() -> inventoryService.initStock(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存已初始化");

        // 验证已初始化标记阻止重复初始化
        verify(valueOperations).setIfAbsent(eq("inventory:init:lock:10001"), eq("1"), any(java.time.Duration.class));
        // 不应进入后续的 MySQL 和分桶操作
        verify(inventoryMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    // ==================== preDeduct ====================

    @Test
    @DisplayName("预扣减 - 正常扣减库存成功")
    void preDeductSuccess() {
        when(inventoryMapper.insertPredeductIdem(ORDER_ID, SKU_ID)).thenReturn(1);
        // 未在扩容暂停中
        when(stringRedisTemplate.hasKey(eq("inventory:paused:10001"))).thenReturn(false);
        // 分桶计数 Key 存在，值为 2
        when(valueOperations.get(eq("inventory:bucket:count:{10001}"))).thenReturn("2");
        // 非热点 SKU
        when(hotSkuDetector.recordAndCheck(SKU_ID)).thenReturn(false);
        // Lua 预扣脚本返回 1（成功）
        when(stringRedisTemplate.execute(
                eq(preDeductScript), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        // MQ 发送成功（ObjectMapper 使用真实实例序列化，只 mock syncSend）
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(
                eq("INVENTORY_TOPIC:PRE_DEDUCT"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        PreDeductRequest request = new PreDeductRequest();
        request.setSkuId(SKU_ID);
        request.setOrderId(ORDER_ID);
        request.setQuantity(2);
        request.setUserId(USER_ID);

        assertThatCode(() -> inventoryService.preDeduct(request))
                .doesNotThrowAnyException();

        // 验证 MQ 消息已发送
        verify(inventoryMapper).insertPredeductIdem(ORDER_ID, SKU_ID);
    }

    @Test
    @DisplayName("预扣减 - 库存不足时抛出业务异常")
    void preDeductInsufficientStock() {
        when(inventoryMapper.insertPredeductIdem(ORDER_ID, SKU_ID)).thenReturn(1);
        // 未在扩容暂停中
        when(stringRedisTemplate.hasKey(eq("inventory:paused:10001"))).thenReturn(false);
        // 分桶计数 Key 存在
        when(valueOperations.get(eq("inventory:bucket:count:{10001}"))).thenReturn("2");
        // 非热点 SKU
        when(hotSkuDetector.recordAndCheck(SKU_ID)).thenReturn(false);
        // Lua 预扣脚本返回 0（库存不足）
        when(stringRedisTemplate.execute(
                eq(preDeductScript), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        PreDeductRequest request = new PreDeductRequest();
        request.setSkuId(SKU_ID);
        request.setOrderId(ORDER_ID);
        request.setQuantity(999);
        request.setUserId(USER_ID);

        assertThatThrownBy(() -> inventoryService.preDeduct(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ResultCode.STOCK_NOT_ENOUGH.getMessage());

        // 库存不足时不应发送 MQ
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class), anyLong());
    }

    // ==================== confirmDeduct ====================

    @Test
    @DisplayName("确认扣减 - MQ发送失败时保留 outbox 待补发")
    void confirmDeduct_keepsOutboxWhenMqSendFails() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("10001", "3");
        when(hashOperations.entries(eq("inventory:prededuct:123456"))).thenReturn(entries);
        when(stringRedisTemplate.hasKey(eq("inventory:paused:10001"))).thenReturn(false);
        when(stringRedisTemplate.execute(eq(confirmScript), anyList(), anyString(), anyString())).thenReturn(1L);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:CONFIRM"), any(Message.class), eq(3000L)))
                .thenThrow(new RuntimeException("mq down"));

        ConfirmDeductRequest request = new ConfirmDeductRequest();
        request.setOrderId(ORDER_ID);

        assertThatCode(() -> inventoryService.confirmDeduct(request)).doesNotThrowAnyException();

        verify(inventoryMapper).insertOutboxEvent(anyLong(), eq(ORDER_ID), eq(SKU_ID), eq(3), eq("CONFIRM"));
        verify(inventoryMapper, never()).cancelOutboxEvent(anyLong());
    }

    @Test
    @DisplayName("预扣减 - MQ发送失败时取消 outbox 避免补发已回滚事件")
    void preDeduct_cancelsOutboxWhenMqSendFails() {
        when(inventoryMapper.insertPredeductIdem(ORDER_ID, SKU_ID)).thenReturn(1);
        when(stringRedisTemplate.hasKey(eq("inventory:paused:10001"))).thenReturn(false);
        when(valueOperations.get(eq("inventory:bucket:count:{10001}"))).thenReturn("2");
        when(hotSkuDetector.recordAndCheck(SKU_ID)).thenReturn(false);
        when(stringRedisTemplate.execute(eq(preDeductScript), anyList(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:PRE_DEDUCT"), any(Message.class), eq(3000L)))
                .thenThrow(new RuntimeException("mq down"));
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(1L);

        PreDeductRequest request = new PreDeductRequest();
        request.setSkuId(SKU_ID);
        request.setOrderId(ORDER_ID);
        request.setQuantity(2);
        request.setUserId(USER_ID);

        assertThatThrownBy(() -> inventoryService.preDeduct(request))
                .isInstanceOf(BizException.class);

        verify(inventoryMapper).cancelOutboxEvent(anyLong());
    }

    // ==================== releaseStock ====================

    @Test
    @DisplayName("释放库存 - 预扣后释放恢复可用库存")
    void releaseStock() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("10001", "5");
        when(hashOperations.entries(eq("inventory:prededuct:123456")))
                .thenReturn(entries);
        when(hashOperations.get(eq("inventory:prededuct:123456"), eq("10001:bucket")))
                .thenReturn("0");
        when(stringRedisTemplate.execute(
                eq(releaseScript), anyList(), anyString(), anyString()))
                .thenReturn(1L);
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(
                eq("INVENTORY_TOPIC:RELEASE"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        ReleaseStockRequest request = new ReleaseStockRequest();
        request.setOrderId(ORDER_ID);

        assertThatCode(() -> inventoryService.releaseStock(request))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(
                eq(releaseScript), anyList(), eq("10001"), eq(String.valueOf(ORDER_ID)));
        verify(rocketMQTemplate).syncSend(
                eq("INVENTORY_TOPIC:RELEASE"), any(Message.class), eq(3000L));
    }

    @Test
    @DisplayName("退款回补 - 多 SKU 使用独立幂等键")
    void refundRestore_multiSkuUsesIndependentIdempotentKeys() {
        when(valueOperations.setIfAbsent(eq("inventory:refund:123456:10001"), eq("1"), any(java.time.Duration.class)))
                .thenReturn(true);
        when(valueOperations.setIfAbsent(eq("inventory:refund:123456:10002"), eq("1"), any(java.time.Duration.class)))
                .thenReturn(true);
        when(stringRedisTemplate.hasKey(eq("inventory:{10001}:total"))).thenReturn(true);
        when(stringRedisTemplate.hasKey(eq("inventory:{10002}:total"))).thenReturn(true);
        when(valueOperations.get(eq("inventory:bucket:count:{10001}"))).thenReturn("2");
        when(valueOperations.get(eq("inventory:bucket:count:{10002}"))).thenReturn("2");
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString())).thenReturn(2L);
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:REFUND_RESTORE"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        RefundRestoreRequest sku1 = new RefundRestoreRequest();
        sku1.setOrderId(ORDER_ID);
        sku1.setSkuId(10001L);
        sku1.setQuantity(2);
        sku1.setUserId(USER_ID);
        RefundRestoreRequest sku2 = new RefundRestoreRequest();
        sku2.setOrderId(ORDER_ID);
        sku2.setSkuId(10002L);
        sku2.setQuantity(1);
        sku2.setUserId(USER_ID);

        inventoryService.refundRestore(sku1);
        inventoryService.refundRestore(sku2);

        verify(valueOperations).setIfAbsent(eq("inventory:refund:123456:10001"), eq("1"), any(java.time.Duration.class));
        verify(valueOperations).setIfAbsent(eq("inventory:refund:123456:10002"), eq("1"), any(java.time.Duration.class));
        verify(rocketMQTemplate, times(2)).syncSend(eq("INVENTORY_TOPIC:REFUND_RESTORE"), any(Message.class), eq(3000L));
    }
}

