package com.myxhs.cart.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 购物车订单联动服务单测（C-006）——**定序握手协议矩阵（本变更核心）**
 * <p>
 * 覆盖：source 分支 / 双向去重 / 正常序回补 / 竞态（回补先到→clear 侧握手回补）/ 直接购买单 /
 * 旧版事件跳过 / 回补失败抛错重试 / marker TTL 规格。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartOrderLinkageServiceTest {

    private static final Long USER_ID = 1001L;
    private static final String ORDER_NO = "ORD-1001";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private CartService cartService;

    private CartOrderLinkageService linkageService;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        linkageService = new CartOrderLinkageService(stringRedisTemplate, cartService, new SimpleMeterRegistry());
    }

    private List<CartOrderLinkageService.LinkageItem> items() {
        return List.of(new CartOrderLinkageService.LinkageItem(1L, 2), new CartOrderLinkageService.LinkageItem(2L, 1));
    }

    private String marker(String type) {
        return "myxhs:cart:linkage:" + type + ":" + ORDER_NO;
    }

    // ==================== 清车（ORDER_TRANSACTION_TOPIC） ====================

    @Test
    @DisplayName("source=CART → 批量移除 + 置 cleared（TTL 7 天）；无 pending 时正常收口")
    void clearForCartSource() {
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(false);
        when(stringRedisTemplate.hasKey(marker("restore-pending"))).thenReturn(false);

        linkageService.handleOrderCreated(ORDER_NO, USER_ID, "CART", items());

        verify(cartService).removeBySkus(eq(USER_ID), eq(List.of(1L, 2L)));
        verify(valueOps).set(eq(marker("cleared")), eq("1"), eq(Duration.ofDays(7)));
        verify(cartService, never()).mergeItem(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("source=DIRECT/缺省 → 不碰购物车（零交互）")
    void directSourceSkipsClear() {
        linkageService.handleOrderCreated(ORDER_NO, USER_ID, "DIRECT", items());
        linkageService.handleOrderCreated(ORDER_NO, USER_ID, null, items());

        verify(cartService, never()).removeBySkus(anyLong(), anyList());
    }

    @Test
    @DisplayName("重复投递（cleared 已存在）→ 跳过清车（防误删用户清车后的新加购）")
    void clearDuplicateSkips() {
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(true);

        linkageService.handleOrderCreated(ORDER_NO, USER_ID, "CART", items());

        verify(cartService, never()).removeBySkus(anyLong(), anyList());
    }

    // ==================== 回补（ORDER_TERMINATION_TOPIC） ====================

    @Test
    @DisplayName("正常序：清车已处理（cleared 存在）→ 逐项 merge 回补 + SETNX restored + 删 pending")
    void restoreAfterClearMerges() {
        when(stringRedisTemplate.hasKey(marker("restored"))).thenReturn(false);
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(true);

        linkageService.handleOrderTerminated(ORDER_NO, USER_ID, items());

        verify(valueOps).set(eq(marker("restore-pending")), eq("1"), eq(Duration.ofHours(1)));
        verify(cartService).mergeItem(USER_ID, 1L, 2);
        verify(cartService).mergeItem(USER_ID, 2L, 1);
        verify(valueOps).setIfAbsent(eq(marker("restored")), eq("1"), eq(Duration.ofDays(7)));
        verify(stringRedisTemplate).delete(marker("restore-pending"));
    }

    @Test
    @DisplayName("重复投递（restored 已存在）→ 跳过回补（不抬回用户调小的数量）")
    void restoreDuplicateSkips() {
        when(stringRedisTemplate.hasKey(marker("restored"))).thenReturn(true);

        linkageService.handleOrderTerminated(ORDER_NO, USER_ID, items());

        verify(cartService, never()).mergeItem(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("旧版事件（无 items）→ 安全跳过（eventVersion=1 时期无清车）")
    void legacyEventWithoutItemsSkips() {
        linkageService.handleOrderTerminated(ORDER_NO, USER_ID, null);
        linkageService.handleOrderTerminated(ORDER_NO, USER_ID, List.of());

        verify(cartService, never()).mergeItem(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("竞态：回补先到（cleared 不存在）→ 置 pending、挂起不 merge（商品仍在车中）")
    void restoreBeforeClearHangs() {
        when(stringRedisTemplate.hasKey(marker("restored"))).thenReturn(false);
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(false);

        linkageService.handleOrderTerminated(ORDER_NO, USER_ID, items());

        verify(valueOps).set(eq(marker("restore-pending")), eq("1"), eq(Duration.ofHours(1)));
        verify(cartService, never()).mergeItem(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("竞态握手：回补先到挂起后，清车侧发现 pending → 清车后立即回补（恰好一方 merge）")
    void raceClearAfterRestoreMerges() {
        // 第一段：回补先到 → 挂起（不 merge）
        when(stringRedisTemplate.hasKey(marker("restored"))).thenReturn(false);
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(false);
        linkageService.handleOrderTerminated(ORDER_NO, USER_ID, items());
        verify(cartService, never()).mergeItem(anyLong(), anyLong(), anyInt());

        // 第二段：清车到达（pending 存在）→ 清车 + 立即回补
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(false);
        when(stringRedisTemplate.hasKey(marker("restore-pending"))).thenReturn(true);
        linkageService.handleOrderCreated(ORDER_NO, USER_ID, "CART", items());

        verify(cartService).removeBySkus(eq(USER_ID), eq(List.of(1L, 2L)));
        verify(cartService).mergeItem(USER_ID, 1L, 2);
        verify(valueOps).setIfAbsent(eq(marker("restored")), eq("1"), eq(Duration.ofDays(7)));
        verify(stringRedisTemplate).delete(marker("restore-pending"));
    }

    @Test
    @DisplayName("clear 永久失败自愈：无 cleared 标记 → 挂起不 merge（商品从未被清，语义正确）")
    void clearFailureSelfHeal() {
        when(stringRedisTemplate.hasKey(marker("restored"))).thenReturn(false);
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(false);

        linkageService.handleOrderTerminated(ORDER_NO, USER_ID, items());

        // 不 merge：商品还在车里（restore-pending 自然过期）
        verify(cartService, never()).mergeItem(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("回补中途失败 → 抛错（MQ 重试收敛；restored 未置位）")
    void restoreFailureThrows() {
        when(stringRedisTemplate.hasKey(marker("restored"))).thenReturn(false);
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(true);
        when(cartService.mergeItem(USER_ID, 2L, 1)).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> linkageService.handleOrderTerminated(ORDER_NO, USER_ID, items()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回补失败");

        verify(valueOps, never()).setIfAbsent(eq(marker("restored")), any(), any());
    }

    @Test
    @DisplayName("深审查F1：竞态握手回补部分失败重入——清车重试补做回补（不因 cleared 去重而漏）")
    void raceRestorePartialFailureReentryCompletes() {
        // 第一段：回补先到 → 挂起（pending 置位、不 merge）
        when(stringRedisTemplate.hasKey(marker("restored"))).thenReturn(false);
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(false);
        linkageService.handleOrderTerminated(ORDER_NO, USER_ID, items());
        verify(cartService, never()).mergeItem(anyLong(), anyLong(), anyInt());

        // 第二段：清车到达（pending 在）→ 握手回补中途失败（item1 成功、item2 失败）→ 抛错（触发 MQ 重试）
        when(stringRedisTemplate.hasKey(marker("cleared"))).thenReturn(false, true); // 首查 false；重试查 true
        when(stringRedisTemplate.hasKey(marker("restore-pending"))).thenReturn(true);
        when(cartService.mergeItem(USER_ID, 1L, 2)).thenReturn(true);
        when(cartService.mergeItem(USER_ID, 2L, 1)).thenThrow(new RuntimeException("redis blip"))
                .thenReturn(true);
        assertThatThrownBy(() -> linkageService.handleOrderCreated(ORDER_NO, USER_ID, "CART", items()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回补失败");

        // 第三段：清车消费者重试 → cleared 已存在但握手回补未完成 → 补做回补（幂等收敛）
        linkageService.handleOrderCreated(ORDER_NO, USER_ID, "CART", items());

        verify(cartService, times(2)).mergeItem(USER_ID, 1L, 2); // 两轮均 merge（max 幂等）
        verify(cartService, times(2)).mergeItem(USER_ID, 2L, 1); // 首失败 + 重试成功
        verify(valueOps).setIfAbsent(eq(marker("restored")), eq("1"), eq(Duration.ofDays(7)));
        verify(stringRedisTemplate).delete(marker("restore-pending"));
    }
}
