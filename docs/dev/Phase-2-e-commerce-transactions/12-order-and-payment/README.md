# 订单与支付

> 所属服务：my-xhs-order (9011) + my-xhs-payment (9012) | 开发阶段：Phase-2 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

订单是电商交易链路的终点，串联商品、库存、优惠券、支付四大子系统。创建订单使用 RocketMQ 事务消息 + 本地消息表兜底，确保订单创建与库存预扣/优惠券使用的分布式一致性。订单状态流转使用状态机配置化管理（9 种状态）。超时关单使用 RocketMQ 延时消息（30 分钟）+ 定时任务兜底。支付使用 MockPayService 模拟实现，生产环境只需替换 1 个实现类。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 创建订单（事务消息） | ✅ | 半消息→本地事务→提交/回滚→消费→回查 |
| 本地消息表兜底 | ✅ | 同一事务写 order + local_message，定时扫描补发 |
| 订单状态机（6 种核心状态） | ✅ | 待付款→已付款→已发货→已完成→已取消→已退款（MVP 先实现 6 种，架构文档规划的 9 种完整状态后续扩展） |
| 超时自动关单 | ✅ | RocketMQ 延时消息 30 分钟 + 定时任务兜底 |
| 订单快照 | ✅ | 每次状态变更记录 JSON 快照，支持审计回溯 |
| 模拟支付（Mock） | ✅ | MockPayService 策略模式，流水号 MOCK_ 开头 |
| 下单异步编排 | ✅ | CompletableFuture 并行查用户/商品/库存/券（串行 600ms→并行 150ms） |
| 分库分表 | ✅ | ShardingSphere 按 buyer_id % 4 分库（Phase-5 实施，当前单库单表，代码层预留分片键设计） |
| 幂等下单 | ✅ | bizIdentifier + Redis 分布式锁 |
| 真实支付对接 | ❌ | 需对接支付宝/微信 SDK，不在 MVP 范围 |
| 订单拆单/合单 | ❌ | 复杂场景，后续扩展 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 订单总量 | 10 亿 | 1000 万用户 × 平均 100 单/年 × 10 年 |
| 日增量 | 50 万/天 | 100 万日活 × 50% 下单率 |
| 下单峰值 QPS | 2000 | 大促期间集中下单 |
| 订单查询 QPS | 5000 | 用户频繁查看订单状态 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway(鉴权) → my-xhs-order(9011) → MySQL(t_order/t_order_item/t_local_message)
                              │                      + Redis(订单缓存/分布式锁)
                              │
                              ├── Feign → my-xhs-user(查地址)
                              ├── Feign → my-xhs-product(查SKU)
                              ├── Feign → my-xhs-inventory(预扣库存)
                              ├── Feign → my-xhs-coupon(使用优惠券)
                              ├── Feign → my-xhs-payment(创建支付单)
                              │
                              └── RocketMQ ← 事务消息/延时消息/状态变更消息
                                     │
                                     ├── Inventory Consumer(扣库存)
                                     ├── Coupon Consumer(扣券)
                                     └── Payment Consumer(创支付单)
```

### 2.2 模块交互

| 调用方 | 被调用方 | 方式 | 场景 |
|--------|---------|------|------|
| my-xhs-order | my-xhs-user | Feign | 下单时查收货地址 |
| my-xhs-order | my-xhs-product | Feign | 下单时查 SKU 信息（名称/价格/图片） |
| my-xhs-order | my-xhs-inventory | Feign | 下单时预扣库存 |
| my-xhs-order | my-xhs-coupon | Feign | 下单时使用优惠券 |
| my-xhs-order | my-xhs-payment | Feign | 创建支付单 |
| my-xhs-order | RocketMQ | 事务消息 | 下单事务消息 |
| my-xhs-order | RocketMQ | 延时消息 | 超时关单（30 分钟） |
| my-xhs-payment | my-xhs-order | MQ 回调 | 支付成功后通知订单服务更新状态 |

### 2.3 核心流程时序图

**创建订单（事务消息 6 步流程）：**

```
1. Client → OrderService: POST /api/order/create
2. OrderService: 生成 bizIdentifier，Redis SET NX 分布式锁（防重复下单）
3. OrderService → RocketMQ: 发送半消息(Half Message)到 ORDER_TOPIC:CREATE
4. RocketMQ: 存储半消息 → 返回确认
5. OrderService: 执行本地事务（同一 DB 事务）：
   a. CompletableFuture 并行查询：
      - Feign → User: 查收货地址
      - Feign → Product: 查 SKU 信息
      - Feign → Inventory: 校验库存
      - Feign → Coupon: 校验优惠券
   b. 计算金额（总金额 - 优惠券折扣）
   c. INSERT t_order + t_order_item（地址快照 + SKU 快照）
   d. INSERT t_local_message（status=待发送）
