package com.myxhs.order.listener;

import com.myxhs.order.config.OrderTerminationRocketMQConfig;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.enums.TerminationReason;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.service.OrderTerminationService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 事务消息监听器 — 订单终结链路（04B）
 * <p>
 * 与下单链路（OrderTransactionListener）完全同构，绑定独立模板/独立生产者组
 * （见 {@link OrderTerminationRocketMQConfig}）：
 * 1. terminateOrder → orderTerminationRocketMQTemplate: 发送半消息（Half Message）
 * 2. executeLocalTransaction(): 执行本地事务（状态 0→4 + 终结事件写本地消息表，同一 DB 事务）
 * 3a. 成功 → COMMIT → 事件对 inventory/coupon/notification 消费者可见
 * 3b. 失败 → ROLLBACK → 半消息删除
 * 4. Broker 未收到 Commit/Rollback → checkLocalTransaction() 回查本地消息表
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQTransactionListener(rocketMQTemplateBeanName = OrderTerminationRocketMQConfig.TERMINATION_TEMPLATE_BEAN_NAME)
public class OrderTerminationListener implements RocketMQLocalTransactionListener {

    private final OrderTerminationService terminationService;
    private final LocalMessageMapper localMessageMapper;

    /**
     * 执行本地事务（半消息发送成功后由 RocketMQ 回调）。
     * <p>
     * 状态结果（NOT_FOUND / ALREADY_TERMINAL / INVALID_STATUS）通过 context 回写，
     * 调用方 terminateOrder 据此返回业务语义；异常（并发竞态/DB 异常）→ ROLLBACK 且 outcome 保持 null。
     * </p>
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        OrderTerminationContext context = (OrderTerminationContext) arg;
        try {
            OrderTerminationService.Outcome outcome = terminationService.executeLocalTransaction(
                    context.getOrderId(), context.getUserId(), context.getReason(),
                    context.getSource(), context.getEventPayload());
            context.setOutcome(outcome);
            log.info("[终结事务] 本地事务执行完成: orderId={}, orderNo={}, outcome={}",
                    context.getOrderId(), context.getOrderNo(), outcome);
            return outcome == OrderTerminationService.Outcome.TERMINATED
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            // 并发竞态（appendEvent 乐观锁/序号冲突）或 DB 异常 → 整体回滚；
            // outcome 保持 null，调用方按"回读收敛 / 状态已变化"语义处理
            log.error("[终结事务] 本地事务执行失败(回滚): orderId={}, orderNo={}",
                    context.getOrderId(), context.getOrderNo(), e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 事务回查（Broker 长时间未收到 Commit/Rollback 时回调）。
     * <p>
     * 回查策略：查本地消息表（与状态迁移同事务写入）。
     * **必须按 operation_type='ORDER_TERMINATED' 过滤**：同一订单的 transactionId（orderNo）下
     * 还存在创建侧 ORDER_CREATED 行——若不过滤，终结事务回滚（如并发支付先赢）时回查会误命中
     * 创建行并 COMMIT，导致消费者对"未终结订单"执行释放库存/退券/通知（资损路径）。
     * </p>
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String orderNo = (String) msg.getHeaders().get("orderNo");
        String userIdStr = (String) msg.getHeaders().get("userId");
        if (orderNo == null) {
            log.error("[终结事务] 回查失败: 消息中缺少 orderNo header");
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            LocalMessage localMessage;
            if (userIdStr != null) {
                // 有 userId → 按分片键精确路由
                localMessage = localMessageMapper.selectByTransactionIdAndUserIdAndOperationType(
                        orderNo, Long.parseLong(userIdStr),
                        OrderTerminationService.OPERATION_TYPE_ORDER_TERMINATED);
            } else {
                // 无 userId 兜底（广播查询）
                localMessage = localMessageMapper.selectByTransactionIdAndOperationType(
                        orderNo, OrderTerminationService.OPERATION_TYPE_ORDER_TERMINATED);
            }
            if (localMessage != null) {
                log.info("[终结事务] 回查: 本地事务已提交, orderNo={}", orderNo);
                return RocketMQLocalTransactionState.COMMIT;
            }
            log.warn("[终结事务] 回查: 本地事务未提交(或无终结记录), orderNo={}", orderNo);
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            // 查询异常时返回 UNKNOWN，Broker 会稍后再次回查
            log.error("[终结事务] 回查异常, orderNo={}", orderNo, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * 终结上下文（半消息发送时传入，本地事务执行时使用；outcome 由本地事务回写）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderTerminationContext {
        private Long orderId;
        private Long userId;
        private String orderNo;
        private TerminationReason reason;
        private String source;
        /** 终结事件载荷 JSON（与事务消息体同源，本地消息表存储用） */
        private String eventPayload;
        /** 本地事务结果；异常回滚时保持 null（调用方按"回读收敛/状态已变化"语义处理） */
        private OrderTerminationService.Outcome outcome;
    }
}
