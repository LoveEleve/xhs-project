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
import com.myxhs.order.feign.ProductFeignClient;
import com.myxhs.order.feign.UserFeignClient;
import com.myxhs.order.config.OrderTerminationRocketMQConfig;
import com.myxhs.order.dto.SkuInfoDTO;
import com.myxhs.order.dto.UserAddressDTO;
import com.myxhs.order.dto.event.OrderTerminationEvent;
import com.myxhs.order.enums.TerminationReason;
import com.myxhs.order.listener.OrderTerminationListener;
import com.myxhs.order.listener.OrderTransactionListener;
import com.myxhs.order.mapper.*;
import com.myxhs.order.repository.AftersaleRepository;
import com.myxhs.order.repository.OrderNoMappingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    private final LocalMessageMapper localMessageMapper;
    private final OrderNoMappingRepository orderNoMappingRepository;
    private final OrderTransactionService transactionService; // 独立事务服务
    private final SubOrderMapper subOrderMapper; // 子订单（ITER-001 拆单）
    private final SubOrderService subOrderService; // 子单聚合/批量推进
    private final AftersaleRepository aftersaleRepository; // 发货拦截校验（决策 2）
    /** 终结事务消息模板（独立生产者组，见 OrderTerminationRocketMQConfig；与下单默认模板隔离事务监听器） */
    @Qualifier(OrderTerminationRocketMQConfig.TERMINATION_TEMPLATE_BEAN_NAME)
    private final RocketMQTemplate orderTerminationRocketMQTemplate;
    private final OrderEventService orderEventService; // Event Sourcing
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryFeignClient inventoryFeignClient;
    private final CouponFeignClient couponFeignClient;
    private final ProductFeignClient productFeignClient;
    private final UserFeignClient userFeignClient;
    private final BusinessMetrics businessMetrics;
    private final OrderNotificationPublisher orderNotificationPublisher;

    private static final String IDEMPOTENT_KEY_PREFIX = "myxhs:order:idempotent:";
    private static final String CREATE_LOCK_PREFIX = "myxhs:order:create:lock:";
    private static final String ORDER_CLOSE_TOPIC = "ORDER_CLOSE_TOPIC";
    private static final String ORDER_COMPENSATION_FALLBACK_KEY = "myxhs:order:compensation:pending";

    /** 【O2修复】异步补偿/联动任务线程池（MDC 感知，替代 CompletableFuture.runAsync 的 commonPool 丢 traceId） */
    private static final java.util.concurrent.ExecutorService ORDER_ASYNC_EXECUTOR =
            new com.myxhs.common.trace.MdcAwareExecutorService(
                    new java.util.concurrent.ThreadPoolExecutor(
                            2, 8, 60, java.util.concurrent.TimeUnit.SECONDS,
                            new java.util.concurrent.LinkedBlockingQueue<>(100),
                            r -> { Thread t = new Thread(r, "order-async"); t.setDaemon(true); return t; },
                            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()));
    /** 事务消息 Topic：下单成功后通知下游服务（库存预扣、优惠券核销等） */
    public static final String ORDER_TRANSACTION_TOPIC = "ORDER_TRANSACTION_TOPIC";
    /** 订单终结事件 Topic（04B：Outbox 形态，inventory/coupon/notification 订阅联动） */
    public static final String ORDER_TERMINATION_TOPIC = "ORDER_TERMINATION_TOPIC";
    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    @Value("${order.close.delay-level:16}")
    private int orderCloseDelayLevel; // 延时关单等级: 默认16=30min, 测试设5=1min

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
        // 幂等键带 userId：原实现不带 → 不同用户提交相同 bizIdentifier 会互相拒绝，且可被恶意抢占
        String idempotentKey = IDEMPOTENT_KEY_PREFIX + userId + ":" + request.getBizIdentifier();
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

        OrderTransactionListener.OrderCreateContext context = null;
        try {
            // 3. 获取 SKU 真实数据（不再使用 Mock 价格）
            List<Long> skuIds = request.getSkuItems().stream()
                    .map(OrderCreateRequest.SkuItem::getSkuId)
                    .collect(Collectors.toList());
            R<List<SkuInfoDTO>> skuResp = productFeignClient.batchGetSkuDetails(skuIds);
            if (!skuResp.isSuccess() || skuResp.getData() == null || skuResp.getData().isEmpty()) {
                throw new BizException(ResultCode.SERVICE_CALL_FAIL, "商品信息查询失败");
            }
            Map<Long, SkuInfoDTO> skuMap = skuResp.getData().stream()
                    .collect(Collectors.toMap(SkuInfoDTO::getId, s -> s));

            // 3.3 前置 SPU/SKU 状态校验（T-047：下单层双状态校验，防御纵深——cart 展示层已拦，下单层必须拦）
            for (OrderCreateRequest.SkuItem item : request.getSkuItems()) {
                SkuInfoDTO skuInfo = skuMap.get(item.getSkuId());
                if (skuInfo == null) {
                    throw new BizException(ResultCode.SKU_NOT_FOUND, "商品信息不存在: skuId=" + item.getSkuId());
                }
                // SPU 下架（status!=1）→ 拒绝下单（与 cart valid=false"商品已下架"语义一致）
                if (skuInfo.getSpuStatus() == null || skuInfo.getSpuStatus() != 1) {
                    throw new BizException(ResultCode.PRODUCT_OFF_SHELF,
                            "商品已下架: skuId=" + item.getSkuId());
                }
            }

            // 3.4 拒绝同 SKU 多行：库存预扣按 (orderId, skuId) 幂等，两行同 SKU（各 60、库存 100）都能过校验
            // 而预扣只按第一行扣 60 → 超卖；直接拒绝比静默合并更安全（要求调用方合并数量）
            java.util.Set<Long> seenSku = new java.util.HashSet<>();
            for (OrderCreateRequest.SkuItem item : request.getSkuItems()) {
                if (!seenSku.add(item.getSkuId())) {
                    throw new BizException(ResultCode.PARAM_INVALID, "同一 SKU 请合并为一行: skuId=" + item.getSkuId());
                }
            }

            // 3.5 前置库存校验（避免创建订单后预扣失败进DLQ）
            for (OrderCreateRequest.SkuItem item : request.getSkuItems()) {
                R<Map<String, Object>> stockResp = inventoryFeignClient.queryStock(item.getSkuId());
                if (!stockResp.isSuccess() || stockResp.getData() == null) {
                    throw new BizException(ResultCode.STOCK_NOT_ENOUGH, "库存查询失败");
                }
                Object availableObj = stockResp.getData().get("availableStock");
                int available = availableObj instanceof Integer ? (Integer) availableObj
                        : Integer.parseInt(availableObj.toString());
                if (available < item.getQuantity()) {
                    throw new BizException(ResultCode.STOCK_NOT_ENOUGH,
                            "库存不足: skuId=" + item.getSkuId() + ", need=" + item.getQuantity() + ", have=" + available);
                }
            }

            // 4. 计算金额（使用真实 SKU 价格 + 真实优惠券折扣）
            String orderNo = generateOrderNo(userId);
            BigDecimal totalAmount = calculateTotalAmount(request.getSkuItems(), skuMap);
            BigDecimal discountAmount = calculateCouponDiscount(userId, request, totalAmount);
            BigDecimal payAmount = totalAmount.subtract(discountAmount);
            if (payAmount.compareTo(BigDecimal.ZERO) < 0) {
                payAmount = BigDecimal.ZERO;
            }

            // 4. 发送事务消息（半消息 → 本地事务 → Commit/Rollback）
            //    本地事务在 OrderTransactionListener.executeLocalTransaction() 中执行

            // 先构建 payload（本地消息表和 MQ 消息体共用同一份 JSON）
            String payload = buildTransactionPayload(orderNo, userId, request);

            // 3.6 获取真实收货地址（替代 Mock 硬编码）：通过 addressId 查询 user 服务
            String addressSnapshot = resolveAddressSnapshot(userId, request.getAddressId());

            context =
                    OrderTransactionListener.OrderCreateContext.builder()
                            .userId(userId)
                            .request(request)
                            .orderNo(orderNo)
                            .totalAmount(totalAmount)
                            .discountAmount(discountAmount)
                            .payAmount(payAmount)
                            .addressSnapshot(addressSnapshot)
                    .transactionPayload(payload)
                    .skuMap(skuMap)
                    .build();

            // 构建事务消息（注入 traceId 保证全链路追踪）
            org.springframework.messaging.Message<String> msg = MqTraceHelper.wrapWithTraceId(
                    MessageBuilder.withPayload(payload)
                            .setHeader("orderNo", orderNo)
                            .setHeader("userId", userId.toString())
                            .setHeader(org.apache.rocketmq.spring.support.RocketMQHeaders.KEYS, orderNo)
                            .build());

            // 发送事务消息（半消息）
            // RocketMQ 收到半消息后回调 OrderTransactionListener.executeLocalTransaction()
            SendResult sendResult;
            try {
                sendResult = rocketMQTemplate.sendMessageInTransaction(
                        ORDER_TRANSACTION_TOPIC, msg, context);
            } catch (Exception e) {
                stringRedisTemplate.delete(idempotentKey);
                log.error("[订单] 事务消息发送失败: topic={}, producerGroup={}, orderNo={}, userId={}, exType={}, exMsg={}",
                        ORDER_TRANSACTION_TOPIC, "order-producer-group", orderNo, userId,
                        e.getClass().getName(), e.getMessage(), e);
                throw new BizException(ResultCode.INTERNAL_ERROR, "下单失败: send message Exception");
            }

            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                // 半消息发送状态异常（SLAVE_NOT_AVAILABLE / FLUSH_*_TIMEOUT 等）时 broker 仍可能已存储并回调本地事务，
                // 必须先看本地事务是否已提交（orderId 非空）：
                // 已提交 → 保留幂等键按成功返回（否则"订单已创建但接口报失败 → 用户重试同 bizIdentifier 生成重复订单"）；
                // 未提交 → 真回滚，删幂等键并报错。
                if (context.getOrderId() == null) {
                    stringRedisTemplate.delete(idempotentKey);
                    log.error("[订单] 事务消息发送异常且本地事务未提交: orderNo={}, userId={}, sendStatus={}",
                            orderNo, userId, sendResult.getSendStatus());
                    throw new BizException(ResultCode.INTERNAL_ERROR, "下单失败: 消息发送异常");
                }
                log.warn("[订单] 事务消息发送状态异常但本地事务已提交, 按成功处理(由回查/本地消息兜底): orderNo={}, sendStatus={}",
                        orderNo, sendResult.getSendStatus());
            }

            // 5. 本地事务已在 Listener 中执行完毕，获取 orderId
            if (context.getOrderId() == null) {
                // 本地事务执行失败（Listener 返回了 ROLLBACK）
                stringRedisTemplate.delete(idempotentKey);
                throw new BizException(ResultCode.INTERNAL_ERROR, "下单失败: 本地事务回滚");
            }

            // 5.2 标记本地消息已由事务消息投递，避免 LocalMessageRetryJob 30s 后再补发一次（双投）。
            //     事务消息 COMMIT 后 broker 会投递并重试至成功；本地消息表仅作回查与异常兜底。
            try {
                localMessageMapper.markSuccessByTransactionId(orderNo, userId);
            } catch (Exception e) {
                log.warn("[订单] 标记本地消息已投递失败(Job 可能重复补发，靠消费者幂等兜底): orderNo={}", orderNo, e);
            }

            // 5.5 核销优惠券（P0-A：下单创建订单时占用券，防止同一张券被复用导致资损）
            //     核销在订单提交后同步执行并绑定 orderId；coupon 侧 markUsed 为乐观锁(WHERE status=0)，
            //     天然幂等 + 归属校验。核销失败则取消订单，保证"打了折扣的订单必有已核销的券"。
            if (request.getCouponId() != null) {
                java.util.Map<String, Object> useReq = new java.util.HashMap<>();
                useReq.put("userCouponId", request.getCouponId());
                useReq.put("orderId", context.getOrderId());
                useReq.put("orderAmount", totalAmount);
                try {
                    R<BigDecimal> useResp = couponFeignClient.useCoupon(userId, useReq);
                    if (useResp == null || !useResp.isSuccess()) {
                        log.error("[订单] 优惠券核销失败，终结订单: orderId={}, couponId={}, resp={}",
                                context.getOrderId(), request.getCouponId(), useResp);
                        terminateOrderQuietly(context.getOrderId(), userId);
                        stringRedisTemplate.delete(idempotentKey);
                        throw new BizException(ResultCode.COUPON_NOT_AVAILABLE,
                                "优惠券核销失败，订单已取消，请重新下单");
                    }
                    // N-7 一致性观测：useCoupon 返回的实际折扣若与下单时 getCouponDiscount 计算结果不一致，
                    // 说明两次调用间模板被变更；记录告警供人工核对（订单金额以 getCouponDiscount 为权威）。
                    if (useResp.getData() != null
                            && discountAmount != null
                            && useResp.getData().compareTo(discountAmount) != 0) {
                        log.warn("[订单] 用券实际折扣与下单折扣不一致(模板可能已变更): orderId={}, couponId={}, 下单折扣={}, 核销折扣={}",
                                context.getOrderId(), request.getCouponId(), discountAmount, useResp.getData());
                    }
                } catch (BizException be) {
                    if (ResultCode.COUPON_NOT_AVAILABLE.getCode() == be.getCode()) {
                        // 已在上面抛出，直接透传
                        throw be;
                    }
                    // 其他业务异常：终结订单保持一致（宽容调用，失败由超时关单兜底）
                    log.error("[订单] 优惠券核销业务异常，终结订单: orderId={}", context.getOrderId(), be);
                    terminateOrderQuietly(context.getOrderId(), userId);
                    stringRedisTemplate.delete(idempotentKey);
                    throw new BizException(ResultCode.COUPON_NOT_AVAILABLE,
                            "优惠券核销失败，订单已取消，请重新下单");
                }
            }

            // 6. 发送延时消息（30 分钟后超时关单）—— 失败不影响下单
            sendCloseDelayMessage(context.getOrderId(), orderNo, userId);

            // 7.（已移除）订单创建事件由创建本地事务内核统一写入（OrderTransactionService，与订单插入同事务）；
            //    原步骤 7 的"补记"因重复事件恒被幂等跳过（属死行为 + 事件流乱序理论风险），收尾 Review 移除。

            // 8. 记录订单快照 —— 失败不影响下单
            takeSnapshot(context.getOrderId(), userId, "CREATED");

            // 9. 写入订单号映射表（解决非分片键查询路由问题；同步执行，失败依赖 RepairJob 补录）
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
            // 业务异常：仅事务未提交时释放幂等键允许重试（context 可能为 null——前置校验在事务消息之前）
            if (context == null || context.getOrderId() == null) {
                stringRedisTemplate.delete(idempotentKey);
            }
            businessMetrics.recordOrderCreated("fail");
            throw e;
        } catch (Exception e) {
            // 非业务异常：仅事务未提交时释放幂等键，已提交则保留(防重复下单)
            if (context == null || context.getOrderId() == null) {
                stringRedisTemplate.delete(idempotentKey);
            }
            businessMetrics.recordOrderCreated("fail");
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
    /**
     * 解析真实收货地址快照（替代 Mock 硬编码）。
     * 通过 addressId 调用 user 服务获取，序列化为 {name, phone, address} JSON 快照。
     */
    private String resolveAddressSnapshot(Long userId, Long addressId) {
        if (addressId == null) {
            log.warn("[订单] 下单未提供 addressId, userId={}, 地址快照为空", userId);
            return "{\"name\":\"\",\"phone\":\"\",\"address\":\"\"}";
        }
        try {
            R<UserAddressDTO> resp = userFeignClient.getAddress(userId, addressId);
            if (resp == null || !resp.isSuccess() || resp.getData() == null) {
                // 收货地址是下单硬需求：addressId 必填（@NotNull），
                // 获取失败/地址不存在必须拒绝下单——否则产生无地址订单（无法收货）。
                log.warn("[订单] 获取收货地址失败, userId={}, addressId={}, msg={}", userId, addressId,
                        resp != null ? resp.getMessage() : "null");
                throw new BizException(ResultCode.ADDRESS_NOT_FOUND, "收货地址不存在，请重新选择");
            }
            UserAddressDTO a = resp.getData();
            String full = nvl(a.getProvince()) + nvl(a.getCity()) + nvl(a.getDistrict()) + nvl(a.getDetailAddress());
            return "{\"name\":\"" + esc(a.getReceiverName()) + "\",\"phone\":\"" + esc(a.getReceiverPhone())
                    + "\",\"address\":\"" + esc(full) + "\"}";
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            log.warn("[订单] 获取收货地址异常, userId={}, addressId={}", userId, addressId, e);
            throw new BizException(ResultCode.INTERNAL_ERROR, "获取收货地址失败，请稍后重试");
        }
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildTransactionPayload(String orderNo, Long userId, OrderCreateRequest request) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "orderNo", orderNo,
                    "userId", userId,
                    "skuItems", request.getSkuItems(),
                    "couponId", request.getCouponId() != null ? request.getCouponId() : 0L,
                    // C-006：订单来源（CART 才触发购物车清车；缺省 DIRECT——Map.of 不容 null）
                    "source", request.getSource() != null && !request.getSource().isBlank()
                            ? request.getSource() : "DIRECT",
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

        // 拆单（ITER-001）：子单一次批量查询 + 行按 subOrderId 分组（无 N+1）
        List<SubOrder> subs = subOrderMapper.selectByOrderId(orderId, userId);
        Map<Long, List<OrderItem>> itemsBySub = items.stream()
                .filter(i -> i.getSubOrderId() != null)
                .collect(Collectors.groupingBy(OrderItem::getSubOrderId));

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
                .items(items.stream().map(this::toItemVO).collect(Collectors.toList()))
                .subOrders(subs.stream()
                        .map(sub -> toSubOrderVO(sub, itemsBySub.getOrDefault(sub.getId(), List.of())))
                        .collect(Collectors.toList()))
                .build();
    }

    /**
     * 查询用户订单列表
     */
    public List<OrderVO> getUserOrders(Long userId, Integer status) {
        return getUserOrders(userId, status, null);
    }

    /**
     * 查询用户订单列表（带条数上限，默认 100/最大 200：原实现无 LIMIT，大用户会全量返回）
     */
    public List<OrderVO> getUserOrders(Long userId, Integer status, Integer limit) {
        int size = (limit == null || limit <= 0) ? 100 : Math.min(limit, 200);
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreatedAt)
                .last("LIMIT " + size);
        if (status != null) {
            wrapper.eq(Order::getStatus, status);
        }

        List<Order> orders = orderMapper.selectList(wrapper);
        if (orders.isEmpty()) {
            return List.of();
        }
        // 拆单（ITER-001）：子单批量查询（user_id 固定 + order_id IN → 单分片一次查询，无 N+1）
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        List<SubOrder> allSubs = subOrderMapper.selectList(new LambdaQueryWrapper<SubOrder>()
                .eq(SubOrder::getUserId, userId)
                .in(SubOrder::getOrderId, orderIds));
        Map<Long, List<SubOrder>> subsByOrder = allSubs.stream()
                .collect(Collectors.groupingBy(SubOrder::getOrderId));
        return orders.stream().map(order -> {
            OrderVO vo = buildOrderVO(order);
            vo.setSubOrders(subsByOrder.getOrDefault(order.getId(), List.of()).stream()
                    .map(sub -> toSubOrderVO(sub, List.of()))
                    .collect(Collectors.toList()));
            return vo;
        }).collect(Collectors.toList());
    }

    // ==================== 取消订单 ====================

    /**
     * 取消订单（只能取消待付款的订单）
     * <p>
     * 04B：统一走 terminateOrder（事务消息 + 本地事务内核）；库存释放/退券/通知
     * 由 ORDER_TERMINATION_TOPIC 事件驱动（各域订阅），不再在本方法内同步 Feign 编排。
     * 竞态语义（并发支付/关单先赢）由 terminateOrder 内部回读收敛（已终结→幂等成功；已支付→状态不允许）。
     * </p>
     */
    public void cancelOrder(Long userId, Long orderId) {
        OrderTerminationService.Outcome outcome =
                terminateOrder(orderId, userId, TerminationReason.USER_CANCELLED, "APP");
        switch (outcome) {
            case NOT_FOUND -> throw new BizException(ResultCode.ORDER_NOT_FOUND);
            case INVALID_STATUS -> throw new BizException(ResultCode.ORDER_STATUS_ERROR, "只能取消待付款的订单");
            case TERMINATED, ALREADY_TERMINAL -> log.info("[订单] 取消成功(幂等): userId={}, orderId={}, outcome={}",
                    userId, orderId, outcome);
        }
    }

    /**
     * 订单终结唯一入口（04B 重构）
     * <p>
     * 四条终结路径（用户取消/超时关单/库存失败/券失败）统一收敛到本方法：
     * 以**事务消息**（独立生产者组/模板，见 OrderTerminationRocketMQConfig）承载——
     * 半消息 → OrderTerminationListener.executeLocalTransaction 执行本地事务
     * （状态 0→4 迁移 + 终结事件写本地消息表，同一 DB 事务）→ COMMIT 后事件对
     * inventory/coupon/notification 消费者可见；Broker 未收到终态由 checkLocalTransaction 回查收敛；
     * 本地消息表 + LocalMessageRetryJob 兜底补发（防"COMMIT 丢失/标记失败"）。
     * 各域联动（释放库存/退券/通知）由订阅方异步执行，不再在下单/取消链路内同步 Feign 编排。
     * </p>
     * <p>
     * 语义：
     * - 仅 status=0（待付款）可终结；status∈{4,5} 幂等返回 ALREADY_TERMINAL；
     * - 已付款/已发货/已完成返回 INVALID_STATUS（终结是"未支付订单"的语义，不替代退款/售后）；
     * - 本地事务回滚（并发竞态）→ 回读收敛：另一终结先赢 → ALREADY_TERMINAL；已支付 → INVALID_STATUS；
     *   仍未收敛 → BizException"状态已变化"；
     * - 消息发送失败（基础设施）→ IllegalStateException（可重试语义：库存失败收口消费者据此走 MQ 重试）；
     * - 消费者以 orderNo（消息 KEYS）为幂等键 + 各域内在幂等兜底。
     * </p>
     *
     * @return 终结结果（TERMINATED/ALREADY_TERMINAL/NOT_FOUND/INVALID_STATUS）
     */
    public OrderTerminationService.Outcome terminateOrder(Long orderId, Long userId,
                                                          TerminationReason reason, String source) {
        // 1. 预读（快速失败 + 构建事件载荷）：载荷必须在发半消息前定型——COMMIT 后可见的消息体
        //    与本地消息表 payload 必须同源（消费者与补发路径解析同一 schema）
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
        if (order == null) {
            return OrderTerminationService.Outcome.NOT_FOUND;
        }
        Integer status = order.getStatus();
        if (status != null && (status == 4 || status == 5)) {
            log.info("[订单] 终结幂等跳过(已是终态): orderId={}, status={}", orderId, status);
            return OrderTerminationService.Outcome.ALREADY_TERMINAL;
        }
        if (status == null || status != 0) {
            log.warn("[订单] 终结拒绝(状态不允许): orderId={}, status={}, reason={}", orderId, status, reason);
            return OrderTerminationService.Outcome.INVALID_STATUS;
        }

        String eventPayload = buildTerminationPayload(order, reason, source);
        OrderTerminationListener.OrderTerminationContext context =
                OrderTerminationListener.OrderTerminationContext.builder()
                        .orderId(orderId)
                        .userId(userId)
                        .orderNo(order.getOrderNo())
                        .reason(reason)
                        .source(source)
                        .eventPayload(eventPayload)
                        .build();

        // 2. 事务消息：半消息 → 监听器执行本地事务 → COMMIT/ROLLBACK（独立模板，与下单链路同构）
        TransactionSendResult sendResult = null;
        boolean sendException = false;
        try {
            sendResult = orderTerminationRocketMQTemplate.sendMessageInTransaction(
                    ORDER_TERMINATION_TOPIC,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(eventPayload)
                            .setHeader("orderNo", order.getOrderNo())
                            .setHeader("userId", userId.toString())
                            .setHeader(org.apache.rocketmq.spring.support.RocketMQHeaders.KEYS, order.getOrderNo())
                            .build()),
                    context);
        } catch (Exception e) {
            sendException = true;
            log.error("[订单] 终结事务消息发送异常: orderId={}, orderNo={}, producerGroup={}, exType={}, exMsg={}",
                    orderId, order.getOrderNo(), OrderTerminationRocketMQConfig.TERMINATION_PRODUCER_GROUP,
                    e.getClass().getName(), e.getMessage(), e);
        }

        // 3. 结果判定（对齐创建侧口径：发送状态异常但本地事务已提交 → 按成功处理，由回查/补发兜底）
        OrderTerminationService.Outcome outcome = context.getOutcome();
        if (outcome == null) {
            if (sendException || sendResult == null || sendResult.getSendStatus() != SendStatus.SEND_OK) {
                // 半消息未发出（本地事务未执行）→ 基础设施失败，可重试
                throw new IllegalStateException("订单终结失败: 事务消息发送异常");
            }
            // 本地事务回滚（并发竞态/DB 异常）：回读收敛语义
            Order latest = orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getUserId, userId)
                            .eq(Order::getId, orderId));
            Integer latestStatus = latest == null ? null : latest.getStatus();
            if (latestStatus != null && (latestStatus == 4 || latestStatus == 5)) {
                log.info("[订单] 终结回滚后回读: 已被并发终结, 按幂等成功处理: orderId={}", orderId);
                return OrderTerminationService.Outcome.ALREADY_TERMINAL;
            }
            if (latestStatus != null && latestStatus != 0) {
                log.info("[订单] 终结回滚后回读: 状态已流转: orderId={}, status={}", orderId, latestStatus);
                return OrderTerminationService.Outcome.INVALID_STATUS;
            }
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "订单状态已变化，请刷新后重试");
        }
        if (outcome != OrderTerminationService.Outcome.TERMINATED) {
            return outcome;
        }
        if (sendException || (sendResult != null && sendResult.getSendStatus() != SendStatus.SEND_OK)) {
            log.warn("[订单] 终结事务消息发送状态异常但本地事务已提交, 按成功处理(回查/本地消息兜底): orderId={}", orderId);
        }

        // 4. 提交后动作：快照（reason/source 归因）/ 缓存失效 / 本地消息标记（防补发 Job 双投）
        takeSnapshot(orderId, userId, reason.getSnapshotEvent(),
                Map.of("reason", reason.name(), "source", source));
        stringRedisTemplate.delete("myxhs:order:info:" + orderId);
        try {
            localMessageMapper.markSuccessByTransactionIdAndType(
                    order.getOrderNo(), userId, OrderTerminationService.OPERATION_TYPE_ORDER_TERMINATED);
        } catch (Exception e) {
            log.warn("[订单] 标记终结本地消息已投递失败(补发Job可能重复投递, 靠消费者幂等兜底): orderNo={}",
                    order.getOrderNo(), e);
        }

        log.info("[订单] 终结完成: orderId={}, orderNo={}, reason={}, source={}",
                orderId, order.getOrderNo(), reason.name(), source);
        return OrderTerminationService.Outcome.TERMINATED;
    }

    /** 构造终结事件载荷（与事务消息体同源；Long 经全局 JacksonConfig 序列化为字符串，消费者需兼容解析） */
    private String buildTerminationPayload(Order order, TerminationReason reason, String source) {
        // C-006：回补联动需要商品明细（购物车按 items 恢复）——终结时订单明细已定型（不可变），预读期查询
        java.util.List<OrderTerminationEvent.TerminationItem> items = orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>()
                                .eq(OrderItem::getOrderId, order.getId())
                                .eq(OrderItem::getUserId, order.getUserId()))
                .stream()
                .map(item -> OrderTerminationEvent.TerminationItem.builder()
                        .skuId(item.getSkuId())
                        .quantity(item.getQuantity())
                        .build())
                .collect(java.util.stream.Collectors.toList());
        OrderTerminationEvent event = OrderTerminationEvent.builder()
                .eventVersion(2) // C-006：+items（回补联动；现有订阅方按字段读，向后兼容）
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .couponId(order.getCouponId())
                .reason(reason.name())
                .source(source)
                .items(items)
                .occurredAt(LocalDateTime.now().toString())
                .traceId(com.myxhs.common.trace.TraceContextHolder.getTraceId())
                .build();
        try {
            return objectMapper.writeValueAsString(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 序列化失败属编码错误（DTO 字段均可序列化），直接失败，禁止投递残缺事件
            throw new IllegalStateException("订单终结事件序列化失败: orderId=" + order.getId(), e);
        }
    }

    /**
     * 券失败路径的终结调用（宽容版）：终结异常不阻断"下单失败"响应——
     * 订单保持待付款时由超时关单（30min）兜底终结，避免下单接口因终结链路异常抛非预期错误。
     */
    private void terminateOrderQuietly(Long orderId, Long userId) {
        try {
            terminateOrder(orderId, userId, TerminationReason.COUPON_FAILED, "SYSTEM");
        } catch (Exception e) {
            log.error("[订单] 券失败终结订单异常(超时关单兜底): orderId={}", orderId, e);
        }
    }

    /**
     * 释放库存的纯执行体：返回是否成功，**不发送补偿消息**。
     * <p>
     * 补偿自循环修复（2026-10-11）：补偿消费者/兜底重放路径原先复用带"失败即再发补偿消息"的
     * releaseInventory，导致失败被吞 + 新消息无延时 → 秒级自旋且兜底集合永不触发。
     * 补偿侧改用本方法：拿到真实成败，失败向上抛走 MQ 原生退避重试。
     * </p>
     */
    private boolean doReleaseInventory(Long orderId, String orderNo, Long userId) {
        try {
            // 库存预扣记录以 fold-hash(orderNo) 派生的 pseudoOrderId 为幂等键
            // （OrderTransactionConsumer 在半消息阶段只有 orderNo，订单未入库无法获得真实 orderId），
            // 释放必须用同一派生值，否则 prededuct 记录找不到 → 库存永久泄漏
            long pseudoOrderId = derivePseudoOrderId(orderNo);
            // C-008：items 契约——订单明细随请求携带（inventory 侧按 per-(sku,order) 记录释放）
            Map<String, Object> request = Map.of(
                    "orderId", pseudoOrderId,
                    "items", buildInventoryItems(orderId, userId));
            R<Void> result = inventoryFeignClient.releaseStock(request);
            if (result == null || !result.isSuccess()) {
                log.error("[订单] 释放库存失败，需补偿: orderId={}, result={}", orderId,
                        result == null ? "null" : result.getMessage());
                return false;
            }
            log.info("[订单] 释放库存成功: orderId={}", orderId);
            return true;
        } catch (Exception e) {
            log.error("[订单] 释放库存异常，需补偿: orderId={}", orderId, e);
            return false;
        }
    }

    /**
     * C-008：构造库存 items 载荷（confirm/release 的 SKU 事实源——订单明细不可变）。
     * inventory 侧预扣记录为 per-(sku,order) 单键，需 items 定位；Long 经全局 JacksonConfig 序列化为字符串。
     */
    private java.util.List<java.util.Map<String, Object>> buildInventoryItems(Long orderId, Long userId) {
        return orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>()
                                .eq(OrderItem::getOrderId, orderId)
                                .eq(OrderItem::getUserId, userId))
                .stream()
                .map(item -> {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("skuId", item.getSkuId());
                    m.put("quantity", item.getQuantity());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 从 orderNo 派生伪 orderId（与 inventory 服务 OrderTransactionConsumer 相同的 fold-hash 算法）
     * 用于释放库存时定位预扣记录
     */
    private static long derivePseudoOrderId(String orderNo) {
        // ⚠️ 与 inventory 侧 PseudoOrderIdUtil.derive 必须保持一致（预扣/释放/确认幂等键；改一侧必须同步另一侧）
        // P2-12: 原 fold-hash（*31+char）在 ~24 字符 orderNo 上必然 long 溢出环绕，跨单碰撞概率高
        //        （碰撞 → 两个订单共用 inventory:{skuId}:prededuct:{id} 预扣记录 → 库存扣减/释放错乱）
        // 改为 SHA-256 截取前 8 字节（64bit），分布均匀，生日碰撞概率 ~2^-32，可忽略。
        // 注意：算法变更只影响新订单；跨版本"预扣旧算法/确认新算法"的进行中订单需人工核对（30min 窗口内）。
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(orderNo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (digest[i] & 0xFF);
            }
            return result;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
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
        if (!doReturnCouponIfUsed(order)) {
            sendCompensationMessage("RETURN_COUPON", order.getId(), order.getUserId(), "退还优惠券失败，进入补偿重试");
        }
    }

    /**
     * 退还优惠券的纯执行体：返回是否成功（未用券视为成功），**不发送补偿消息**（同步循环修复同 doReleaseInventory）。
     */
    private boolean doReturnCouponIfUsed(Order order) {
        if (order.getCouponId() == null) {
            return true;
        }
        try {
            Map<String, Object> request = Map.of(
                    "userCouponId", order.getCouponId(),
                    "orderId", order.getId()
            );
            R<Void> result = couponFeignClient.returnCoupon(order.getUserId(), request);
            if (result == null || !result.isSuccess()) {
                log.error("[订单] 退还优惠券失败，需补偿: orderId={}, couponId={}, result={}",
                        order.getId(), order.getCouponId(), result == null ? "null" : result.getMessage());
                return false;
            }
            log.info("[订单] 退还优惠券成功: orderId={}, couponId={}", order.getId(), order.getCouponId());
            return true;
        } catch (Exception e) {
            log.error("[订单] 退还优惠券异常，需补偿: orderId={}, couponId={}", order.getId(), order.getCouponId(), e);
            return false;
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
            // MQ 也发送失败，写入本地兜底集合，供定时任务重放补偿消息
            log.error("[订单] 补偿消息发送失败，写入本地兜底集合: action={}, orderId={}", action, orderId, e);
            try {
                String member = action + ":" + orderId + ":" + userId + ":" + System.currentTimeMillis();
                stringRedisTemplate.opsForSet().add(ORDER_COMPENSATION_FALLBACK_KEY, member);
            } catch (Exception ex) {
                log.error("[订单] 本地补偿兜底集合写入失败: action={}, orderId={}", action, orderId, ex);
            }
        }
    }

    // ==================== 确认收货 ====================

    /**
     * 确认收货（子单维度：已发货 2 → 已完成 3；父单由聚合收敛）
     * <p>
     * 履约下沉（ITER-001）：确认作用在子单（CAS 2→3）；全部子单终态且成功 → 父单聚合为"已完成"。
     * 不发通知——动作发起人就是用户自己（与既有口径一致）。
     * </p>
     */
    public void confirmReceive(Long userId, String subOrderNo) {
        SubOrder sub = subOrderMapper.selectBySubOrderNo(subOrderNo, userId);
        if (sub == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND, "子订单不存在");
        }
        if (sub.getStatus() == null || sub.getStatus() != SubOrder.STATUS_SHIPPED) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "只能确认已发货的子订单");
        }
        int affected = subOrderMapper.markCompleted(sub.getId(), userId);
        if (affected == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "子订单状态已变化，请刷新后重试");
        }

        // 父单聚合（全部子单终态且成功 → 父单 →3；事件/快照随聚合流转）
        boolean parentMoved = subOrderService.recomputeParentStatus(userId, sub.getOrderId());
        if (parentMoved) {
            orderMapper.setCompletedAt(sub.getOrderId(), userId); // 条件更新（WHERE status=3），幂等
            takeSnapshot(sub.getOrderId(), userId, "COMPLETED", Map.of("subOrderNo", subOrderNo));
        }
        stringRedisTemplate.delete("myxhs:order:info:" + sub.getOrderId());
        log.info("[订单] 子单确认收货: userId={}, subOrderNo={}, orderId={}, parentMoved={}",
                userId, subOrderNo, sub.getOrderId(), parentMoved);
    }

    // ==================== 发货 ====================

    /**
     * 发货（子单维度：待发货 1 → 已发货 2；父单由聚合收敛）
     * <p>
     * ITER-001 拆单：
     * - 履约下沉：发货作用在子单（CAS 1→2），全部子单发货后父单聚合为"已发货"；
     * - 发货拦截（决策 2）：子单商品存在进行中的"仅退款"售后 → 拒绝发货（防钱货两空）；
     * - 通知/缓存：按子单通知（附物流信息）；父单快照仅在聚合流转时记录。
     * </p>
     */
    public void deliverOrder(Long userId, String subOrderNo, String logisticsCompany, String trackingNo) {
        SubOrder sub = subOrderMapper.selectBySubOrderNo(subOrderNo, userId);
        if (sub == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND, "子订单不存在");
        }
        if (sub.getStatus() == null || sub.getStatus() != SubOrder.STATUS_TO_SHIP) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "只能对已付款（待发货）的子订单执行发货");
        }
        // 决策 2：进行中的仅退款售后 → 拒绝发货（商家先处理售后，防钱货两空）
        if (hasActiveRefundOnlyAftersale(userId, sub)) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "该子订单商品存在进行中的退款申请，请先处理售后");
        }
        int affected = subOrderMapper.markDelivered(sub.getId(), userId);
        if (affected == 0) {
            throw new BizException(ResultCode.ORDER_STATUS_ERROR, "子订单状态已变化，请刷新后重试");
        }

        // 父单聚合（全部子单发货 → 父单 1→2；事件/快照随聚合流转）
        boolean parentMoved = subOrderService.recomputeParentStatus(userId, sub.getOrderId());
        if (parentMoved) {
            orderMapper.setDeliveredAt(sub.getOrderId(), userId); // 条件更新（WHERE status=2），幂等
            takeSnapshot(sub.getOrderId(), userId, "DELIVERED", Map.of("subOrderNo", subOrderNo));
        }

        stringRedisTemplate.delete("myxhs:order:info:" + sub.getOrderId());
        // 子单发货通知（type=5；附物流信息）
        String shipInfo = ((logisticsCompany == null || logisticsCompany.isEmpty()) ? "" : logisticsCompany + " ")
                + (trackingNo == null || trackingNo.isEmpty() ? "" : trackingNo);
        orderNotificationPublisher.publishStatusChanged(userId, sub.getOrderId(), sub.getOrderNo(),
                "子订单已发货，请注意查收", shipInfo.trim());
        log.info("[订单] 子单发货成功: userId={}, subOrderNo={}, orderId={}, parentMoved={}",
                userId, subOrderNo, sub.getOrderId(), parentMoved);
    }

    /**
     * 发货拦截校验（决策 2）：子单商品存在**进行中的仅退款售后**（待审核/已同意/退款中）
     * <p>
     * 语义：退货退款（type=2）在未发货时不可申请（申请资格已约束），因此发货时只需拦"仅退款"。
     * 竞态：售后审核与发货并发时谁先赢谁生效，商家审核是最终决策点。
     * </p>
     */
    private boolean hasActiveRefundOnlyAftersale(Long userId, SubOrder sub) {
        List<OrderItem> items = orderItemMapper.selectBySubOrderId(sub.getId(), userId);
        if (items.isEmpty()) {
            return false;
        }
        Set<Long> itemIds = items.stream().map(OrderItem::getId).collect(Collectors.toSet());
        return aftersaleRepository.listByOrder(sub.getOrderId()).stream()
                .anyMatch(a -> a.getItemId() != null && itemIds.contains(a.getItemId())
                        && a.getType() != null && a.getType() == Aftersale.TYPE_REFUND_ONLY
                        && a.getStatus() != null
                        && (a.getStatus() == Aftersale.STATUS_APPLIED
                            || a.getStatus() == Aftersale.STATUS_APPROVED
                            || a.getStatus() == Aftersale.STATUS_REFUNDING));
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
        // 分库分表后状态更新需要 userId 作为分片键路由
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
            businessMetrics.recordOrderPayResult("not_found");
            return false;
        }
        if (order.getStatus() != 0) {
            log.warn("[订单] 支付回调但订单状态不是待付款: orderId={}", orderId);
            businessMetrics.recordOrderPayResult("race"); // 已支付/已取消/已关闭：正常竞态终态
            // 拆单（ITER-001）：机会式自愈——父单已支付但子单可能未推进（进程崩溃窗口），
            // 借重复通知（双通道/渠道重试天然存在）重放推进（幂等；子单已 1 时 0 行）
            if (order.getStatus() == 1) {
                subOrderService.advanceAllToPaid(userId, orderId);
            }
            return false;
        }

        // Event Sourcing: 追加支付事件并更新状态
        boolean statusUpdated = false;
        try {
            orderEventService.appendEvent(order, OrderEventService.EVENT_PAID,
                    Map.of("paidTime", LocalDateTime.now().toString()));
            statusUpdated = true;
            orderMapper.setPaidAt(orderId, userId);
            // 拆单（ITER-001）：全子单 0→1（批量推进；与父单支付事件同流程）
            subOrderService.advanceAllToPaid(userId, orderId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // 并发: 关单/取消先赢→支付已成功(钱已扣)但订单状态已变
            // 不抛异常: 抛异常→payment侧误判通知失败→P0-6无限重试
            log.error("[订单] 支付回调竞态(订单状态已变): orderId={}, status={}", orderId, order.getStatus(), e);
            businessMetrics.recordOrderPayResult("race");
            return false;
        } catch (org.springframework.dao.DataAccessException e) {
            // SQL 异常：可能 setPaidAt/事件落库瞬时失败，但状态可能已改为已支付。
            // 不向上抛 500（否则 payment 侧误判失败并自动退款已支付订单）。
            // 若状态已更新则返回 true 并记录，paid_at/事件由对账兜底；若状态未更新则返回 false。
            if (statusUpdated) {
                log.error("[订单] 支付已成功但 paid_at/事件 SQL 失败(对账兜底): orderId={}", orderId, e);
                businessMetrics.recordOrderPayResult("transient_event_failed");
                subOrderService.advanceAllToPaid(userId, orderId); // 拆单：全子单 0→1（尽力推进，幂等）
                confirmInventoryDeduct(orderId, order.getOrderNo(), userId);
                takeSnapshot(orderId, userId, "PAID");
                stringRedisTemplate.delete("myxhs:order:info:" + orderId);
                orderNotificationPublisher.publishStatusChanged(userId, orderId, order.getOrderNo(),
                        "支付成功，我们将尽快为你发货", null);
                return true;
            }
            log.error("[订单] 支付事件 SQL 失败且状态未更新: orderId={}", orderId, e);
            businessMetrics.recordOrderPayResult("transient_status_lost");
            return false;
        }

        // 确认库存扣减（预扣 → 正式扣减，异步不阻塞主链路，失败由补偿+对账兜底）
        confirmInventoryDeduct(orderId, order.getOrderNo(), userId);

        takeSnapshot(orderId, userId, "PAID");
        stringRedisTemplate.delete("myxhs:order:info:" + orderId);
        // 订单通知（type=5）：支付成功
        orderNotificationPublisher.publishStatusChanged(userId, orderId, order.getOrderNo(),
                "支付成功，我们将尽快为你发货", null);
        log.info("[订单] 支付成功: orderId={}", orderId);
        return true;
    }

    /**
     * 确认库存扣减（支付成功后调用）
     * <p>
     * 将预扣的 locked_stock 正式扣除。使用 fold-hash(orderNo) 派生的 pseudoOrderId 定位预扣记录。
     * 失败时记录日志，由库存对账任务兜底（Redis 预扣记录 30min 过期 + MySQL locked 对账修复）。
     * </p>
     */
    private void confirmInventoryDeduct(Long orderId, String orderNo, Long userId) {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            confirmInventoryDeductSync(orderId, orderNo, userId);
        }, ORDER_ASYNC_EXECUTOR);
    }

    /**
     * 同步确认库存扣减（支付成功路径 / 退款前调用）。
     * <p>
     * 确保预扣记录被清理：confirm 成功后 Redis 删除预扣 Hash、MySQL 减 locked；
     * 若预扣记录已清理则 no-op（幂等）。
     * 失败进入补偿重试（2026-10-11，问题 4 修复）：原实现仅日志"依赖对账兜底"——
     * 预扣记录未清理时会被 PreDeductTimeoutJob 到期释放（已付款订单的货被放回）；
     * 现在失败发送 CONFIRM_STOCK 补偿消息，在超时窗口前把记录清掉，从源头掐掉该缺口。
     * </p>
     */
    private boolean confirmInventoryDeductSync(Long orderId, String orderNo, Long userId) {
        if (!doConfirmInventoryDeduct(orderId, orderNo, userId)) {
            // 业务链路失败：发补偿消息由消费者重试（补偿侧用纯执行体，防消息放大——2026-10-14 收尾修复）
            sendCompensationMessage("CONFIRM_STOCK", orderId, userId, "确认库存扣减失败，进入补偿重试");
            return false;
        }
        return true;
    }

    /**
     * 确认扣减纯执行体：返回真实成败，**不发送补偿消息**（补偿消费者/兜底重放专用）。
     * <p>
     * 修复动机（与 2026-10-11 RELEASE_STOCK 同类问题）：补偿消费者执行失败会抛异常走 MQ 退避重试，
     * 若执行体再发一条补偿消息 → 每次重试都放大一条新消息（消息放大/准自循环）。
     * </p>
     */
    private boolean doConfirmInventoryDeduct(Long orderId, String orderNo, Long userId) {
        try {
            long pseudoOrderId = derivePseudoOrderId(orderNo);
            // C-008：items 契约——订单明细（不可变）随请求携带（inventory 侧按 per-(sku,order) 记录确认）
            Map<String, Object> request = Map.of(
                    "orderId", pseudoOrderId,
                    "items", buildInventoryItems(orderId, userId));
            R<Void> result = inventoryFeignClient.confirmDeduct(request);
            if (result == null || !result.isSuccess()) {
                log.error("[订单] 确认库存扣减失败: orderId={}, result={}", orderId,
                        result == null ? "null" : result.getMessage());
                return false;
            }
            log.info("[订单] 确认库存扣减成功: orderId={}", orderId);
            return true;
        } catch (Exception e) {
            log.error("[订单] 确认库存扣减异常: orderId={}", orderId, e);
            return false;
        }
    }

    /**
     * 补偿：确认库存扣减（CONFIRM_STOCK，2026-10-11 问题 4 修复）
     * <p>
     * 供 OrderCompensationConsumer / 兜底重放使用；返回真实成败，失败由调用方抛出走 MQ 保序重试。
     * </p>
     */
    public boolean compensateConfirmStock(Long orderId, Long userId) {
        Order order = loadOrderEntity(orderId, userId);
        if (order == null) {
            log.warn("[补偿] 确认库存-订单不存在: orderId={}", orderId);
            return true; // 无法定位订单，按终态处理，避免无限重试
        }
        return doConfirmInventoryDeduct(orderId, order.getOrderNo(), userId);
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
        if (!doCloseTimeoutOrder(orderId, userId)) {
            log.error("[订单] 超时关单失败, 发送补偿消息: orderId={}, userId={}", orderId, userId);
            sendCompensationMessage("CLOSE_ORDER", orderId, userId, "超时关单失败，进入补偿重试");
        }
    }

    /**
     * 超时关单的纯执行体：返回是否成功（含幂等跳过），**不发送补偿消息**（补偿自循环修复 2026-10-11）。
     */
    private boolean doCloseTimeoutOrder(Long orderId, Long userId) {
        try {
            // 04B：统一终结入口（TIMEOUT）；释放/退券/通知由终结事件驱动
            OrderTerminationService.Outcome outcome =
                    terminateOrder(orderId, userId, TerminationReason.TIMEOUT, "TIMEOUT_JOB");
            if (outcome == OrderTerminationService.Outcome.TERMINATED) {
                log.info("[订单] 超时关单: orderId={}", orderId);
            } else {
                // NOT_FOUND / ALREADY_TERMINAL / INVALID_STATUS（已付款等）：幂等跳过
                log.info("[订单] 超时关单幂等跳过: orderId={}, outcome={}", orderId, outcome);
            }
            return true;
        } catch (Exception e) {
            log.error("[订单] 超时关单执行失败: orderId={}, userId={}", orderId, userId, e);
            return false;
        }
    }

    /** 补偿：超时关单（补偿消费者/兜底重放使用，返回真实成败） */
    public boolean compensateCloseOrder(Long orderId, Long userId) {
        return doCloseTimeoutOrder(orderId, userId);
    }

    /**
     * 库存失败事件收口（inventory → order，INVENTORY_FAILED_TOPIC）
     * <p>
     * 触发场景：预扣/确认在 MySQL 侧重试耗尽（Redis 侧未锁定成功）。
     * 收口策略：
     * - 待付款(0)：直接取消（原因=库存预扣失败），不等 30 分钟超时；释放已成功的其他 SKU、退券、通知用户；
     * - 已付款(1)：记 ERROR + 指标（人工/对账介入；发货守卫为最后一道闸）；
     * - 其他状态：幂等跳过。
     * </p>
     */
    public void handleInventoryFailure(Long orderId, Long userId, String reason, Long skuId) {
        try {
            Order order = orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getUserId, userId)
                            .eq(Order::getId, orderId));
            if (order == null) {
                log.warn("[库存失败收口] 订单不存在: orderId={}", orderId);
                businessMetrics.recordOrderInventoryFailure("order_missing");
                return;
            }
            int status = order.getStatus();
            if (status == 0) {
                // 04B：统一终结入口——多 SKU 释放/退券/通知由终结事件驱动（不再同步 Feign 编排）
                OrderTerminationService.Outcome outcome =
                        terminateOrder(orderId, userId, TerminationReason.INVENTORY_FAILED, "SYSTEM");
                if (outcome == OrderTerminationService.Outcome.TERMINATED) {
                    businessMetrics.recordOrderInventoryFailure("auto_cancelled");
                    log.warn("[库存失败收口] 待付款订单已自动取消: orderId={}, reason={}, skuId={}",
                            orderId, reason, skuId);
                } else {
                    // ALREADY_TERMINAL / INVALID_STATUS：并发支付/取消先赢 → 已收敛
                    businessMetrics.recordOrderInventoryFailure("state_conflict");
                    log.info("[库存失败收口] 订单状态已收敛, 跳过: orderId={}, outcome={}", orderId, outcome);
                }
            } else if (status == 1) {
                businessMetrics.recordOrderInventoryFailure("manual_required_paid");
                log.error("[库存失败收口] 已付款订单库存失败，需人工/对账介入: orderId={}, orderNo={}, reason={}, skuId={}",
                        orderId, order.getOrderNo(), reason, skuId);
            } else {
                businessMetrics.recordOrderInventoryFailure("skipped_status_" + status);
                log.info("[库存失败收口] 订单当前状态无需处理: orderId={}, status={}", orderId, status);
            }
        } catch (com.myxhs.common.exception.BizException e) {
            // 状态机拒绝（并发取消/支付已流转）→ 视为已收敛，不重试
            businessMetrics.recordOrderInventoryFailure("state_conflict");
            log.warn("[库存失败收口] 状态流转冲突(并发取消/支付): orderId={}, msg={}", orderId, e.getMessage());
        } catch (Exception e) {
            log.error("[库存失败收口] 处理异常: orderId={}", orderId, e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    /**
     * T-071（2026-08-14）：退款回补库存
     * <p>
     * 全额退款（订单置 5）后，将订单明细数量回补库存（Redis total/桶 + MySQL available）。
     * 幂等：order 状态机（status==1 才处理一次）+ inventory 侧 SETNX(orderId)。
     * </p>
     */
    private void restoreStockOnRefund(Long orderId, Long userId) {
        try {
            List<OrderItem> items = orderItemMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrderItem>()
                            .eq(OrderItem::getUserId, userId)
                            .eq(OrderItem::getOrderId, orderId));
            for (OrderItem item : items) {
                Map<String, Object> req = Map.of(
                        "orderId", orderId, "skuId", item.getSkuId(),
                        "quantity", item.getQuantity(), "userId", userId);
                R<Void> resp = inventoryFeignClient.refundRestore(req);
                if (resp == null || !resp.isSuccess()) {
                    log.error("[订单] 退款回补库存失败(依赖对账): orderId={}, skuId={}, resp={}",
                            orderId, item.getSkuId(), resp);
                } else {
                    log.info("[订单] 退款回补库存成功: orderId={}, skuId={}, qty={}",
                            orderId, item.getSkuId(), item.getQuantity());
                }
            }
        } catch (Exception e) {
            log.error("[订单] 退款回补库存异常(依赖对账): orderId={}", orderId, e);
        }
    }

    /**
     * 补偿：释放库存（P1-2 修复）
     * <p>
     * 供 OrderCompensationConsumer 对 RELEASE_STOCK 补偿使用。加载订单取 orderNo 后释放，
     * <b>不依赖订单状态</b>（已付款/已取消订单都需释放），避免 closeTimeoutOrder 只处理
     * status==0 导致已支付订单库存永久泄漏。
     * </p>
     */
    public boolean compensateReleaseStock(Long orderId, Long userId) {
        Order order = loadOrderEntity(orderId, userId);
        if (order == null) {
            log.warn("[补偿] 释放库存-订单不存在: orderId={}", orderId);
            return true; // 无法定位订单（也就无从释放），按终态处理，避免无限重试
        }
        return doReleaseInventory(orderId, order.getOrderNo(), userId);
    }

    /**
     * 补偿：退还优惠券（P1-2 修复）
     * <p>
     * 供 OrderCompensationConsumer 对 RETURN_COUPON 补偿使用。
     * </p>
     */
    public boolean compensateReturnCoupon(Long orderId, Long userId) {
        Order order = loadOrderEntity(orderId, userId);
        if (order == null) {
            log.warn("[补偿] 退券-订单不存在: orderId={}", orderId);
            return true; // 无法定位订单，按终态处理，避免无限重试
        }
        return doReturnCouponIfUsed(order);
    }

    /** 按 userId(orderId) 路由加载订单实体（分库分表需带分片键） */
    private Order loadOrderEntity(Long orderId, Long userId) {
        return orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
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
                    3000, orderCloseDelayLevel); // 默认16=30min, 测试环境可设5=1min
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
        takeSnapshot(orderId, userId, event, Map.of());
    }

    /**
     * 快照记录（04B：支持附加归因字段，如终结的 reason/source）
     */
    private void takeSnapshot(Long orderId, Long userId, String event, Map<String, Object> extra) {
        try {
            Order order = orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getUserId, userId)
                            .eq(Order::getId, orderId));
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>()
                            .eq(OrderItem::getUserId, userId)
                            .eq(OrderItem::getOrderId, orderId));

            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("order", order);
            data.put("items", items);
            data.put("timestamp", LocalDateTime.now().toString());
            if (extra != null && !extra.isEmpty()) {
                data.putAll(extra);
            }

            OrderSnapshot snapshot = new OrderSnapshot();
            snapshot.setOrderId(orderId);
            snapshot.setUserId(userId); // 分片键
            snapshot.setEvent(event);
            snapshot.setSnapshotData(objectMapper.writeValueAsString(data));
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
     * 计算订单总金额（使用真实 SKU 价格）
     */
    private BigDecimal calculateTotalAmount(List<OrderCreateRequest.SkuItem> skuItems,
                                            Map<Long, SkuInfoDTO> skuMap) {
        BigDecimal total = BigDecimal.ZERO;
        for (OrderCreateRequest.SkuItem item : skuItems) {
            SkuInfoDTO sku = skuMap.get(item.getSkuId());
            BigDecimal price = sku != null ? sku.getPrice() : BigDecimal.ZERO;
            total = total.add(price.multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return total;
    }

    /**
     * 计算优惠券折扣金额（调用 coupon 服务实时计算，替代 Mock）
     */
    private BigDecimal calculateCouponDiscount(Long userId, OrderCreateRequest request,
                                                BigDecimal totalAmount) {
        if (request.getCouponId() == null) {
            return BigDecimal.ZERO;
        }
        R<BigDecimal> result;
        try {
            result = couponFeignClient.getCouponDiscount(
                    userId, request.getCouponId(), totalAmount);
        } catch (Exception e) {
            log.error("[订单] 查询券折扣异常，拒绝下单(避免券被核销但按全价付款): couponId={}",
                    request.getCouponId(), e);
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券折扣查询失败，请稍后重试");
        }
        if (result == null || !result.isSuccess()) {
            log.error("[订单] 查询券折扣失败，拒绝下单(避免券被核销但按全价付款): couponId={}, resp={}",
                    request.getCouponId(), result);
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券折扣查询失败，请稍后重试");
        }
        return result.getData() != null ? result.getData() : BigDecimal.ZERO;
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

    /** 行 → VO（ITER-001：含分摊/行实付/子单归属） */
    // ==================== 商家侧查询（C-004 P1） ====================

    /**
     * 商家维度子单详情（服务端路由解析）：subOrderNo → orderNo（去 "-N" 后缀）→ 映射表 → userId
     * → 路由读（不依赖 ES）。商家不持有买家 userId；映射缺失明确报错（不静默、不 scatter）。
     */
    public OrderVO.SubOrderVO getSellerSubOrderDetail(String subOrderNo) {
        if (subOrderNo == null || subOrderNo.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "子单号必填");
        }
        int idx = subOrderNo.lastIndexOf('-');
        if (idx <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, "子单号格式非法");
        }
        String orderNo = subOrderNo.substring(0, idx);
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderNo(orderNo);
        if (mapping == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND, "订单路由信息缺失");
        }
        SubOrder sub = subOrderMapper.selectBySubOrderNo(subOrderNo, mapping.getUserId());
        if (sub == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND, "子单不存在");
        }
        List<OrderItem> items = orderItemMapper.selectBySubOrderId(sub.getId(), mapping.getUserId());
        return toSubOrderVO(sub, items);
    }

    /**
     * 子单全量扫描（C-004 P1 回填用；游标分页，跨全部分片——一次性运维路径）。
     * 返回 snake_case 字段（与 binlog 列名同口径，供索引文档构建复用）。
     */
    public List<Map<String, Object>> scanSubOrders(long cursor, int limit) {
        List<SubOrder> subs = subOrderMapper.scanByCursor(cursor, limit);
        List<Map<String, Object>> rows = new java.util.ArrayList<>(subs.size());
        for (SubOrder sub : subs) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", sub.getId());
            row.put("order_id", sub.getOrderId());
            row.put("order_no", sub.getOrderNo());
            row.put("sub_order_no", sub.getSubOrderNo());
            row.put("user_id", sub.getUserId());
            row.put("seller_id", sub.getSellerId());
            row.put("status", sub.getStatus());
            row.put("total_amount", sub.getTotalAmount());
            row.put("discount_amount", sub.getDiscountAmount());
            row.put("pay_amount", sub.getPayAmount());
            row.put("created_at", sub.getCreatedAt());
            row.put("updated_at", sub.getUpdatedAt());
            rows.add(row);
        }
        return rows;
    }

    private OrderVO.OrderItemVO toItemVO(OrderItem item) {
        BigDecimal share = item.getDiscountShare() != null ? item.getDiscountShare() : BigDecimal.ZERO;
        BigDecimal total = item.getTotalAmount() != null ? item.getTotalAmount() : BigDecimal.ZERO;
        return OrderVO.OrderItemVO.builder()
                .skuId(item.getSkuId())
                .skuName(item.getSkuName())
                .skuImage(item.getSkuImage())
                .price(item.getPrice())
                .quantity(item.getQuantity())
                .totalAmount(item.getTotalAmount())
                .discountShare(share)
                .payAmount(total.subtract(share))
                .subOrderId(item.getSubOrderId())
                .build();
    }

    /** 子单 → VO（履约/售后展示锚点） */
    private OrderVO.SubOrderVO toSubOrderVO(SubOrder sub, List<OrderItem> subItems) {
        return OrderVO.SubOrderVO.builder()
                .subOrderId(sub.getId())
                .subOrderNo(sub.getSubOrderNo())
                .sellerId(sub.getSellerId())
                .status(sub.getStatus())
                .statusDesc(getSubOrderStatusDesc(sub.getStatus()))
                .totalAmount(sub.getTotalAmount())
                .discountAmount(sub.getDiscountAmount())
                .payAmount(sub.getPayAmount())
                .deliveredAt(sub.getDeliveredAt())
                .completedAt(sub.getCompletedAt())
                .items(subItems.stream().map(this::toItemVO).collect(Collectors.toList()))
                .build();
    }

    private String getSubOrderStatusDesc(Integer status) {
        return switch (status) {
            case 0 -> "待付款";
            case 1 -> "待发货";
            case 2 -> "已发货";
            case 3 -> "已完成";
            case 4 -> "已取消";
            case 5 -> "已退款";
            default -> "未知";
        };
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
    public OrderVO getOrderByOrderNo(Long requestUserId, String orderNo) {
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderNo(orderNo);
        if (mapping == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND, "订单不存在");
        }
        if (!requestUserId.equals(mapping.getUserId())) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看该订单");
        }
        return getOrderDetail(mapping.getUserId(), mapping.getOrderId());
    }

    public boolean isOrderOwner(Long userId, Long orderId) {
        // 使用带 userId 的查询确保 ShardingSphere 路由到正确分片
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getId, orderId)
                .eq(Order::getUserId, userId)
                .eq(Order::getDeleted, 0);
        Order order = orderMapper.selectOne(wrapper);
        return order != null;
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
    public boolean onRefundSuccess(Long orderId) {
        // 1. 通过映射表反查 userId（分库分表后 selectById 无法路由，必须带分片键）
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
        if (mapping == null) {
            log.error("[订单] 退款回调但映射表中找不到orderId: orderId={}", orderId);
            return false;
        }
        Long userId = mapping.getUserId();

        // 2. 使用 userId + orderId 联合查询（分片键路由）
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getId, orderId));
        if (order == null) {
            log.warn("[订单] 退款回调但订单不存在: orderId={}, userId={}", orderId, userId);
            return false;
        }
        if (order.getStatus() != 1 && order.getStatus() != 2 && order.getStatus() != 3) {
            // 决策 1（ITER-001）：放宽到 {1,2,3}——已发货(2)的全退同样收敛（修复 08 §5.2 缺口）；
            // "货先退回来"的约束由售后流程承担（申请资格 + 商家审核），支付域通知到达时业务决策已完成。
            // 5(已退款) 幂等返回 true；0(待付款)/4(已取消) 拒绝。
            log.warn("[订单] 退款回调但订单状态不允许退款: orderId={}, status={}", orderId, order.getStatus());
            // 拆单（ITER-001）：机会式自愈——父单已退款但子单可能未收敛（崩溃窗口），重放收敛（幂等）
            if (order.getStatus() == 5) {
                subOrderService.refundAll(userId, orderId);
            }
            return order.getStatus() == 5;
        }

        // 3. Event Sourcing: 追加退款事件并更新状态
        //    并发口径：Feign 同步与 REFUND_RESULT_TOPIC MQ 兜底可能同时到达，
        //    后到线程乐观锁失败（订单已被先到线程收敛到 5）→ 按幂等成功返回，
        //    避免向支付侧抛 500/503 触发无谓补偿（支付侧会误判"订单状态滞留"）。
        try {
            orderEventService.appendEvent(order, OrderEventService.EVENT_REFUNDED,
                    Map.of("refundTime", LocalDateTime.now().toString()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            Order latest = orderMapper.selectOne(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getUserId, userId)
                            .eq(Order::getId, orderId));
            boolean alreadyRefunded = latest != null && latest.getStatus() == 5;
            log.warn("[订单] 退款回调并发竞态(Feign/MQ 双通道): orderId={}, alreadyRefunded={}, cause={}",
                    orderId, alreadyRefunded, e.getMessage());
            if (alreadyRefunded) {
                // 拆单（ITER-001）：机会式自愈——先赢方若在"父单已 5、子单未收敛"窗口崩溃，此处重放（幂等）
                subOrderService.refundAll(userId, orderId);
            }
            return alreadyRefunded;
        }

        // 4. 拆单（ITER-001）：全退收敛——全部子单（1/2/3）→5（幂等；与父单退款事件同流程）
        subOrderService.refundAll(userId, orderId);

        // 5. 退款前先同步确认库存扣减：确保预扣记录被清理，
        //    避免「releaseInventory 回补 + refundRestore 回补」双通道叠加造成库存虚增，
        //    也避免预扣记录残留被 PreDeductTimeoutJob/对账二次回补。
        confirmInventoryDeductSync(orderId, order.getOrderNo(), userId);
        // T-071 退款回补库存（confirm 清理预扣记录后，仅此一条回补通道，refund-restore 幂等）
        restoreStockOnRefund(orderId, userId);

        // 6. 退还优惠券
        returnCouponIfUsed(order);

        takeSnapshot(orderId, userId, "REFUNDED");
        stringRedisTemplate.delete("myxhs:order:info:" + orderId);
        // 订单通知（type=5）：退款到账
        orderNotificationPublisher.publishStatusChanged(userId, orderId, order.getOrderNo(),
                "退款已到账", null);
        log.info("[订单] 退款成功: orderId={}", orderId);
        return true;
    }

    /**
     * 支付失败回调——自动终结订单（04B 收尾：第五条终结路径收敛到统一入口）
     * <p>
     * 通过映射表反查 userId，使用分片键加载订单；终结（状态 0→4 + 终结事件，Outbox/事务消息）
     * 统一走 terminateOrder；释放库存/退券/通知由 ORDER_TERMINATION_TOPIC 事件驱动（各域订阅）。
     * </p>
     * <p>
     * 回调语义（对齐旧实现，2026-10-11）：
     * - 竞态（关单/取消/支付先赢）→ terminateOrder 回读收敛为 ALREADY_TERMINAL/INVALID_STATUS，幂等返回不抛错；
     * - 基础设施失败（消息发送/DB）→ 抛异常由调用方处理（PayResultConsumer 走 MQ 重试；
     *   /pay-fail Feign 回调由 Controller 捕获，订单保持待付款由 30 分钟超时关单兜底）。
     * </p>
     */
    public void onPaymentFailed(Long orderId) {
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
        if (mapping == null) {
            log.warn("[订单] 支付失败回调但映射表中找不到orderId: orderId={}", orderId);
            return;
        }
        Long userId = mapping.getUserId();

        OrderTerminationService.Outcome outcome =
                terminateOrder(orderId, userId, TerminationReason.PAY_FAILED, "PAY_CALLBACK");
        switch (outcome) {
            case TERMINATED -> log.info("[订单] 支付失败已自动取消: orderId={}", orderId);
            case ALREADY_TERMINAL -> log.warn("[订单] 支付失败回调但订单已终态(幂等): orderId={}", orderId);
            case NOT_FOUND -> log.warn("[订单] 支付失败回调但订单不存在: orderId={}", orderId);
            case INVALID_STATUS -> log.warn("[订单] 支付失败回调但订单状态不是待付款: orderId={}", orderId);
        }
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
     * 必须通过 t_order_no_mapping 映射表反查 userId��才能路由到正确的分片。
     * </p>
     */
    public BigDecimal getOrderPayAmount(Long orderId) {
        // 通过映射表反查 userId（分库分表后 selectById 无法路由）
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
        if (mapping == null) {
            return null;
        }
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, mapping.getUserId())
                        .eq(Order::getId, orderId));
        if (order == null) {
            return null;
        }
        if (order.getStatus() != 0) {
            return null;
        }
        return order.getPayAmount();
    }

    /**
     * 查询订单状态（P1-1 修复：支付前回查订单是否待付款，防止对已取消/已支付的订单发起支付）
     *
     * @return 订单状态码（0=待付款），订单不存在返回 null
     */
    public Integer getOrderStatus(Long orderId) {
        OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
        if (mapping == null) {
            return null;
        }
        Order order = orderMapper.selectOne(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, mapping.getUserId())
                        .eq(Order::getId, orderId));
        return order != null ? order.getStatus() : null;
    }
}