6a. 本地事务成功 → 提交半消息(Commit) → 消息对消费者可见
6b. 本地事务失败 → 回滚半消息(Rollback) → 消息被删除
7. 消费者消费消息：
   a. Inventory: 预扣减库存
   b. Coupon: 标记优惠券已使用
8. 消费失败 → 重试 16 次 → 死信队列 → 告警
9. 发送延时消息（30 分钟后超时关单）
10. 如果 Broker 未收到 Commit/Rollback → 回查本地事务状态 → 自动补发
```

**订单状态机（9 种状态）：**

```
已创建(0) ──待付款──→ 已付款(1) ──发货──→ 已发货(2) ──完成──→ 已完成(3)
   │                     │
   │ 超时/取消            │ 退款
   ▼                     ▼
已取消(4)              已退款(5)
```

> **简化说明**：实际 DDL 中 status 定义为 0-待付款 1-已付款 2-已发货 3-已完成 4-已取消 5-已退款。架构文档中规划的 9 种状态（已创建/已确认/已支付/已履约/出库中/配送中/已签收/已取消/已退款）是完整版，MVP 阶段先实现 6 种核心状态。

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 订单主表（实际 DDL 来自 init-databases.sql）
CREATE TABLE IF NOT EXISTS t_order (
    id              BIGINT        NOT NULL COMMENT '订单ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID',
    order_no        VARCHAR(64)   NOT NULL COMMENT '订单号',
    total_amount    DECIMAL(10,2) NOT NULL COMMENT '订单总金额',
    pay_amount      DECIMAL(10,2) NOT NULL COMMENT '实付金额',
    discount_amount DECIMAL(10,2) DEFAULT 0 COMMENT '优惠金额',
    coupon_id       BIGINT        DEFAULT NULL COMMENT '使用的优惠券ID',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-待付款 1-已付款 2-已发货 3-已完成 4-已取消 5-已退款',
    address_snapshot VARCHAR(1024) DEFAULT NULL COMMENT '收货地址快照(JSON)',
    remark          VARCHAR(256)  DEFAULT NULL COMMENT '订单备注',
    paid_at         DATETIME      DEFAULT NULL COMMENT '支付时间',
    delivered_at    DATETIME      DEFAULT NULL COMMENT '发货时间',
    completed_at    DATETIME      DEFAULT NULL COMMENT '完成时间',
    cancelled_at    DATETIME      DEFAULT NULL COMMENT '取消时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_order_no (order_no),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表';

-- 订单明细表
CREATE TABLE IF NOT EXISTS t_order_item (
    id              BIGINT        NOT NULL COMMENT 'ID',
    order_id        BIGINT        NOT NULL COMMENT '订单ID',
    sku_id          BIGINT        NOT NULL COMMENT 'SKU ID',
    spu_id          BIGINT        NOT NULL COMMENT 'SPU ID',
    sku_name        VARCHAR(256)  NOT NULL COMMENT 'SKU名称快照',
    sku_image       VARCHAR(512)  DEFAULT NULL COMMENT 'SKU图片快照',
    price           DECIMAL(10,2) NOT NULL COMMENT '单价快照',
    quantity        INT           NOT NULL COMMENT '数量',
    total_amount    DECIMAL(10,2) NOT NULL COMMENT '小计金额',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细表';

-- 支付记录表
CREATE TABLE IF NOT EXISTS t_payment (
    id              BIGINT        NOT NULL COMMENT 'ID',
    order_id        BIGINT        NOT NULL COMMENT '订单ID',
    user_id         BIGINT        NOT NULL COMMENT '用户ID',
    payment_no      VARCHAR(64)   NOT NULL COMMENT '支付流水号',
    amount          DECIMAL(10,2) NOT NULL COMMENT '支付金额',
    pay_type        TINYINT       NOT NULL COMMENT '支付方式：1-支付宝(Mock) 2-微信(Mock)',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-待支付 1-支付成功 2-支付失败 3-已退款',
    paid_at         DATETIME      DEFAULT NULL COMMENT '支付成功时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_payment_no (payment_no),
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付记录表';

-- 本地消息表（与 t_order 同库，保证本地事务原子性）
CREATE TABLE IF NOT EXISTS t_local_message (
    id              BIGINT        NOT NULL COMMENT '消息ID（雪花算法）',
    transaction_id  VARCHAR(64)   NOT NULL COMMENT '事务ID（全局唯一）',
    service_name    VARCHAR(32)   NOT NULL COMMENT '服务名（inventory/coupon/payment）',
    operation_type  VARCHAR(32)   NOT NULL COMMENT '操作类型（DEDUCT_INVENTORY/USE_COUPON/CREATE_PAYMENT）',
    payload         TEXT          NOT NULL COMMENT '操作参数JSON',
    status          TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0-待处理 1-成功 2-失败 3-死信',
    retry_count     INT           NOT NULL DEFAULT 0 COMMENT '重试次数（最大3次）',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_transaction_id (transaction_id),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地消息表';
```

