package com.myxhs.order.service;

import com.myxhs.order.dto.SkuInfoDTO;
import com.myxhs.order.dto.request.OrderCreateRequest;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderItem;
import com.myxhs.order.entity.SubOrder;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.mapper.OrderItemMapper;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.mapper.SubOrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 订单事务服务（独立类，解决同类内部调用 @Transactional 不生效的问题）
 * <p>
 * 为什么要独立成一个类？
 * Spring AOP 基于代理实现，同一个类内部的方法调用（this.xxx()）不会经过代理，
 * 导致 @Transactional 注解不生效。将事务方法抽取到独立类中，
 * 通过 Spring 注入调用，确保走代理。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransactionService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final SubOrderMapper subOrderMapper;
    private final LocalMessageMapper localMessageMapper;
    private final OrderEventService orderEventService;
    private final ObjectMapper objectMapper;

    /**
     * 本地事务：创建订单（父单）+ 子单（按商家拆单）+ 明细 + 本地消息表
     * <p>
     * 同一个 DB 事务保证原子性。
     * 任何一步失败，整个事务回滚（父单 + 子单 + 明细 + 本地消息表都不会写入）。
     * </p>
     */
    /**
     * @param transactionPayload 事务消息的完整 payload JSON（与 MQ 消息体一致）
     *                           补发时直接用这个 payload 发送到 MQ，消费端无需适配
     */
    @Transactional(rollbackFor = Exception.class)
    public Order executeLocalTransaction(Long userId, OrderCreateRequest request,
                                         String orderNo, BigDecimal totalAmount,
                                         BigDecimal discountAmount, BigDecimal payAmount,
                                         String transactionPayload,
                                         String addressSnapshot,
                                         Map<Long, SkuInfoDTO> skuMap) {
        // 1. 创建订单主表
        Order order = new Order();
        order.setUserId(userId);
        order.setOrderNo(orderNo);
        order.setTotalAmount(totalAmount);
        order.setPayAmount(payAmount);
        order.setDiscountAmount(discountAmount);
        order.setCouponId(request.getCouponId());
        order.setStatus(0); // 待付款
        order.setRemark(request.getRemark());
        // 真实地址快照（来自 user 服务，替代 Mock 硬编码）
        order.setAddressSnapshot(addressSnapshot != null ? addressSnapshot : "{\"name\":\"\",\"phone\":\"\",\"address\":\"\"}");
        orderMapper.insert(order);

        // 2. 记录 Event Sourcing 事件（与订单创建同事务；载荷含 orderNo/source——
        //    收尾修复：原先此处 payload=null，而 createOrder 步骤 7 的"补记"因重复事件恒被幂等跳过，
        //    导致 EVENT_CREATED 载荷永不落库）
        orderEventService.appendEvent(order, OrderEventService.EVENT_CREATED,
                java.util.Map.of("orderNo", orderNo, "source", "APP"));

        // 3. 定型订单明细（使用真实 SKU 数据；预分配行 ID——分摊以行 ID 为键，与售后侧重算口径一致）
        java.util.List<OrderItem> items = new java.util.ArrayList<>(request.getSkuItems().size());
        for (OrderCreateRequest.SkuItem skuItem : request.getSkuItems()) {
            SkuInfoDTO sku = skuMap.get(skuItem.getSkuId());
            OrderItem item = new OrderItem();
            // 预分配行 ID：分摊以行 ID 为键（与售后侧重算口径一致——平局按 ID 定序，保证确定性）
            item.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
            item.setOrderId(order.getId());
            item.setUserId(userId);
            item.setSkuId(skuItem.getSkuId());
            // 卖家ID（决策 3：缺失兜底平台自营 0）——拆单分组的依据
            item.setSellerId(sku != null && sku.getSellerId() != null ? sku.getSellerId() : 0L);
            item.setSpuId(sku != null ? sku.getSpuId() : skuItem.getSkuId());
            item.setSkuName(sku != null ? sku.getName() : "SKU-" + skuItem.getSkuId());
            item.setSkuImage(sku != null ? sku.getImage() : null); // 从 product 服务取 SKU 主图（继承 SPU）
            item.setPrice(sku != null ? sku.getPrice() : BigDecimal.ZERO);
            item.setQuantity(skuItem.getQuantity());
            item.setTotalAmount((sku != null ? sku.getPrice() : BigDecimal.ZERO)
                    .multiply(BigDecimal.valueOf(skuItem.getQuantity())));
            item.setDiscountShare(BigDecimal.ZERO);
            items.add(item);
        }
        java.util.Map<Long, BigDecimal> allocation =
                DiscountAllocator.allocateByItems(items, discountAmount);

        // 4. 拆单（ITER-001 T3.4）：按卖家分组生成子单（下单即拆，同一本地事务），行挂子单
        //    金额守恒：子单金额 = Σ行小计 / Σ行分摊 / Σ行实付；父单实付 = Σ子单 = Σ行
        java.util.LinkedHashMap<Long, java.util.List<OrderItem>> bySeller = new java.util.LinkedHashMap<>();
        for (OrderItem item : items) {
            item.setDiscountShare(allocation.getOrDefault(item.getId(), BigDecimal.ZERO));
            bySeller.computeIfAbsent(item.getSellerId(), k -> new java.util.ArrayList<>()).add(item);
        }
        int subIndex = 1;
        for (java.util.Map.Entry<Long, java.util.List<OrderItem>> entry : bySeller.entrySet()) {
            java.util.List<OrderItem> sellerItems = entry.getValue();
            BigDecimal subTotal = BigDecimal.ZERO;
            BigDecimal subDiscount = BigDecimal.ZERO;
            for (OrderItem item : sellerItems) {
                subTotal = subTotal.add(item.getTotalAmount());
                subDiscount = subDiscount.add(item.getDiscountShare());
            }
            SubOrder sub = new SubOrder();
            sub.setId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
            sub.setOrderId(order.getId());
            sub.setOrderNo(orderNo);
            sub.setSubOrderNo(orderNo + "-" + subIndex++); // 子单号 = 父单号 + 序号（唯一键兜底）
            sub.setUserId(userId);
            sub.setSellerId(entry.getKey());
            sub.setStatus(SubOrder.STATUS_PENDING_PAYMENT);
            sub.setTotalAmount(subTotal);
            sub.setDiscountAmount(subDiscount);
            sub.setPayAmount(subTotal.subtract(subDiscount));
            subOrderMapper.insert(sub);
            for (OrderItem item : sellerItems) {
                item.setSubOrderId(sub.getId());
            }
        }

        // 5. 写入订单明细（挂子单）
        for (OrderItem item : items) {
            orderItemMapper.insert(item);
        }

        // 6. 写入本地消息表（与订单同库同事务）
        //    payload 存储与事务消息体一致的 JSON，补发时直接发送到 MQ
        LocalMessage message = new LocalMessage();
        message.setUserId(userId); // 分片键冗余存储
        message.setTransactionId(orderNo);
        message.setServiceName("order");
        message.setOperationType("ORDER_CREATED");
        message.setPayload(transactionPayload);
        message.setStatus(0); // 待处理
        message.setRetryCount(0);
        message.setNextRetryTime(LocalDateTime.now()); // 初始补发时间: 立即, 防NULL导致SQL不匹配
        localMessageMapper.insert(message);

        return order;
    }
}
