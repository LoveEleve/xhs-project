package com.myxhs.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.order.entity.LocalMessage;
import com.myxhs.order.entity.Order;
import com.myxhs.order.enums.TerminationReason;
import com.myxhs.order.mapper.LocalMessageMapper;
import com.myxhs.order.mapper.OrderMapper;
import com.myxhs.order.mapper.SubOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 订单终结本地事务服务（04B：唯一终结入口的事务内核）
 * <p>
 * 独立 Service 承载 @Transactional（与 OrderTransactionService 同模式，避免类内自调用代理失效），
 * 由 {@link com.myxhs.order.listener.OrderTerminationListener} 在事务消息回调中调用
 * （与下单链路 OrderTransactionListener → OrderTransactionService 完全同构）。
 * </p>
 * <p>
 * 事务内核职责（原子完成，任一失败整体回滚）：
 * 1. 加载订单（分片键 userId 精确路由）
 * 2. 状态校验：仅 status=0 可终结；4/5 幂等返回；其余拒绝
 * 3. Event Sourcing 状态迁移（0→4，appendEvent 白名单 + 乐观锁；冲突抛异常整体回滚）
 * 4. **全部子单同事务取消**（0→4，ITER-001 拆单：父单+子单原子收敛）
 * 5. 终结事件写本地消息表（与状态迁移同库同事务，保证"状态已终结"与"事件已持久化"原子）
 * </p>
 * <p>
 * 事件载荷由调用方（terminateOrder）在发送半消息前构造并传入——半消息 COMMIT 后可见的消息体
 * 与本地消息表 payload 必须**同源**（消费者与补发路径解析同一 schema，见 §十一 Review H3）。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTerminationService {

    /** 本地消息表 operationType（终结事件）：回查/标记成功/补发均按此类型过滤——
     *  同一订单 transactionId（orderNo）下还有创建侧 ORDER_CREATED 行，不区分会互相误判 */
    public static final String OPERATION_TYPE_ORDER_TERMINATED = "ORDER_TERMINATED";

    private final OrderMapper orderMapper;
    private final LocalMessageMapper localMessageMapper;
    private final OrderEventService orderEventService;
    private final SubOrderMapper subOrderMapper;

    /** 终结执行结果 */
    public enum Outcome {
        /** 终结成功：0→4 迁移 + 终结事件已落本地消息表 */
        TERMINATED,
        /** 已是终态（4/5）：幂等跳过，无需重复终结 */
        ALREADY_TERMINAL,
        /** 订单不存在（含跨用户越权访问：按 userId+orderId 精确查询） */
        NOT_FOUND,
        /** 当前状态不允许终结（已付款/已发货/已完成）：终结是"未支付订单"的语义 */
        INVALID_STATUS
    }

    /**
     * 执行终结本地事务（详见类注释）。
     *
     * @param orderId      订单ID
     * @param userId       用户ID（分片键）
     * @param reason       终结原因
     * @param source       终结来源（APP / TIMEOUT_JOB / SYSTEM）
     * @param eventPayload 终结事件载荷 JSON（与事务消息体同源）
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public Outcome executeLocalTransaction(Long orderId, Long userId,
                                           TerminationReason reason, String source, String eventPayload) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
        if (order == null) {
            return Outcome.NOT_FOUND;
        }

        Integer status = order.getStatus();
        if (status != null && (status == 4 || status == 5)) {
            log.info("[订单终结] 已是终态, 幂等跳过: orderId={}, status={}", orderId, status);
            return Outcome.ALREADY_TERMINAL;
        }
        if (status == null || status != 0) {
            log.warn("[订单终结] 状态不允许终结: orderId={}, status={}, reason={}", orderId, status, reason);
            return Outcome.INVALID_STATUS;
        }

        // 1. Event Sourcing 状态迁移（0→4）：白名单 + 序号/乐观锁冲突直接抛异常整体回滚
        //    （回滚 → 监听器返回 ROLLBACK → 调用方按"回读收敛/请刷新"语义处理）
        orderEventService.appendEvent(order, reason.getEventType(),
                Map.of(
                        "cancelReason", reason.getDisplayName(),
                        "source", source,
                        "terminateTime", LocalDateTime.now().toString()));
        orderMapper.setCancelledAt(orderId, userId);

        // 2. 全部子单同事务取消（ITER-001 拆单：父单+子单原子收敛；仅未支付子单 0→4）
        int cancelledSubs = subOrderMapper.cancelAll(orderId, userId);
        log.info("[订单终结] 子单取消: orderId={}, cancelledSubs={}", orderId, cancelledSubs);

        // 3. 终结事件写本地消息表：与状态迁移同本地事务，任一失败整体回滚
        LocalMessage message = new LocalMessage();
        message.setUserId(userId); // 分片键冗余存储
        message.setTransactionId(order.getOrderNo());
        message.setServiceName("order");
        message.setOperationType(OPERATION_TYPE_ORDER_TERMINATED);
        message.setPayload(eventPayload);
        message.setStatus(0); // 待处理
        message.setRetryCount(0);
        message.setNextRetryTime(LocalDateTime.now()); // 初始补发时间: 立即
        localMessageMapper.insert(message);

        log.info("[订单终结] 本地事务完成: orderId={}, orderNo={}, reason={}, source={}",
                orderId, order.getOrderNo(), reason.name(), source);
        return Outcome.TERMINATED;
    }
}