### 3.2 索引设计

| 表 | 索引名 | 字段 | 使用场景 |
|----|--------|------|----------|
| t_order | `uk_order_no` | order_no | 按订单号查询（唯一） |
| t_order | `idx_user_id` | user_id | 用户订单列表（分库分片键） |
| t_order | `idx_status` | status | 按状态筛选（超时关单扫描） |
| t_order | `idx_created_at` | created_at | 按时间排序 |
| t_order_item | `idx_order_id` | order_id | 按订单查明细 |
| t_payment | `uk_payment_no` | payment_no | 按支付流水号查询（唯一） |
| t_local_message | `idx_status_created` | (status, created_at) | 定时扫描待处理/失败消息 |

### 3.3 分库分表策略

| 维度 | 策略 | 说明 |
|------|------|------|
| 分片键 | user_id (buyer_id) | 同一用户的订单在同一库，避免跨库查询 |
| 分片算法 | user_id % 4 | 4 个库均匀分布 |
| 分片表 | t_order + t_order_item | 同分片键，保证订单和明细在同一库（避免跨库 JOIN） |
| 主键策略 | 雪花 ID | 不依赖 MySQL auto-increment |

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `order:info:{orderId}` | Hash | 30min | 订单详情缓存 |
| `order:create:lock:{userId}` | String | 10s | 下单分布式锁（防重复下单） |
| `order:status:{orderId}` | String | 30min | 订单状态缓存 |
| `order:idempotent:{bizIdentifier}` | String | 24h | 幂等下单标记 |

### 4.2 缓存更新策略

| 操作 | 策略 | 说明 |
|------|------|------|
| 读订单详情 | Cache Aside | 先查 Redis → Miss → 查 DB → 写 Redis |
| 订单状态变更 | 先更新 DB → 再删缓存 | 状态变更后删除 order:info 和 order:status |
| 下单幂等 | Redis SET NX | bizIdentifier 作为幂等键，24 小时过期 |

---

## 📡 五、接口设计

### 5.1 接口列表

