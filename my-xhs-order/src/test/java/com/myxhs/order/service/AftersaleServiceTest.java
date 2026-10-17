package com.myxhs.order.service;

import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.order.dto.request.AftersaleApplyRequest;
import com.myxhs.order.entity.Aftersale;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderItem;
import com.myxhs.order.feign.InventoryFeignClient;
import com.myxhs.order.feign.PaymentFeignClient;
import com.myxhs.order.mapper.OrderItemMapper;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.repository.AftersaleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AftersaleService 单元测试（ITER-001 行级售后锚点）
 * <p>
 * 聚焦阶段②改造的锚点语义：售后单绑定 item_id、幂等键按 (item_id, type) 查询、
 * 分摊以**落库 discount_share** 为准（重算仅兜底）。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AftersaleServiceTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private AftersaleRepository aftersaleRepository;
    @Mock
    private PaymentFeignClient paymentFeignClient;
    @Mock
    private InventoryFeignClient inventoryFeignClient;
    @Mock
    private IdGeneratorUtil idGeneratorUtil;
    @Mock
    private BusinessMetrics businessMetrics;

    private AftersaleService aftersaleService;

    private static final Long USER_ID = 1001L;
    private static final Long ORDER_ID = 123456L;
    private static final Long ITEM_ID = 7001L;
    private static final Long SKU_ID = 10001L;

    @BeforeEach
    void setUp() {
        aftersaleService = new AftersaleService(orderMapper, orderItemMapper, aftersaleRepository,
                paymentFeignClient, inventoryFeignClient, idGeneratorUtil, businessMetrics);
        // 默认无历史占用（闸门一/二/三的基础桩）
        when(aftersaleRepository.sumRefundedAmountBySku(anyLong(), anyLong())).thenReturn(BigDecimal.ZERO);
        when(aftersaleRepository.sumRefundedQuantityBySku(anyLong(), anyLong())).thenReturn(0);
        when(aftersaleRepository.sumRefundedAmount(anyLong())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("申请售后 - 挂行锚点（item_id）+ 落库分摊为准（Σshare 校验不依赖重算）")
    void apply_bindsItemIdAndUsesStoredShare() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setOrderNo("ORD-X");
        order.setStatus(3); // 已完成
        order.setPayAmount(new BigDecimal("300.00"));
        order.setDiscountAmount(new BigDecimal("30.00"));
        when(orderMapper.selectOne(any())).thenReturn(order);

        OrderItem item = new OrderItem();
        item.setId(ITEM_ID);
        item.setOrderId(ORDER_ID);
        item.setUserId(USER_ID);
        item.setSkuId(SKU_ID);
        item.setSkuName("商品A");
        item.setPrice(new BigDecimal("100.00"));
        item.setQuantity(3);
        item.setTotalAmount(new BigDecimal("300.00"));
        item.setDiscountShare(new BigDecimal("30.00")); // 落库分摊（行实付 = 270）
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        when(aftersaleRepository.findByItemType(ITEM_ID, Aftersale.TYPE_REFUND_ONLY)).thenReturn(null);
        when(idGeneratorUtil.nextId()).thenReturn(999L);
        when(aftersaleRepository.insert(any(Aftersale.class))).thenReturn(1);
        when(aftersaleRepository.findByNo(anyString())).thenAnswer(inv ->
                buildStored(item, inv.getArgument(0)));

        AftersaleApplyRequest request = new AftersaleApplyRequest();
        request.setOrderId(ORDER_ID);
        request.setSkuId(SKU_ID);
        request.setType(Aftersale.TYPE_REFUND_ONLY);
        request.setApplyQuantity(1); // 部分退：行实付 270 × 1/3 = 90.00（按比例向下取整）

        aftersaleService.apply(USER_ID, request);

        ArgumentCaptor<Aftersale> captor = ArgumentCaptor.forClass(Aftersale.class);
        verify(aftersaleRepository).insert(captor.capture());
        Aftersale saved = captor.getValue();
        // 锚点：item_id 绑定（行级售后）
        assertThat(saved.getItemId()).isEqualTo(ITEM_ID);
        // 分摊口径：以落库 share 为准（30/3=10），本次应退 = 100 - 10 = 90
        assertThat(saved.getDiscountShare()).isEqualByComparingTo("10.00");
        assertThat(saved.getRefundAmount()).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("申请售后 - 幂等：同 item 同类型已存在活动单 → 拒绝")
    void apply_duplicateByItemType() {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setUserId(USER_ID);
        order.setOrderNo("ORD-X");
        order.setStatus(3);
        order.setPayAmount(new BigDecimal("300.00"));
        order.setDiscountAmount(BigDecimal.ZERO);
        when(orderMapper.selectOne(any())).thenReturn(order);

        OrderItem item = new OrderItem();
        item.setId(ITEM_ID);
        item.setOrderId(ORDER_ID);
        item.setUserId(USER_ID);
        item.setSkuId(SKU_ID);
        item.setPrice(new BigDecimal("100.00"));
        item.setQuantity(1);
        item.setTotalAmount(new BigDecimal("100.00"));
        item.setDiscountShare(BigDecimal.ZERO);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item));

        Aftersale existing = new Aftersale();
        existing.setId(1L);
        existing.setItemId(ITEM_ID);
        existing.setType(Aftersale.TYPE_REFUND_ONLY);
        existing.setStatus(Aftersale.STATUS_APPLIED); // 进行中
        when(aftersaleRepository.findByItemType(ITEM_ID, Aftersale.TYPE_REFUND_ONLY)).thenReturn(existing);

        AftersaleApplyRequest request = new AftersaleApplyRequest();
        request.setOrderId(ORDER_ID);
        request.setSkuId(SKU_ID);
        request.setType(Aftersale.TYPE_REFUND_ONLY);
        request.setApplyQuantity(1);

        assertThatThrownBy(() -> aftersaleService.apply(USER_ID, request))
                .hasMessageContaining("已有进行中的售后申请");
        verify(aftersaleRepository, never()).insert(any(Aftersale.class));
    }

    private Aftersale buildStored(OrderItem item, String aftersaleNo) {
        Aftersale a = new Aftersale();
        a.setAftersaleNo(aftersaleNo);
        a.setOrderId(ORDER_ID);
        a.setItemId(item.getId());
        a.setSkuId(item.getSkuId());
        return a;
    }
}
