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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock 支付服务
 * <p>
 * 策略模式：通过 @ConditionalOnProperty 控制加载。
 * 生产环境只需新增 AlipayServiceImpl / WechatPayServiceImpl 替换。
 * </p>
 * <p>
 * Mock 行为：
 * - 成功：创建支付记录（流水号 MOCK_ 开头）→ 标记支付成功 → 更新订单状态
 * - 失败：payType=2 时 50% 概率模拟支付失败，创建失败记录
 * - 退款：退款成功回调执行真实 Feign 释放库存+退券；退款失败记录失败原因
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
     * 创建支付（Mock：成功或随机失败）
     * <p>
     * payType=2（微信）时 50% 概率模拟支付失败（如余额不足、网络超时等）。
     * 失败时将 orderId 返回给调用方，由 Controller 触发 pay-fail 回调。
     * </p>
     *
     * @return PaymentResult 包含成功/失败状态和对应数据
     */
    public PaymentResult createPayment(Long userId, PayRequest request) {
        Order order = orderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, request.getOrderId()));
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() != 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "订单不是待付款状态");
        }

        // 【Mock 失败模拟】payType=2（微信）时 30% 概率失败
        String failReason = null;
        if (request.getPayType() != null && request.getPayType() == 2) {
            if (ThreadLocalRandom.current().nextInt(100) < 30) {
                failReason = "余额不足，请联系发卡行";
            }
        }

        // 支付失败路径
        if (failReason != null) {
            String paymentNo = "MOCK_FAIL_" + System.currentTimeMillis();
            Payment payment = new Payment();
            payment.setId(IdWorker.getId());
            payment.setOrderId(order.getId());
            payment.setUserId(userId);
            payment.setPaymentNo(paymentNo);
            payment.setAmount(order.getPayAmount());
            payment.setPayType(request.getPayType());
            payment.setStatus(2); // 支付失败
            paymentRepository.insert(payment);

            log.info("[支付Mock] 模拟支付失败: orderId={}, reason={}", order.getId(), failReason);
            return new PaymentResult(false, order.getId(), failReason, payment);
        }

        // 支付成功路径
        boolean paySuccess = orderService.onPaymentSuccess(order.getId(), userId);
        if (!paySuccess) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "订单已取消或已支付");
        }

        String paymentNo = "MOCK_PAY_" + System.currentTimeMillis();
        Payment payment = new Payment();
        payment.setId(IdWorker.getId());
        payment.setOrderId(order.getId());
        payment.setUserId(userId);
        payment.setPaymentNo(paymentNo);
        payment.setAmount(order.getPayAmount());
        payment.setPayType(request.getPayType());
        payment.setStatus(1);
        payment.setPaidAt(LocalDateTime.now());
        paymentRepository.insert(payment);

        log.info("[支付Mock] 支付成功: orderId={}, paymentNo={}, amount={}",
                order.getId(), paymentNo, order.getPayAmount());

        return new PaymentResult(true, order.getId(), null, payment);
    }

    /**
     * 查询支付状态
     */
    public Payment getPaymentByOrderId(Long orderId) {
        return paymentRepository.selectByOrderId(orderId);
    }

    /**
     * 支付结果封装
     */
    public record PaymentResult(boolean success, Long orderId, String failReason, Payment payment) {}
}