**订单服务**

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/order/create` | 创建订单 | ✅ |
| GET | `/api/order/{orderId}` | 订单详情 | ✅ |
| GET | `/api/order/list` | 我的订单列表 | ✅ |
| POST | `/api/order/cancel` | 取消订单 | ✅ |
| POST | `/api/order/confirm` | 确认收货 | ✅ |

**支付服务**

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/pay/create` | 创建支付单 | ✅ |
| POST | `/api/pay/callback` | 支付回调（Mock） | ❌（内部调用） |
| GET | `/api/pay/status/{orderId}` | 查询支付状态 | ✅ |

### 5.2 请求/响应示例

**创建订单**

```http
POST /api/order/create
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "skuItems": [
    {"skuId": 1001, "quantity": 2},
    {"skuId": 1002, "quantity": 1}
  ],
  "couponId": 5001,
  "addressId": 100001,
  "remark": "请尽快发货",
  "bizIdentifier": "uuid-unique-idempotent-key"
}
```

```json
{
  "code": 200,
  "msg": "下单成功",
  "data": {
    "orderId": 200001,
    "orderNo": "ORD20260512001",
    "totalAmount": 299.00,
    "payAmount": 249.00,
    "discountAmount": 50.00,
    "status": 0,
    "statusDesc": "待付款"
  }
}
```

**支付回调（Mock）**

```http
POST /api/pay/callback
Content-Type: application/json

{
  "paymentNo": "MOCK_PAY_20260512001",
  "orderId": 200001,
  "status": 1,
  "tradeNo": "MOCK_2026051200001"
}
```

---

## 💻 六、核心代码实现

### 6.1 创建订单（事务消息 + 异步编排）

```java
/**
 * 订单创建服务
 * 关键点：幂等校验 → 异步并行查询 → 事务消息 → 本地消息表
 */
@Service
public class OrderCreateService {

    /**
     * 创建订单入口
     */
    public OrderVO createOrder(Long userId, OrderCreateRequest request) {
        // 1. 幂等校验（bizIdentifier + Redis SET NX）
        String idempotentKey = "order:idempotent:" + request.getBizIdentifier();
        if (!redisOperator.setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS)) {
            throw new BizException(BizErrorCode.ORDER_DUPLICATE, "请勿重复下单");
        }

        // 2. 分布式锁防并发（同一用户 10 秒内只能下 1 单）
        String lockKey = "order:create:lock:" + userId;
        return distributedLockHelper.executeWithLock(lockKey, 10, TimeUnit.SECONDS, () -> {
            // 3. CompletableFuture 异步并行查询（串行 600ms → 并行 150ms）
            CompletableFuture<AddressVO> addressFuture = CompletableFuture.supplyAsync(
                    () -> userFeignClient.getAddress(request.getAddressId()));
            CompletableFuture<List<SkuVO>> skuFuture = CompletableFuture.supplyAsync(
                    () -> productFeignClient.batchGetSku(request.getSkuIds()));
            CompletableFuture<Boolean> stockFuture = CompletableFuture.supplyAsync(
                    () -> inventoryFeignClient.checkStock(request.getSkuItems()));
            CompletableFuture<CouponVO> couponFuture = request.getCouponId() != null
                    ? CompletableFuture.supplyAsync(
                    () -> couponFeignClient.validateCoupon(userId, request.getCouponId()))
                    : CompletableFuture.completedFuture(null);

            // 4. 等待所有查询完成
            CompletableFuture.allOf(addressFuture, skuFuture, stockFuture, couponFuture).join();

            AddressVO address = addressFuture.get();
            List<SkuVO> skuList = skuFuture.get();
            Boolean stockOk = stockFuture.get();
            CouponVO coupon = couponFuture.get();

            if (!stockOk) {
                throw new BizException(BizErrorCode.STOCK_NOT_ENOUGH);
            }

            // 5. 计算金额
            BigDecimal totalAmount = calculateTotalAmount(request.getSkuItems(), skuList);
            BigDecimal discountAmount = coupon != null ? coupon.getDiscountAmount() : BigDecimal.ZERO;
            BigDecimal payAmount = totalAmount.subtract(discountAmount);

            // 6. 发送事务消息
            OrderCreateContext context = new OrderCreateContext(
                    userId, request, address, skuList, coupon,
                    totalAmount, discountAmount, payAmount);
            return orderTransactionProducer.sendTransactionMessage(context);
        });
    }
}
```

