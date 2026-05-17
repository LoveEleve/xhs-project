package com.myxhs.order.service;

import com.myxhs.order.dto.request.OrderCreateRequest;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.OrderItem;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.mapper.OrderItemMapper;
import com.myxhs.order.mapper.OrderMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

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
    private final LocalMessageMapper localMessageMapper;
    private final ObjectMapper objectMapper;

    /**
     * 本地事务：创建订单 + 明细 + 本地消息表
     * <p>
     * 同一个 DB 事务保证原子性。
     * 任何一步失败，整个事务回滚（订单 + 明细 + 本地消息表都不会写入）。
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
                                         String transactionPayload) {
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
        // Mock 地址快照
        order.setAddressSnapshot("{\"name\":\"测试用户\",\"phone\":\"13800138000\"," +
                "\"address\":\"北京市朝阳区xxx路xxx号\"}");
        orderMapper.insert(order);

        // 2. 创建订单明细
        for (OrderCreateRequest.SkuItem skuItem : request.getSkuItems()) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setUserId(userId); // 分片键冗余存储
            item.setSkuId(skuItem.getSkuId());
            item.setSpuId(skuItem.getSkuId()); // Mock: spuId = skuId
            item.setSkuName("Mock商品-" + skuItem.getSkuId());
            item.setSkuImage("https://img.mock.com/sku/" + skuItem.getSkuId() + ".jpg");
            item.setPrice(new BigDecimal("99.00")); // Mock 单价
            item.setQuantity(skuItem.getQuantity());
            item.setTotalAmount(new BigDecimal("99.00").multiply(
                    BigDecimal.valueOf(skuItem.getQuantity())));
            orderItemMapper.insert(item);
        }

        // 3. 写入本地消息表（与订单同库同事务）
        //    payload 存储与事务消息体一致的 JSON，补发时直接发送到 MQ
        LocalMessage message = new LocalMessage();
        message.setUserId(userId); // 分片键冗余存储
        message.setTransactionId(orderNo);
        message.setServiceName("order");
        message.setOperationType("ORDER_CREATED");
        message.setPayload(transactionPayload);
        message.setStatus(0); // 待处理
        message.setRetryCount(0);
        localMessageMapper.insert(message);

        return order;
    }
}
