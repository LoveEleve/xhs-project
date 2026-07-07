package com.myxhs.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.common.entity.CompensationMessage;
import com.myxhs.common.response.R;
import com.myxhs.order.dto.request.OrderCreateRequest;
import com.myxhs.order.dto.response.OrderVO;
import com.myxhs.order.entity.*;
import com.myxhs.order.feign.CouponFeignClient;
import com.myxhs.order.feign.InventoryFeignClient;
import com.myxhs.order.listener.OrderTransactionListener;
import com.myxhs.order.mapper.*;
import com.myxhs.order.repository.OrderNoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单服务
 * <p>
 * 核心设计：
 * 1. 幂等下单：bizIdentifier + Redis SET NX（24h 过期）
 * 2. 分布式锁：同一用户 10 秒内只能下 1 单（Lua 安全释放）
 * 3. 本地事务：创建订单 + 写本地消息表（独立 Service 保证 @Transactional 生效）
 * 4. 超时关单：RocketMQ 延时消息（30 分钟）+ 定时任务兜底
 * 5. 状态机：乐观锁状态流转（WHERE status = 期望状态）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderSnapshotMapper snapshotMapper;
    private final OrderNoMappingRepository orderNoMappingRepository;
    private final OrderTransactionService transactionService; // 独立事务服务
    private final OrderEventService orderEventService; // Event Sourcing
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryFeignClient inventoryFeignClient;
    private final CouponFeignClient couponFeignClient;
    private final BusinessMetrics businessMetrics;

    private static final String IDEMPOTENT_KEY_PREFIX = "order:idempotent:";
    private static final String CREATE_LOCK_PREFIX = "order:create:lock:";
    private static final String ORDER_CLOSE_TOPIC = "ORDER_CLOSE_TOPIC";
    /** 事务消息 Topic：下单成功后通知下游服务（库存预扣、优惠券核销等） */
    public static final String ORDER_TRANSACTION_TOPIC = "ORDER_TRANSACTION_TOPIC";
    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /**
     * Lua 脚本：安全释放分布式锁（只释放自己持有的锁）
     * <p>
     * 为什么必须用 Lua？
     * 如果用 GET + DEL 两步操作，在 GET 和 DEL 之间锁可能已过期被其他线程获取，
     * DEL 会误删别人的锁。Lua 保证 GET + 比较 + DEL 是原子的。
     * </p>
     */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end";

    // ==================== 创建订单 ====================

    /**
     * 创建订单
     * <p>
     * 流程：
     * 1. 幂等校验（bizIdentifier + Redis SET NX）
     * 2. 分布式锁（同一用户 10 秒内只能下 1 单，Lua 安全释放）
     * 3. 计算金额
     * 4. 本地事务：INSERT 订单 + 订单明细 + 本地消息表（独立 Service）
     * 5. 发送延时消息（30 分钟后超时关单）
     * 6. 记录订单快照
     * </p>
     * <p>
     * 幂等键释放策略：
     * - 本地事务提交前失败 → 释放幂等键（允许重试）
     * - 本地事务提交后 → 不释放幂等键（订单已创建，后续步骤失败不影响）
     * </p>
     */
    public OrderVO createOrder(Long userId, OrderCreateRequest request) {
        long startTime = System.currentTimeMillis();
        businessMetrics.recordOrderCreated("attempt");

        // 1. 幂等校验
        String idempotentKey = IDEMPOTENT_KEY_PREFIX + request.getBizIdentifier();
        Boolean setResult = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(setResult)) {
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "请勿重复下单");
        }

        // 2. 分布式锁（value = UUID，释放时用 Lua 比较后删除）
        String lockKey = CREATE_LOCK_PREFIX + userId;
        String lockValue = UUID.randomUUID().toString();
        Boolean lockResult = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(lockResult)) {
            stringRedisTemplate.delete(idempotentKey);
            throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, "操作过于频繁，请稍后重试");
        }

        try {
            // 3. 计算金额
            String orderNo = generateOrderNo(userId);
            BigDecimal totalAmount = calculateTotalAmount(request.getSkuItems());
            BigDecimal discountAmount = request.getCouponId() != null
                    ? new BigDecimal("10.00") // Mock: 优惠券固定减 10 元
                    : BigDecimal.ZERO;
            BigDecimal payAmount = totalAmount.subtract(discountAmount);
            if (payAmount.compareTo(BigDecimal.ZERO) < 0) {
                payAmount = BigDecimal.ZERO;
            }

            // 4. 发送事务消息（半消息 → 本地事务 → Commit/Rollback）
            //    本地事务在 OrderTransactionListener.executeLocalTransaction() 中执行

            // 先构建 payload（本地消息表和 MQ 消息体共用同一份 JSON）
            String payload = buildTransactionPayload(orderNo, userId, request);

            OrderTransactionListener.OrderCreateContext context =
                    OrderTransactionListener.OrderCreateContext.builder()
                            .userId(userId)
                            .request(request)
                            .orderNo(orderNo)
                            .totalAmount(totalAmount)
                            .discountAmount(discountAmount)
                            .payAmount(payAmount)
                            .transactionPayload(payload)
                            .build();

            // 构建事务消息
            org.springframework.messaging.Message<String> msg = MessageBuilder.withPayload(payload)
                    .setHeader("orderNo", orderNo)
                    .setHeader("userId", userId.toString())
                    .setHeader(org.apache.rocketmq.spring.support.RocketMQHeaders.KEYS, orderNo)
                    .build();

            // 发送事务消息（半消息）
            // RocketMQ 收到半消息后回调 OrderTransactionListener.executeLocalTransaction()
            SendResult sendResult = rocketMQTemplate.sendMessageInTransaction(
                    ORDER_TRANSACTION_TOPIC, msg, context);

            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                // 半消息发送失败，释放幂等键允许重试
                stringRedisTemplate.delete(idempotentKey);
                throw new BizException(ResultCode.INTERNAL_ERROR, "下单失败: 消息发送异常");
            }

            // 5. 本地事务已在 Listener 中执行完毕，获取 orderId
            if (context.getOrderId() == null) {
                // 本地事务执行失败（Listener 返回了 ROLLBACK）
                stringRedisTemplate.delete(idempotentKey);
                throw new BizException(ResultCode.INTERNAL_ERROR, "下单失败: 本地事务回滚");
            }

            // 6. 发送延时消息（30 分钟后超时关单）—— 失败不影响下单
            sendCloseDelayMessage(context.getOrderId(), orderNo, userId);

            // 7. 记录订单创建事件（Event Sourcing）—— 失败不影响下单
            try {
                Order order = orderMapper.selectOne(
                        new LambdaQueryWrapper<Order>()
                                .eq(Order::getUserId, userId)
                                .eq(Order::getId, context.getOrderId()));
                if (order != null) {
                    orderEventService.appendEvent(order, OrderEventService.EVENT_CREATED, 
                            Map.of("orderNo", orderNo, "source", "APP"));
                }
            } catch (Exception e) {
                log.error("[订单] 事件记录失败: orderId={}", context.getOrderId(), e);
            }

            // 8. 记录订单快照 —— 失败不影响下单
            takeSnapshot(context.getOrderId(), userId, "CREATED");

            // 9. 异步写入订单号映射表（解决非分片键查询路由问题）
            saveOrderNoMapping(orderNo, userId, context.getOrderId());

            log.info("[订单] 创建成功(事务消息): userId={}, orderNo={}, payAmount={}",
                    userId, orderNo, payAmount);

            businessMetrics.recordOrderCreated("success");
            businessMetrics.recordOrderCreateLatency(System.currentTimeMillis() - startTime);

            // 查询完整订单信息返回
            Order order = orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getUserId, userId)
                            .eq(Order::getId, context.getOrderId()));
            return buildOrderVO(order);

        } catch (BizException e) {
            // 业务异常：事务未提交，释放幂等键允许重试
            businessMetrics.recordOrderCreated("fail");
            stringRedisTemplate.delete(idempotentKey);
            throw e;
        } catch (Exception e) {
            businessMetrics.recordOrderCreated("fail");
            stringRedisTemplate.delete(idempotentKey);
            throw new BizException(ResultCode.INTERNAL_ERROR, "下单失败: " + e.getMessage());
        } finally {
            // Lua 安全释放锁（只释放自己持有的锁）
            safeUnlock(lockKey, lockValue);
        }
    }

    /**
     * 构建事务消息 Payload（下游消费者使用）
     * <p>
     * 包含订单号、用户ID、SKU 列表等信息，
     * 下游库存服务消费后执行预扣减。
     * </p>
     */
    private String buildTransactionPayload(String orderNo, Long userId, OrderCreateRequest request) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "orderNo", orderNo,
                    "userId", userId,
                    "skuItems", request.getSkuItems(),
                    "couponId", request.getCouponId() != null ? request.getCouponId() : 0L,
                    "timestamp", System.currentTimeMillis()
            ));
        } catch (Exception e) {
            return "{\"orderNo\":\"" + orderNo + "\"}";
        }
    }

    // ==================== 查询 ====================

    /**
     * 查询订单详情
     */
    public OrderVO getOrderDetail(Long userId, Long orderId) {
        // 分库分表后，selectById 无法路由（缺少分片键）
        // 必须通过 user_id + id 联合查询
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }

        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getUserId, userId)
                        .eq(OrderItem::getOrderId, orderId));

        return OrderVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .totalAmount(order.getTotalAmount())
                .payAmount(order.getPayAmount())
                .discountAmount(order.getDiscountAmount())
                .status(order.getStatus())
                .statusDesc(getStatusDesc(order.getStatus()))
                .remark(order.getRemark())
                .addressSnapshot(order.getAddressSnapshot())
                .createdAt(order.getCreatedAt())
                .paidAt(order.getPaidAt())
                .items(items.stream().map(item -> OrderVO.OrderItemVO.builder()
                        .skuId(item.getSkuId())
                        .skuName(item.getSkuName())
                        .skuImage(item.getSkuImage())
                        .price(item.getPrice())
                        .quantity(item.getQuantity())
                        .totalAmount(item.getTotalAmount())
                        .build()).collect(Collectors.toList()))
                .build();
    }

    /**
     * 查询用户订单列表
     */
    public List<OrderVO> getUserOrders(Long userId, Integer status) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreatedAt);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }

        List<Order> orders = orderMapper.selectList(wrapper);
        return orders.stream().map(this::buildOrderVO).collect(Collectors.toList());
    }

    // ==================== 取消订单 ====================

    /**
     * 取消订单（只能取消待付款的订单）
     * <p>
     * 状态流转：0(待付款) → 4(已取消)
     * 乐观锁：WHERE status = 0
     * </p>
     */
    public void cancelOrder(Long userId, Long orderId) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() != 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "只能取消待付款的订单");
        }

        // Event Sourcing: 追加取消事件并更新状态
        orderEventService.appendEvent(order, OrderEventService.EVENT_CANCELLED, 
                Map.of("cancelReason", "用户主动取消", "cancelTime", LocalDateTime.now().toString()));

        // 联动释放库存 + 退还优惠券（并行执行，互不依赖，带超时控制）
        CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(() -> releaseInventory(orderId, userId));
        CompletableFuture<Void> returnFuture = CompletableFuture.runAsync(() -> returnCouponIfUsed(order));
        try {
            CompletableFuture.allOf(releaseFuture, returnFuture).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[订单] 取消订单Feign并行调用超时/异常，降级依赖补偿机制: orderId={}", orderId, e);
        }

        takeSnapshot(orderId, userId, "CANCELLED");
        stringRedisTemplate.delete("order:info:" + orderId);
        log.info("[订单] 取消成功: userId={}, orderId={}", userId, orderId);
    }

    /**
     * 联动释放库存
     * <p>
     * 通过 Feign 调用库存服务释放预扣库存。
     * 如果库存服务不可用，降级记录日志，由定时任务补偿重试。
     * 幂等保证：库存服务内部通过 orderId 做幂等（同一订单重复释放不会多加库存）。
     * </p>
     */
    private void releaseInventory(Long orderId, Long userId) {
        try {
            Map<String, Object> request = Map.of("orderId", orderId);
            R<Void> result = inventoryFeignClient.releaseStock(request);
            if (result == null || !result.isSuccess()) {
                log.error("[订单] 释放库存失败，需补偿: orderId={}, result={}", orderId, result);
                // 发送补偿消息到 MQ，由补偿消费者重试
                sendCompensationMessage("RELEASE_STOCK", orderId, userId,
                        "Feign返回失败: " + (result != null ? result.getMessage() : "null"));
            } else {
                log.info("[订单] 释放库存成功: orderId={}", orderId);
            }
        } catch (Exception e) {
            log.error("[订单] 释放库存异常，需补偿: orderId={}", orderId, e);
            sendCompensationMessage("RELEASE_STOCK", orderId, userId, e.getMessage());
        }
    }

    /**
     * 联动退还优惠券
     * <p>
     * 如果订单使用了优惠券（couponId != null），通过 Feign 调用优惠券服务退还。
     * 如果优惠券服务不可用，降级记录日志，由定时任务补偿重试。
     * 幂等保证：优惠券服务内部通过 orderId 做幂等（同一订单重复退券不会多退）。
     * </p>
     */
    private void returnCouponIfUsed(Order order) {
        if (order.getCouponId() == null) {
            return; // 未使用优惠券，无需退还
        }
        try {
            Map<String, Object> request = Map.of(
                    "userCouponId", order.getCouponId(),
                    "orderId", order.getId()
            );
            R<Void> result = couponFeignClient.returnCoupon(order.getUserId(), request);
            if (result == null || !result.isSuccess()) {
                log.error("[订单] 退还优惠券失败，需补偿: orderId={}, couponId={}, result={}",
                        order.getId(), order.getCouponId(), result);
                sendCompensationMessage("RETURN_COUPON", order.getId(), order.getUserId(),
                        "Feign返回失败: " + (result != null ? result.getMessage() : "null"));
            } else {
                log.info("[订单] 退还优惠券成功: orderId={}, couponId={}", order.getId(), order.getCouponId());
            }
        } catch (Exception e) {
            log.error("[订单] 退还优惠券异常，需补偿: orderId={}, couponId={}", order.getId(), order.getCouponId(), e);
            sendCompensationMessage("RETURN_COUPON", order.getId(), order.getUserId(), e.getMessage());
        }
    }

    /**
     * 发送补偿消息到 MQ
     * <p>
     * 当 Feign 调用失败时，发送补偿消息到专用 Topic，
     * 由 OrderCompensationConsumer 消费并重试。保证最终一致性。
     * </p>
     */
    private void sendCompensationMessage(String action, Long orderId, Long userId, String failReason) {
        try {
            CompensationMessage cm = new CompensationMessage();
            cm.setAction(action);
            cm.setOrderId(orderId);
            cm.setFailReason(failReason);
            cm.setTimestamp(System.currentTimeMillis());
            String payload = objectMapper.writeValueAsString(cm);
            rocketMQTemplate.syncSend("ORDER_COMPENSATION_TOPIC",
                    org.springframework.messaging.support.MessageBuilder.withPayload(payload)
                            .setHeader("userId", userId.toString())
                            .build(),
                    3000);
            log.info("[订单] 补偿消息已发送: action={}, orderId={}", action, orderId);
        } catch (Exception e) {
            // MQ 也发送失败，只能依赖定时任务扫描订单状态来补偿
            log.error("[订单] 补偿消息发送失败，依赖定时任务兜底: action={}, orderId={}", action, orderId, e);
        }
    }

    // ==================== 确认收货 ====================

    /**
     * 确认收货（状态流转：2 → 3）
     */
    public void confirmReceive(Long userId, Long orderId) {
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() != 2) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "只能确认已发货的订单");
        }

        // Event Sourcing: 追加完成事件并更新状态
        orderEventService.appendEvent(order, OrderEventService.EVENT_COMPLETED,
                Map.of("completeTime", LocalDateTime.now().toString()));

        takeSnapshot(orderId, userId, "COMPLETED");
        stringRedisTemplate.delete("order:info:" + orderId);
        log.info("[订单] 确认收货: userId={}, orderId={}", userId, orderId);
    }

    // ==================== 支付成功回调 ====================

    /**
     * 支付成功后更新订单状态
     * <p>
     * 状态流转：0(待付款) → 1(已付款)
     * 乐观锁保证幂等：WHERE status = 0
     * </p>
     * <p>
     * 分库分表兼容：
     * 支付回调来自支付服务，userId 可能为 null（Feign 调用无 X-User-Id Header）。
     * 当 userId 为 null 时，通过映射表反查。
     * </p>
     *
     * @return true=更新成功, false=订单已不是待付款状态
     */
    public boolean onPaymentSuccess(Long orderId, Long userId) {
        // 分库分表后 markPaid 需要 userId 作为分片键路由
        if (userId == null) {
            OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
            if (mapping == null) {
                log.error("[订单] 支付回调但映射表中找不到orderId: orderId={}", orderId);
                return false;
            }
            userId = mapping.getUserId();
        }

        // 查询订单（需要 userId + orderId 联合查询）
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
        if (order == null) {
            log.warn("[订单] 支付回调但订单不存在: orderId={}", orderId);
            return false;
        }
        if (order.getStatus() != 0) {
            log.warn("[订单] 支付回调但订单状态不是待付款: orderId={}", orderId);
            return false; // 订单已取消或已支付
        }

        // Event Sourcing: 追加支付事件并更新状态
        orderEventService.appendEvent(order, OrderEventService.EVENT_PAID,
                Map.of("paidTime", LocalDateTime.now().toString()));

        takeSnapshot(orderId, userId, "PAID");
        stringRedisTemplate.delete("order:info:" + orderId);
        log.info("[订单] 支付成功: orderId={}", orderId);
        return true;
    }

    // ==================== 超时关单 ====================

    /**
     * 超时关单（延时消息触发或定时任务调用）
     * <p>
     * 只关"待付款"状态的订单（幂等）。
     * 乐观锁 WHERE status = 0 保证与支付不会冲突。
     * </p>
     */
    public void closeTimeoutOrder(Long orderId, Long userId) {
        try {
            Order order = orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getUserId, userId)
                            .eq(Order::getId, orderId));
            if (order == null || order.getStatus() != 0) {
                return; // 幂等
            }

            // Event Sourcing: 追加超时取消事件并更新状态
            orderEventService.appendEvent(order, OrderEventService.EVENT_TIMEOUT_CANCELLED,
                    Map.of("cancelReason", "超时未支付", "cancelTime", LocalDateTime.now().toString()));

            // 联动释放库存（与 cancelOrder 复用同一逻辑）
            releaseInventory(orderId, userId);

            // 联动退还优惠券（与 cancelOrder 复用同一逻辑）
            returnCouponIfUsed(order);

            takeSnapshot(orderId, userId, "TIMEOUT_CANCELLED");
            stringRedisTemplate.delete("order:info:" + orderId);
            log.info("[订单] 超时关单: orderId={}, orderNo={}", orderId, order.getOrderNo());

        } catch (Exception e) {
            log.error("[订单] 超时关单异常, 发送补偿消息: orderId={}, userId={}", orderId, userId, e);
            sendCompensationMessage("CLOSE_ORDER", orderId, userId, e.getMessage());
        }
    }

    // ==================== 私有方法 ====================

    /**
     * Lua 安全释放分布式锁
     * <p>
     * 只有当锁的 value 等于当前持有者的 value 时才删除。
     * 防止业务执行超时后误删其他线程的锁。
     * </p>
     */
    private void safeUnlock(String lockKey, String lockValue) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
            stringRedisTemplate.execute(script, Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("[订单] 释放锁异常(不影响业务): key={}", lockKey, e);
        }
    }

    /**
     * 发送延时消息（30 分钟后超时关单）
     */
    private void sendCloseDelayMessage(Long orderId, String orderNo, Long userId) {
        try {
            String payload = orderId.toString();
            SendResult result = rocketMQTemplate.syncSend(
                    ORDER_CLOSE_TOPIC,
                    MqTraceHelper.wrapWithTraceId(
                            MessageBuilder.withPayload(payload)
                                    .setHeader("orderId", orderId)
                                    .setHeader("orderNo", orderNo)
                                    .setHeader("userId", userId.toString())
                                    .build()),
                    3000, 16); // delayLevel=16 = 30 分钟
            if (result.getSendStatus() != SendStatus.SEND_OK) {
                log.warn("[订单] 延时关单消息发送状态异常: orderId={}, status={}",
                        orderId, result.getSendStatus());
            }
        } catch (Exception e) {
            log.error("[订单] 延时关单消息发送失败(定时任务兜底): orderId={}", orderId, e);
        }
    }

    /**
     * 记录订单快照（失败不影响主流程）
     */
    private void takeSnapshot(Long orderId, Long userId, String event) {
        try {
            Order order = orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getUserId, userId)
                            .eq(Order::getId, orderId));
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>()
                            .eq(OrderItem::getUserId, userId)
                            .eq(OrderItem::getOrderId, orderId));

            OrderSnapshot snapshot = new OrderSnapshot();
            snapshot.setOrderId(orderId);
            snapshot.setUserId(userId); // 分片键
            snapshot.setEvent(event);
            snapshot.setSnapshotData(objectMapper.writeValueAsString(
                    java.util.Map.of("order", order, "items", items,
                            "timestamp", LocalDateTime.now().toString())));
            snapshotMapper.insert(snapshot);
        } catch (Exception e) {
            log.error("[订单] 快照记录失败: orderId={}, event={}", orderId, event, e);
        }
    }

    /**
     * 生成订单号：ORD + 时间戳(17位) + userId后4位 + 序列号(4位)
     * <p>
     * 使用 Redis INCR 获取全局唯一序列号，替代进程内 AtomicLong。
     * 多实例部署时，AtomicLong 各自自增会导致同一毫秒内序列号碰撞。
     * Redis INCR 是单线程原子操作，保证跨实例全局唯一。
     * Key 设计：order:seq:{date} —— 按天分 Key，自动过期防止 Key 堆积。
     * </p>
     */
    private String generateOrderNo(Long userId) {
        String timestamp = LocalDateTime.now().format(ORDER_NO_FORMAT);
        String userSuffix = String.format("%04d", userId % 10000);
        String dateKey = "order:seq:" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long seq;
        try {
            seq = stringRedisTemplate.opsForValue().increment(dateKey);
            // 按天分 Key，第二天自动切换到新 Key，设置 48h 过期防止 Key 堆积
            if (seq != null && seq == 1L) {
                stringRedisTemplate.expire(dateKey, 48, TimeUnit.HOURS);
            }
        } catch (Exception e) {
            // Redis 不可用时降级为时间戳 + 随机数（概率碰撞，但保业务可用）
            log.error("[订单] Redis INCR 失败，降级使用随机序列号, userId={}", userId, e);
            seq = (long) (Math.random() * 10000);
        }
        String seqStr = String.format("%04d", (seq != null ? seq : 0L) % 10000);
        return "ORD" + timestamp + userSuffix + seqStr;
    }

    /**
     * 计算订单总金额（MVP Mock：每个 SKU 单价 99 元）
     */
    private BigDecimal calculateTotalAmount(List<OrderCreateRequest.SkuItem> skuItems) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderCreateRequest.SkuItem item : skuItems) {
            total = total.add(new BigDecimal("99.00").multiply(
                    BigDecimal.valueOf(item.getQuantity())));
        }
        return total;
    }

    private OrderVO buildOrderVO(Order order) {
        return OrderVO.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .totalAmount(order.getTotalAmount())
                .payAmount(order.getPayAmount())
                .discountAmount(order.getDiscountAmount())
                .status(order.getStatus())
                .statusDesc(getStatusDesc(order.getStatus()))
                .createdAt(order.getCreatedAt())
                .paidAt(order.getPaidAt())
                .build();
    }

    private String getStatusDesc(Integer status) {
        return switch (status) {
            case 0 -> "待付款";
            case 1 -> "已付款";
            case 2 -> "已发货";
            case 3 -> "已完成";
            case 4 -> "已取消";
            case 5 -> "已退款";
            default -> "未知";
        };
    }

    /**
     * 保存订单号映射（解决非分片键查询路由问题）
     * <p>
     * 写入公共库 my_xhs_order 的 t_order_no_mapping 表。
     * 失败不影响主流程（最终一致性：定时任务补录）。
     * </p>
     */
    private void saveOrderNoMapping(String orderNo, Long userId, Long orderId) {
        try {
            OrderNoMapping mapping = new OrderNoMapping();
            mapping.setOrderNo(orderNo);
            mapping.setUserId(userId);
            mapping.setOrderId(orderId);
            orderNoMappingRepository.insert(mapping);
        } catch (Exception e) {
            log.error("[订单] 订单号映射写入失败(不影响主流程): orderNo={}, userId={}",
                    orderNo, userId, e);
        }
    }

    /**
     * 通过订单号查询订单（非分片键查询）
     * <p>
     * 流程：订单号 → 映射表查 user_id → 路由到分片库查询
     * 场景：客服通过订单号查询、支付回调通过订单号定位订单
     * </p>
     */
    public OrderVO getOrderByOrderNo(String orderNo) {
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderNo(orderNo);
        if (mapping == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND, "订单不存在");
        }
        return getOrderDetail(mapping.getUserId(), mapping.getOrderId());
    }

    /**
     * 退款成功回调
     * <p>
     * 由独立支付服务退款成功后通过 Feign 回调。
     * 执行操作：
     * 1. 乐观锁更新订单状态为"已退款(5)"
     * 2. 释放库存
     * 3. 退还优惠券
     * </p>
     * <p>
     * 幂等性：乐观锁 WHERE status=1(已支付) 保证不会重复退款。
     * 多实例安全：乐观锁是原子操作，不需要分布式锁。
     * </p>
     * <p>
     * 分库分表兼容：
     * 退款回调来自支付服务，只有 orderId 没有 userId，
     * 必须通过 t_order_no_mapping 映射表反查 userId，才能路由到正确的分片。
     * </p>
     */
    public void onRefundSuccess(Long orderId) {
        // 1. 通过映射表反查 userId（分库分表后 selectById 无法路由，必须带分片键）
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
        if (mapping == null) {
            log.error("[订单] 退款回调但映射表中找不到orderId: orderId={}", orderId);
            return;
        }
        Long userId = mapping.getUserId();

        // 2. 使用 userId + orderId 联合查询（分片键路由）
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
        if (order == null) {
            log.warn("[订单] 退款回调但订单不存在: orderId={}, userId={}", orderId, userId);
            return;
        }
        if (order.getStatus() != 1) {
            log.warn("[订单] 退款回调但订单状态不是已支付: orderId={}", orderId);
            return;
        }

        // 3. Event Sourcing: 追加退款事件并更新状态
        orderEventService.appendEvent(order, OrderEventService.EVENT_REFUNDED,
                Map.of("refundTime", LocalDateTime.now().toString()));

        // 4. 释放库存
        releaseInventory(orderId, userId);

        // 5. 退还优惠券
        returnCouponIfUsed(order);

        takeSnapshot(orderId, userId, "REFUNDED");
        stringRedisTemplate.delete("order:info:" + orderId);
        log.info("[订单] 退款成功: orderId={}", orderId);
    }

    /**
     * 查询订单支付金额
     * <p>
     * 供支付服务校验支付金额与订单金额是否匹配。
     * 同时被补偿任务间接用于判断订单状态：
     * - 返回非空金额 → 订单仍为待支付
     * - 返回成功但 data 为 null → 订单已不是待支付
     * </p>
     * <p>
     * 分库分表兼容：
     * 支付服务通过 Feign 调用时只有 orderId，没有 userId。
     * 必须通过 t_order_no_mapping 映射表反查 userId，才能路由到正确的分片。
     * </p>
     */
    public BigDecimal getOrderPayAmount(Long orderId) {
        // 通过映射表反查 userId（分库分表后 selectById 无法路由）
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
        if (mapping == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, mapping.getUserId())
                        .eq(Order::getId, orderId));
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        return order.getPayAmount();
    }
}