### 6.2 事务消息监听器（本地事务 + 回查）

```java
/**
 * 订单事务消息监听器
 * 关键点：executeLocalTransaction 执行本地事务
 *         checkLocalTransaction 回查本地事务状态
 */
@RocketMQTransactionListener
public class OrderTransactionListener implements RocketMQLocalTransactionListener {

    /**
     * 执行本地事务（半消息发送成功后回调）
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        OrderCreateContext context = (OrderCreateContext) arg;
        try {
            // 同一个 DB 事务中：创建订单 + 写本地消息表
            orderTransactionService.executeLocalTransaction(context);
            return RocketMQLocalTransactionState.COMMIT; // 提交半消息
        } catch (Exception e) {
            log.error("本地事务执行失败: {}", context.getOrderNo(), e);
            return RocketMQLocalTransactionState.ROLLBACK; // 回滚半消息
        }
    }

    /**
     * 回查本地事务状态（Broker 未收到 Commit/Rollback 时触发）
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String orderNo = msg.getHeaders().get("orderNo", String.class);
        // 查订单表是否存在
        Order order = orderMapper.selectByOrderNo(orderNo);
        if (order != null) {
            return RocketMQLocalTransactionState.COMMIT; // 订单存在，提交
        }
        return RocketMQLocalTransactionState.ROLLBACK; // 订单不存在，回滚
    }
}

/**
 * 本地事务执行（同一 DB 事务）
 */
@Service
public class OrderTransactionService {

    @Transactional(rollbackFor = Exception.class)
    public void executeLocalTransaction(OrderCreateContext ctx) {
        // 1. 创建订单主表
        Order order = buildOrder(ctx);
        orderMapper.insert(order);

        // 2. 创建订单明细
        List<OrderItem> items = buildOrderItems(order.getId(), ctx);
        orderItemMapper.batchInsert(items);

        // 3. 写入本地消息表（与订单同库同事务，保证原子性）
        LocalMessage message = new LocalMessage();
        message.setId(idGenerator.nextId());
        message.setTransactionId(order.getOrderNo());
        message.setServiceName("order");
        message.setOperationType("ORDER_CREATE");
        message.setPayload(JSON.toJSONString(ctx));
        message.setStatus(0); // 待处理
        localMessageMapper.insert(message);

        // 4. 发送延时消息（30 分钟后超时关单）
        rocketMQTemplate.syncSend("order-close-delay",
                MessageBuilder.withPayload(new OrderCloseEvent(order.getId()))
                        .build(),
                3000, 16); // delayLevel=16 对应 30 分钟
    }
}
```

### 6.3 超时关单

```java
/**
 * 超时关单消费者（延时消息触发）
 * 关键点：只关"待付款"状态的订单，已支付的不关
 */
@Component
@RocketMQMessageListener(topic = "order-close-delay", consumerGroup = "order-close-group")
public class OrderCloseDelayConsumer implements RocketMQListener<OrderCloseEvent> {

    @Override
    public void onMessage(OrderCloseEvent event) {
        Order order = orderMapper.selectById(event.getOrderId());
        if (order == null || order.getStatus() != 0) {
            // 订单不存在或已不是"待付款"状态，跳过
            return;
        }

        // 关闭订单
        orderMapper.updateStatus(order.getId(), 4, LocalDateTime.now()); // 4=已取消

        // 释放库存
        inventoryFeignClient.releaseStock(order.getId());

        // 退还优惠券
        if (order.getCouponId() != null) {
            couponFeignClient.releaseCoupon(order.getUserId(), order.getCouponId());
        }

        // 清除缓存
        redisOperator.delete("order:info:" + order.getId());
        redisOperator.delete("order:status:" + order.getId());

        log.info("订单超时关闭: orderId={}", order.getId());
    }
}

/**
 * 定时任务兜底（每分钟扫描超时未支付订单）
 * 防止延时消息丢失
 */
@Scheduled(fixedRate = 60000)
public void closeTimeoutOrders() {
    // 查询 30 分钟前创建且仍为"待付款"的订单
    LocalDateTime deadline = LocalDateTime.now().minusMinutes(30);
    List<Order> timeoutOrders = orderMapper.selectTimeoutOrders(0, deadline);
    for (Order order : timeoutOrders) {
        try {
            closeOrder(order);
        } catch (Exception e) {
            log.error("兜底关单失败: orderId={}", order.getId(), e);
        }
    }
}
```

