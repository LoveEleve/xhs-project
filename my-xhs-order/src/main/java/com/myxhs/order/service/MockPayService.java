package com.myxhs.order.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.order.dto.request.PayRequest;
import com.myxhs.order.entity.Order;
import com.myxhs.order.entity.Payment;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Mock 支付服务
 * <p>
 * 策略模式：通过 @ConditionalOnProperty 控制加载。
 * 生产环境只需新增 AlipayServiceImpl / WechatPayServiceImpl 替换。
 * </p>
 * <p>
 * Mock 行为：
 * 1. 创建支付记录（流水号 MOCK_ 开头）
 * 2. 直接标记支付成功
 * 3. 回调订单服务更新状态
 * </p>
 * <p>
 * 重要：支付表（t_payment）在独立库 my_xhs_payment 中，
 * 使用 PaymentRepository（独立数据源）操作，不走 ShardingSphere 分片路由。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pay.type", havingValue = "mock", matchIfMissing = true)
public class MockPayService {

    private final PaymentRepository paymentRepository;
    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /**
     * 创建支付（Mock：直接成功）
     * <p>
     * 并发安全：先调用 onPaymentSuccess（乐观锁 WHERE status=0），
     * 如果返回 false 说明订单已被关单/取消，此时支付失败。
     * 不能"先检查状态再操作"——检查和操作之间可能被关单。
     * </p>
     */
    public Payment createPayment(Long userId, PayRequest request) {
        // 1. 校验订单归属（分库分表后必须带 user_id 查询）
        Order order = orderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, request.getOrderId()));
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        // 快速失败（非原子，仅减少无效操作）
        if (order.getStatus() != 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "订单不是待付款状态");
        }

        // 2. 先尝试更新订单状态（乐观锁，原子操作）
        boolean paySuccess = orderService.onPaymentSuccess(order.getId(), userId);
        if (!paySuccess) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "订单已取消或已支付");
        }

        // 3. 订单状态更新成功后，创建支付记录（独立数据源，不走分片）
        String paymentNo = "MOCK_PAY_" + System.currentTimeMillis();
        Payment payment = new Payment();
        payment.setId(IdWorker.getId()); // 手动生成雪花ID
        payment.setOrderId(order.getId());
        payment.setUserId(userId);
        payment.setPaymentNo(paymentNo);
        payment.setAmount(order.getPayAmount());
        payment.setPayType(request.getPayType());
        payment.setStatus(1); // 支付成功
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.insert(payment);

        log.info("[支付Mock] 支付成功: orderId={}, paymentNo={}, amount={}",
                order.getId(), paymentNo, order.getPayAmount());

        return payment;
    }

    /**
     * 查询支付状态
     */
    public Payment getPaymentByOrderId(Long orderId) {
        return paymentRepository.selectByOrderId(orderId);
    }
}
