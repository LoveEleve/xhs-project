package com.myxhs.order.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.order.dto.request.OrderCreateRequest;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.entity.Order;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.service.OrderTransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 事务消息监听器 — 下单链路分布式事务核心
 * <p>
 * 事务消息 6 步流程：
 * 1. OrderService → RocketMQ: 发送半消息（Half Message）
 * 2. RocketMQ: 存储半消息 → 返回确认
 * 3. 本监听器 executeLocalTransaction(): 执行本地事务
 *    - INSERT t_order + t_order_item + t_local_message（同一 DB 事务）
 * 4a. 本地事务成功 → 返回 COMMIT → 消费者可见
 * 4b. 本地事务失败 → 返回 ROLLBACK → 消息被删除
 * 5. 消费者消费：Inventory 预扣库存
 * 6. Broker 未收到 Commit/Rollback → 调用 checkLocalTransaction() 回查
 * </p>
 * <p>
 * 为什么用事务消息而不是"先写 DB 再发普通消息"？
 * - 先写 DB 再发消息：DB 成功但发消息失败 → 订单创建了但库存没扣 → 超卖
 * - 事务消息：本地事务和消息发送绑定为原子操作，要么都成功要么都失败
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQTransactionListener
public class OrderTransactionListener implements RocketMQLocalTransactionListener {

    private final OrderTransactionService transactionService;
    private final LocalMessageMapper localMessageMapper;

    /**
     * 执行本地事务
     * <p>
     * 半消息发送成功后，RocketMQ 回调此方法执行本地事务。
     * 本地事务成功 → COMMIT（消费者可见）
     * 本地事务失败 → ROLLBACK（消息被删除）
     * </p>
     * <p>
     * arg 参数是 OrderCreateContext，包含下单所需的所有信息。
     * </p>
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        OrderCreateContext context = (OrderCreateContext) arg;
        try {
            // 执行本地事务：创建订单 + 明细 + 本地消息表（同一 DB 事务）
            Order order = transactionService.executeLocalTransaction(
                    context.getUserId(),
                    context.getRequest(),
                    context.getOrderNo(),
                    context.getTotalAmount(),
                    context.getDiscountAmount(),
                    context.getPayAmount(),
                    context.getTransactionPayload(),
                    context.getSkuMap()
            );

            // 将 orderId 回写到 context（供后续使用）
            context.setOrderId(order.getId());

            log.info("[事务消息] 本地事务执行成功, orderNo={}, orderId={}",
                    context.getOrderNo(), order.getId());
            return RocketMQLocalTransactionState.COMMIT;

        } catch (Exception e) {
            log.error("[事务消息] 本地事务执行失败, orderNo={}", context.getOrderNo(), e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 事务回查
     * <p>
     * 当 Broker 长时间（默认 60s）未收到 Commit/Rollback 时，主动回查本地事务状态。
     * </p>
     * <p>
     * 回查策略：查本地消息表（与订单同事务写入）
     * - 本地消息表有记录 → 说明本地事务已提交 → COMMIT
     * - 本地消息表无记录 → 说明本地事务未提交或已回滚 → ROLLBACK
     * </p>
     * <p>
     * 为什么查本地消息表而不是查订单表？
     * 因为本地消息表和订单表在同一个事务中写入，状态完全一致。
     * 而且本地消息表有 transactionId（orderNo）索引，查询更快。
     * </p>
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String orderNo = (String) msg.getHeaders().get("orderNo");
        String userIdStr = (String) msg.getHeaders().get("userId");
        if (orderNo == null) {
            log.error("[事务消息] 回查失败: 消息中缺少 orderNo header");
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        try {
            // 查本地消息表：transactionId = orderNo
            // 分库分表后，如果有 userId 则精确路由，否则全库扫描（兜底）
            LocalMessage localMessage;
            if (userIdStr != null) {
                Long userId = Long.parseLong(userIdStr);
                localMessage = localMessageMapper.selectByTransactionIdAndUserId(orderNo, userId);
            } else {
                localMessage = localMessageMapper.selectByTransactionId(orderNo);
            }

            if (localMessage != null) {
                log.info("[事务消息] 回查: 本地事务已提交, orderNo={}", orderNo);
                return RocketMQLocalTransactionState.COMMIT;
            } else {
                log.warn("[事务消息] 回查: 本地事务未提交, orderNo={}", orderNo);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            // 查询异常时返回 UNKNOWN，Broker 会稍后再次回查
            log.error("[事务消息] 回查异常, orderNo={}", orderNo, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * 下单上下文（半消息发送时传入，本地事务执行时使用）
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OrderCreateContext {
        private Long userId;
        private OrderCreateRequest request;
        private String orderNo;
        private java.math.BigDecimal totalAmount;
        private java.math.BigDecimal discountAmount;
        private java.math.BigDecimal payAmount;
        /** 事务消息的完整 payload JSON（与 MQ 消息体一致，本地消息表存储用） */
        private String transactionPayload;
        /** 本地事务执行后回写 */
        private Long orderId;
        /** SKU 详情 Map（skuId → SkuInfoDTO），避免事务内再调 Feign */
        private java.util.Map<Long, com.myxhs.order.dto.SkuInfoDTO> skuMap;
    }
}