### 6.4 Mock 支付（策略模式）

```java
/**
 * 支付服务接口（策略模式）
 * 生产环境只需新增 AlipayServiceImpl / WechatPayServiceImpl
 */
public interface PayService {
    PayResult pay(PayCreateRequest request);
    PayResult refund(PayRefundRequest request);
}

/**
 * Mock 支付实现（开发/测试环境）
 * 流水号以 MOCK_ 开头，直接返回支付成功
 */
@Service
@ConditionalOnProperty(name = "pay.type", havingValue = "mock", matchIfMissing = true)
public class MockPayService implements PayService {

    @Override
    public PayResult pay(PayCreateRequest request) {
        // 模拟支付成功
        String paymentNo = "MOCK_PAY_" + System.currentTimeMillis();
        String tradeNo = "MOCK_" + UUID.randomUUID().toString().substring(0, 16);

        // 创建支付记录
        Payment payment = new Payment();
        payment.setId(idGenerator.nextId());
        payment.setOrderId(request.getOrderId());
        payment.setUserId(request.getUserId());
        payment.setPaymentNo(paymentNo);
        payment.setAmount(request.getAmount());
        payment.setPayType(request.getPayType());
        payment.setStatus(1); // 支付成功
        payment.setPaidAt(LocalDateTime.now());
        paymentMapper.insert(payment);

        // 发送支付成功 MQ 消息 → 通知订单服务更新状态
        rocketMQTemplate.syncSend("payment-callback",
                MessageBuilder.withPayload(new PaymentCallbackEvent(
                        request.getOrderId(), paymentNo, tradeNo, 1))
                        .build());

        return PayResult.success(paymentNo, tradeNo);
    }
}
```

### 6.5 订单快照

