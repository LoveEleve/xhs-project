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
import com.myxhs.inventory.dto.request.StockItem;
import com.myxhs.inventory.entity.Inventory;
import com.myxhs.inventory.mapper.InventoryMapper;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.redisson.api.RedissonClient;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * InventoryService 单元测试（C-008 键模型：单库存键 + per-(sku,order) 记录 + items 契约）
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
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
    private DefaultRedisScript<Long> refundRestoreScript;
    @Mock
    private DefaultRedisScript<Long> confirmScript;
    @Mock
    private DefaultRedisScript<Long> markTerminatedScript;
    @Mock
    private BusinessMetrics businessMetrics;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private ValueOperations<String, String> valueOperations;

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
                preDeductScript, releaseScript, refundRestoreScript, confirmScript, markTerminatedScript,
                businessMetrics, redissonClient,
                mock(com.myxhs.inventory.feign.ProductFeignClient.class)
        );
        // 注入 @Value 字段（非 final，不在 Lombok 构造函数中）
        ReflectionTestUtils.setField(inventoryService, "preDeductExpireSeconds", 1800);
        ReflectionTestUtils.setField(inventoryService, "terminatedTombstoneTtlSeconds", 86400L);
        ReflectionTestUtils.setField(inventoryService, "confirmedMarkerTtlSeconds", 86400L);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private static StockItem item(Long skuId, Integer quantity) {
        StockItem item = new StockItem();
        item.setSkuId(skuId);
        item.setQuantity(quantity);
        return item;
    }

    // ==================== initStock ====================

    @Test
    @DisplayName("库存初始化 - 单键写入可用库存（C-008 去分桶）")
    void initStockSuccess() {
        when(valueOperations.setIfAbsent(
                eq("inventory:init:lock:10001"), eq("1"), any(java.time.Duration.class)))
                .thenReturn(true);
        when(inventoryMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);

        InventoryInitRequest request = new InventoryInitRequest();
        request.setSkuId(SKU_ID);
        request.setTotalStock(100);

        inventoryService.initStock(request);

        verify(valueOperations).setIfAbsent(eq("inventory:init:lock:10001"), eq("1"), any(java.time.Duration.class));
        // C-008：单库存键（原 total + bucket* + bucket:count 收敛为一键）
        verify(valueOperations).set(eq("inventory:{10001}:stock"), eq("100"));
        verify(inventoryMapper).insert(any(Inventory.class));
    }

    @Test
    @DisplayName("库存初始化 - 重复初始化不会加倍库存，应抛出异常")
    void initStockDuplicatePrevention() {
        when(valueOperations.setIfAbsent(
                eq("inventory:init:lock:10001"), eq("1"), any(java.time.Duration.class)))
                .thenReturn(true);
        when(stringRedisTemplate.hasKey("inventory:{10001}:stock")).thenReturn(true);

        InventoryInitRequest request = new InventoryInitRequest();
        request.setSkuId(SKU_ID);
        request.setTotalStock(100);

        assertThatThrownBy(() -> inventoryService.initStock(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存已初始化");

        verify(inventoryMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    // ==================== preDeduct ====================

    @Test
    @DisplayName("预扣减 - 正常扣减库存成功（单键脚本）")
    void preDeductSuccess() {
        when(inventoryMapper.insertPredeductIdem(ORDER_ID, SKU_ID)).thenReturn(1);
        when(stringRedisTemplate.hasKey(eq("inventory:{10001}:stock"))).thenReturn(true);
        // Lua 预扣脚本返回 1（成功）；C-008：4 个 ARGV（orderId/qty/expire/ttl）
        when(stringRedisTemplate.execute(
                eq(preDeductScript), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
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

        verify(inventoryMapper).insertPredeductIdem(ORDER_ID, SKU_ID);
    }

    @Test
    @DisplayName("C-008：prededuct 脚本全部 KEYS 同 {skuId} tag（Cluster 单 slot）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void preDeductAllKeysSameSlot() {
        when(inventoryMapper.insertPredeductIdem(ORDER_ID, SKU_ID)).thenReturn(1);
        when(stringRedisTemplate.hasKey(eq("inventory:{10001}:stock"))).thenReturn(true);
        when(stringRedisTemplate.execute(
                eq(preDeductScript), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
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
        inventoryService.preDeduct(request);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate).execute(
                eq(preDeductScript), keysCaptor.capture(),
                anyString(), anyString(), anyString(), anyString());
        assertThat(keysCaptor.getValue()).allMatch(k -> k.startsWith("inventory:{10001}"));
    }

    @Test
    @DisplayName("预扣减 - 订单已终结（墓碑命中）: 跳过视为处理完成")
    void preDeduct_terminatedTombstone_skip() {
        when(inventoryMapper.insertPredeductIdem(ORDER_ID, SKU_ID)).thenReturn(1);
        when(stringRedisTemplate.hasKey(eq("inventory:{10001}:stock"))).thenReturn(true);
        // 04B：Lua 返回 -3（终结墓碑命中）→ 跳过预扣，视为处理完成（不抛异常、不删幂等占位）
        when(stringRedisTemplate.execute(
                eq(preDeductScript), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(-3L);

        PreDeductRequest request = new PreDeductRequest();
        request.setSkuId(SKU_ID);
        request.setOrderId(ORDER_ID);
        request.setQuantity(2);
        request.setUserId(USER_ID);

        assertThatCode(() -> inventoryService.preDeduct(request))
                .doesNotThrowAnyException();

        verify(inventoryMapper, never()).deletePredeductIdem(ORDER_ID, SKU_ID);
    }

    @Test
    @DisplayName("预扣减 - 库存不足时抛出业务异常")
    void preDeductInsufficientStock() {
        when(inventoryMapper.insertPredeductIdem(ORDER_ID, SKU_ID)).thenReturn(1);
        when(stringRedisTemplate.hasKey(eq("inventory:{10001}:stock"))).thenReturn(true);
        when(stringRedisTemplate.execute(
                eq(preDeductScript), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(0L);

        PreDeductRequest request = new PreDeductRequest();
        request.setSkuId(SKU_ID);
        request.setOrderId(ORDER_ID);
        request.setQuantity(999);
        request.setUserId(USER_ID);

        assertThatThrownBy(() -> inventoryService.preDeduct(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining(ResultCode.STOCK_NOT_ENOUGH.getMessage());

        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class), anyLong());
    }

    @Test
    @DisplayName("预扣减 - MQ发送失败时取消 outbox 避免补发已回滚事件")
    void preDeduct_cancelsOutboxWhenMqSendFails() {
        when(inventoryMapper.insertPredeductIdem(ORDER_ID, SKU_ID)).thenReturn(1);
        when(stringRedisTemplate.hasKey(eq("inventory:{10001}:stock"))).thenReturn(true);
        when(stringRedisTemplate.execute(
                eq(preDeductScript), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1L);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:PRE_DEDUCT"), any(Message.class), eq(3000L)))
                .thenThrow(new RuntimeException("mq down"));
        // 回滚脚本（releaseScript，1 个 ARGV=orderId）
        when(stringRedisTemplate.execute(eq(releaseScript), anyList(), anyString())).thenReturn(1L);

        PreDeductRequest request = new PreDeductRequest();
        request.setSkuId(SKU_ID);
        request.setOrderId(ORDER_ID);
        request.setQuantity(2);
        request.setUserId(USER_ID);

        assertThatThrownBy(() -> inventoryService.preDeduct(request))
                .isInstanceOf(BizException.class);

        verify(inventoryMapper).cancelOutboxEvent(anyLong(), anyLong(), anyString());
    }

    // ==================== confirmDeduct ====================

    @Test
    @DisplayName("确认扣减 - MQ发送失败时保留 outbox 待补发（items 驱动）")
    void confirmDeduct_keepsOutboxWhenMqSendFails() {
        when(stringRedisTemplate.execute(eq(confirmScript), anyList(), anyString())).thenReturn(3L);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:CONFIRM"), any(Message.class), eq(3000L)))
                .thenThrow(new RuntimeException("mq down"));

        ConfirmDeductRequest request = new ConfirmDeductRequest();
        request.setOrderId(ORDER_ID);
        request.setItems(List.of(item(SKU_ID, 3)));

        assertThatCode(() -> inventoryService.confirmDeduct(request)).doesNotThrowAnyException();

        // C-008：数量以预扣记录为准（脚本返回 3）
        verify(inventoryMapper).insertOutboxEvent(anyLong(), eq(ORDER_ID), eq(SKU_ID), eq(3), eq("CONFIRM"));
        verify(inventoryMapper, never()).cancelOutboxEvent(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("确认扣减 - 全部记录不存在（支付确认早于预扣落）：写确认标记")
    void confirmDeduct_missingRecord_writesConfirmedMarker() {
        when(stringRedisTemplate.execute(eq(confirmScript), anyList(), anyString())).thenReturn(0L);

        ConfirmDeductRequest request = new ConfirmDeductRequest();
        request.setOrderId(ORDER_ID);
        request.setItems(List.of(item(SKU_ID, 3)));

        assertThatCode(() -> inventoryService.confirmDeduct(request)).doesNotThrowAnyException();

        verify(valueOperations).set(eq("inventory:confirmed:123456"), eq("1"),
                eq(86400L), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("C-008 F12：部分 SKU 记录缺失 → 也写确认标记（迟到记录不被释放）")
    void confirmDeduct_partialMissing_writesConfirmedMarker() {
        // sku1 存在（返回数量），sku2 缺失（返回 0）
        when(stringRedisTemplate.execute(eq(confirmScript), anyList(), anyString()))
                .thenReturn(5L)
                .thenReturn(0L);
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("INVENTORY_TOPIC:CONFIRM"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        ConfirmDeductRequest request = new ConfirmDeductRequest();
        request.setOrderId(ORDER_ID);
        request.setItems(List.of(item(10001L, 5), item(10002L, 2)));

        assertThatCode(() -> inventoryService.confirmDeduct(request)).doesNotThrowAnyException();

        verify(valueOperations).set(eq("inventory:confirmed:123456"), eq("1"),
                eq(86400L), eq(java.util.concurrent.TimeUnit.SECONDS));
    }

    // ==================== releaseStock ====================

    @Test
    @DisplayName("释放库存 - items 驱动释放恢复可用库存")
    void releaseStock() {
        // 04B：终结墓碑脚本（检查预扣记录 + 总是写墓碑）→ 记录存在（返回 1），继续释放
        when(stringRedisTemplate.execute(eq(markTerminatedScript), anyList(), anyString()))
                .thenReturn(1L);
        when(stringRedisTemplate.execute(eq(releaseScript), anyList(), anyString()))
                .thenReturn(5L);
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(
                eq("INVENTORY_TOPIC:RELEASE"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        ReleaseStockRequest request = new ReleaseStockRequest();
        request.setOrderId(ORDER_ID);
        request.setItems(List.of(item(SKU_ID, 5)));

        assertThatCode(() -> inventoryService.releaseStock(request))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(
                eq(releaseScript), anyList(), eq(String.valueOf(ORDER_ID)));
        verify(rocketMQTemplate).syncSend(
                eq("INVENTORY_TOPIC:RELEASE"), any(Message.class), eq(3000L));
    }

    @Test
    @DisplayName("释放库存 - 无预扣记录（已释放/从未预扣）：写终结墓碑并幂等跳过")
    void releaseStock_noRecord_writesTombstone() {
        when(stringRedisTemplate.execute(eq(markTerminatedScript), anyList(), anyString()))
                .thenReturn(0L);

        ReleaseStockRequest request = new ReleaseStockRequest();
        request.setOrderId(ORDER_ID);
        request.setItems(List.of(item(SKU_ID, 5)));

        assertThatCode(() -> inventoryService.releaseStock(request))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(eq(markTerminatedScript), anyList(), anyString());
        verify(stringRedisTemplate, never()).execute(
                eq(releaseScript), anyList(), anyString());
    }

    @Test
    @DisplayName("释放库存 - items 缺失：保守不释放（仅告警）")
    void releaseStock_missingItems_conservativeSkip() {
        ReleaseStockRequest request = new ReleaseStockRequest();
        request.setOrderId(ORDER_ID);

        assertThatCode(() -> inventoryService.releaseStock(request))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate, never()).execute(eq(markTerminatedScript), anyList(), anyString());
    }

    // ==================== refundRestore ====================

    @Test
    @DisplayName("退款回补 - 多 SKU 独立累计键 + 同槽原子脚本（C-008）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void refundRestore_multiSkuUsesIndependentIdempotentKeys() {
        // C-008：累计幂等 + 库存 INCRBY 单脚本原子（KEYS[1]=inventory:{skuId}:refund:{orderId}，2 ARGV）
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(2L);
        when(stringRedisTemplate.hasKey(eq("inventory:{10001}:stock"))).thenReturn(true);
        when(stringRedisTemplate.hasKey(eq("inventory:{10002}:stock"))).thenReturn(true);
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

        // 幂等键按 SKU 独立：原子脚本每个 SKU 各执行一次（KEYS=[refundKey, stockKey]，同 {skuId} tag）
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(stringRedisTemplate, times(2)).execute(
                eq(refundRestoreScript), keysCaptor.capture(), anyString(), anyString());
        assertThat(keysCaptor.getAllValues().stream().map(List::toString))
                .containsExactlyInAnyOrder(
                        "[inventory:{10001}:refund:123456, inventory:{10001}:stock]",
                        "[inventory:{10002}:refund:123456, inventory:{10002}:stock]");
        verify(rocketMQTemplate, times(2)).syncSend(
                eq("INVENTORY_TOPIC:REFUND_RESTORE"), any(Message.class), eq(3000L));
    }
}