```java
/**
 * 订单快照服务
 * 每次状态变更记录完整的订单 JSON 快照
 * 用途：审计回溯、纠纷处理、数据恢复
 */
@Service
public class OrderSnapshotService {

    /**
     * 记录订单快照（状态变更时调用）
     */
    public void takeSnapshot(Long orderId, String event) {
        Order order = orderMapper.selectById(orderId);
        List<OrderItem> items = orderItemMapper.selectByOrderId(orderId);

        OrderSnapshot snapshot = new OrderSnapshot();
        snapshot.setId(idGenerator.nextId());
        snapshot.setOrderId(orderId);
        snapshot.setEvent(event); // 如 "PAID"、"CANCELLED"、"DELIVERED"
        snapshot.setSnapshotData(JSON.toJSONString(Map.of(
                "order", order,
                "items", items,
                "timestamp", LocalDateTime.now()
        )));
        snapshotMapper.insert(snapshot);
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 分布式事务：事务消息 vs TCC vs Seata AT

| 维度 | RocketMQ 事务消息（✅ 选定） | TCC | Seata AT |
|------|---------------------------|-----|---------|
| 一致性 | 最终一致 | 最终一致 | 最终一致 |
| 性能 | 高（异步） | 中（3 个接口） | 中（全局锁） |
| 业务侵入 | 中（需实现 Listener） | 高（Try/Confirm/Cancel） | 低（自动回滚） |
| 适用场景 | 核心链路（下单） | 资金类（转账） | 非核心链路 |

**选择理由**：下单链路对实时性要求高，事务消息异步解耦性能最好。TCC 业务侵入太大（每个操作要写 3 个接口），Seata AT 有全局锁性能瓶颈。

### 7.2 超时关单：延时消息 vs 定时任务 vs Redis 过期回调

| 维度 | 延时消息 + 定时任务兜底（✅ 选定） | 纯定时任务 | Redis Key 过期回调 |
|------|-------------------------------|-----------|------------------|
| 精度 | 秒级（延时消息） | 分钟级（扫描间隔） | 秒级 |
| 可靠性 | 高（MQ 持久化 + 定时兜底） | 中（单点扫描） | 低（Redis 不保证回调） |
| 性能 | 高（无需扫描 DB） | 低（全表扫描） | 高 |

**选择理由**：延时消息精度高且不需要扫描 DB，定时任务作为兜底方案覆盖 MQ 丢消息的极端场景。

### 7.3 支付方案：Mock vs 真实对接

| 维度 | MockPayService（✅ 当前版本） | 真实支付对接 |
|------|---------------------------|------------|
| 依赖 | 零外部依赖 | 需要商户号、证书、SDK |
| 链路完整性 | ✅ 完整（下单→支付→回调→状态更新） | ✅ 完整 |
| 切换成本 | 新增 1 个实现类即可 | — |

**选择理由**：策略模式 + `@ConditionalOnProperty`，Mock 和真实支付只是不同的实现类，切换零改动。

---

## 🐛 八、踩坑记录

### 8.1 事务消息回查频率过高

- **现象**：Broker 频繁回查本地事务状态，日志刷屏
- **原因**：本地事务执行太慢（> 60 秒），Broker 认为超时触发回查
- **解决**：本地事务只做快操作（写 order 表 + 写 local_message 表），慢操作（扣库存/扣券）交给消费端异步处理
- **教训**：事务消息的本地事务要尽量快，复杂操作放消费端

### 8.2 分库分表后跨库查询

- **现象**：后台管理按订单号查询，ShardingSphere 全库扫描
- **原因**：order_no 不是分片键，无法路由到具体库
- **解决**：订单号中嵌入 user_id 信息（如 `ORD_{userId}_{timestamp}`），或建立 order_no → user_id 的映射表
- **教训**：分库分表后，非分片键查询必须有路由方案

### 8.3 重复下单

- **现象**：用户快速点击两次下单按钮，创建了两个订单
- **原因**：前端未做防重，后端未做幂等
- **解决**：前端按钮点击后禁用 3 秒 + 后端 bizIdentifier Redis SET NX 幂等
- **教训**：幂等必须前后端双重保障

### 8.4 取消订单未释放库存

- **现象**：取消订单后库存未恢复
- **原因**：取消订单只改了 status，未调用库存服务释放
- **解决**：取消订单时联动释放库存 + 退还优惠券 + 清除缓存
- **教训**：订单取消不是只改状态，关联资源都要释放

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 正常下单 | 合法 SKU + 地址 + 优惠券 | 返回订单 ID，status=0 | ⬜ |
| 重复下单（相同 bizIdentifier） | 相同幂等键 | 返回"请勿重复下单" | ⬜ |
| 库存不足下单 | SKU 库存为 0 | 返回"库存不足" | ⬜ |
| 优惠券无效 | 已过期优惠券 | 返回"优惠券无效" | ⬜ |
| 支付成功 | Mock 支付 | 订单 status 变为 1（已付款） | ⬜ |
| 超时关单（延时消息） | 30 分钟未支付 | 订单 status 变为 4（已取消），库存释放 | ⬜ |
| 超时关单（定时任务兜底） | 延时消息丢失 | 定时任务扫描关单 | ⬜ |
| 取消订单 | 待付款订单 | status=4 + 库存释放 + 优惠券退还 | ⬜ |
| 已支付订单取消 | 已付款订单 | 返回"已支付订单不可取消" | ⬜ |
| 事务消息回查 | 模拟 Broker 未收到 Commit | 回查订单表，存在则 Commit | ⬜ |

### 9.2 压测数据（预期基线）

| 场景 | 并发数 | 目标 QPS | 目标平均 RT | 目标 P99 RT | 目标错误率 |
|------|--------|---------|-----------|-----------|-----------|
| 创建订单 | 100 | 2000 | < 200ms | < 1000ms | < 0.1% |
| 订单详情查询 | 200 | 5000 | < 30ms | < 100ms | < 0.1% |
| 订单列表查询 | 100 | 3000 | < 50ms | < 200ms | < 0.1% |

### 9.3 关键场景验证

- [ ] 事务消息完整链路：下单→半消息→本地事务→Commit→消费→扣库存→扣券
- [ ] 本地消息表兜底：模拟 MQ 不可用→本地消息表记录→MQ 恢复后定时任务补发
- [ ] 并发下单同一 SKU：100 并发下单最后 1 件库存→只有 1 个成功
- [ ] 分库分表路由：不同 user_id 的订单路由到不同库

---

## 🎤 十、面试考察点

### Q1: 下单流程怎么保证分布式事务一致性？

**推荐回答思路**：

> 1. "用 RocketMQ 事务消息 + 本地消息表双保险"
> 2. "事务消息 6 步流程：发送半消息→执行本地事务（创建订单+写本地消息表，同一 DB 事务）→提交/回滚→消费者扣库存扣券→消费确认→Broker 回查兜底"
> 3. "本地消息表是最后的兜底：即使 MQ Broker 整体宕机，本地消息表和订单表在同一个 MySQL 事务中，只要 MySQL 不挂消息就不会丢。Broker 恢复后定时任务扫描补发"
> 4. "消费端用 @Idempotent 保证幂等，重复消费不会重复扣库存"

### Q2: 订单超时未支付怎么自动取消？

**推荐回答思路**：

> 1. "主方案：RocketMQ 延时消息。下单时发送 30 分钟延时消息，到期后消费者检查订单状态，仍为'待付款'则关闭订单"
> 2. "兜底方案：定时任务每分钟扫描 `status=0 AND created_at < NOW()-30min` 的订单，执行关单"
> 3. "关单联动：改 status=已取消 + 释放库存 + 退还优惠券 + 清除缓存"
> 4. "为什么不只用定时任务？定时任务有分钟级延迟，延时消息秒级精度更好。为什么不只用延时消息？MQ 可能丢消息，定时任务兜底"

### Q3: 为什么要做订单快照？

**推荐回答思路**：

> 1. "订单快照记录每次状态变更时的完整订单数据（JSON），包括商品名称、价格、地址等"
> 2. "为什么需要？商品可能改价、地址可能修改、优惠券可能过期——下单时的数据和现在的数据可能不同"
> 3. "用途：纠纷处理（用户说'我下单时价格是 99 元'，快照可以证明）、审计合规、数据恢复"
> 4. "实现：每次状态变更时调用 `OrderSnapshotService.takeSnapshot()`，记录当前订单+明细的 JSON"

### Q4: 分库分表后怎么查询？

**推荐回答思路**：

> 1. "按 user_id 分 4 库，同一用户的订单在同一库"
> 2. "用户查自己的订单列表：带 user_id 条件，ShardingSphere 精确路由到 1 个库"
> 3. "按订单号查询：订单号中嵌入 user_id 信息，或建立 order_no → user_id 映射表"
> 4. "后台管理全量查询：走 ES 宽表（Canal 同步），不走分库分表的 MySQL"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📖 《深入理解分布式事务》 | 第 6 章 | 可靠消息最终一致性方案、事务消息原理 |
| 📖 《凤凰架构》 | 第 3 章 | 本地消息表兜底方案 |
| 📄 phase-2/README.md | §3.12 | 订单与支付完整设计（API/表/Redis Key/Java文件/事务消息流程） |
| 📄 02-module-detailed-design.md | §8 | 订单服务核心链路/状态机/Mock支付 |
| 📄 03-distributed-solutions.md | §3 | 分布式事务 4 方案对比 + 事务消息流程 |
| 📄 22-distributed-transaction | 全文 | 分布式事务专题 |
| 📄 26-database-sharding-practice | 全文 | 分库分表实战专题 |
