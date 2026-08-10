# 03 — 事务消息下单全链路

> **前置阅读**：[架构文档 §4.1](01-order-module.md) · [测试 1 (create)](02-order-test-record.md)
> **源码**：`OrderService.createOrder()` (105-230 行) · `OrderTransactionListener` · `OrderTransactionService`

## 为什么下单用事务消息而非直接 `@Transactional`？

如果下单直接用 `@Transactional` 包住所有操作（INSERT order + item + 本地消息）——订单写入了 MySQL，但后续需要通知 inventory 预扣、coupon 核销。用同步 Feign 调用——响应慢，失败影响下单。用异步 MQ——消息可能丢失，订单写了但下游不知道。

RocketMQ 事务消息解决了这个问题：**先发半消息 → 执行本地事务 → 根据结果 Commit/Rollback**。如果本地事务失败，半消息被丢弃；如果成功，消息对消费者可见。

```
半消息（对 Consumer 不可见）
  → 回调 executeLocalTransaction()
    → @Transactional INSERT order + item + local_message
      → 成功 → COMMIT（消息对 Consumer 可见）→ inventory.consumer 预扣
      → 失败 → ROLLBACK（消息丢弃）→ 订单不创建
```

**半消息的语义**：RocketMQ 收到半消息后不投递，而是回调 Producer 的 `executeLocalTransaction()`。Producer 执行完本地事务后返回 `COMMIT` 或 `ROLLBACK`。如果 Producer 崩溃未返回，RocketMQ 会定时回查 `checkLocalTransaction()` 确认状态。

---

## 下单全链路源码追踪

入口：`POST /api/order/create` → `OrderController.createOrder()` → `OrderService.createOrder()` (105-230 行)

### Step 1：幂等校验（源码行 110-115）

```java
String idempotentKey = "order:idempotent:" + request.getBizIdentifier();
Boolean setResult = stringRedisTemplate.opsForValue()
        .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
if (Boolean.FALSE.equals(setResult)) {
    throw new BizException(ResultCode.IDEMPOTENT_REJECT, "请勿重复下单");
}
```

**为什么用 SETNX 而不是数据库唯一键？** `bizIdentifier` 是客户端生成的幂等键（如购物车提交 ID）。MySQL 唯一索引可以做幂等，但需要等事务提交后才能发现冲突——本地事务执行到一半才发现重复，浪费了 ShardingSphere 的路由和 SQL 执行。Redis SETNX 在毫秒级就能拦截重复请求，避免进入事务消息流程。

**24 小时 TTL**：`bizIdentifier` 不需要永久存储——24 小时后重试下单是合理的用户行为。如果 TTL 永久，Redis 内存会无限增长。

**幂等键释放策略**（源码注释）：

```java
// 幂等键释放策略：
// - 本地事务提交前失败 → 释放幂等键（允许重试）
// - 本地事务提交后 → 不释放幂等键（订单已创建，后续步骤失败不影响）
```

关键区分：如果事务消息发送失败（半消息未投递），幂等键被释放，用户可以重试。如果半消息已投递且本地事务已提交，幂等键保留，防止重复下单。

### Step 2：分布式锁（源码行 121-125）

```java
String lockKey = "order:create:lock:" + userId;
String lockValue = UUID.randomUUID().toString();
Boolean lockResult = stringRedisTemplate.opsForValue()
        .setIfAbsent(lockKey, lockValue, 10, TimeUnit.SECONDS);
if (Boolean.FALSE.equals(lockResult)) {
    stringRedisTemplate.delete(idempotentKey); // 释放幂等键
    throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, "操作过于频繁，请稍后重试");
}
```

**为什么需要分布式锁？** 幂等键只防止同一个 `bizIdentifier` 的重复提交——但如果用户用不同的 `bizIdentifier` 快速提交多个订单，每个都通过幂等检查。分布式锁限制同一用户 10 秒内只能有 1 个订单在创建中。

**Lua 安全释放**（源码行 81-84）：

```lua
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else return 0 end
```

```java
private void safeUnlock(String lockKey, String lockValue) {
    stringRedisTemplate.execute(
        new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class),
        List.of(lockKey), lockValue);
}
```

**为什么必须用 Lua？** 如果分两步（GET + 判断 + DEL）——在 GET 和 DEL 之间锁可能过期，被另一个请求获取。DEL 会误删别人的锁。Lua 的 `GET + 比较 + DEL` 是原子的——只释放自己持有的锁（value 匹配 UUID）。

### Step 3：构建事务消息（源码行 157-162）

```java
org.springframework.messaging.Message<String> msg = MqTraceHelper.wrapWithTraceId(
    MessageBuilder.withPayload(payload)
        .setHeader("orderNo", orderNo)
        .setHeader("userId", userId.toString())
        .setHeader(RocketMQHeaders.KEYS, orderNo)
        .build());
```

**`MqTraceHelper.wrapWithTraceId()` 的作用**：从当前线程的 MDC 中取出 `traceId`，注入到 MQ 消息的 Header 中（Key=`X-Trace-Id`）。Consumer 消费时通过 `restoreTraceId(msg)` 恢复——同一 traceId 从 order 贯穿到 inventory，在 ES 中一键串联全链路日志。

**修复前**：`createOrder` 没有调用 `wrapWithTraceId()`，traceId 在 order 本地有但在 inventory Consumer 日志中为空 `[]`。修复后已验证跨服务传播。

### Step 4：发送事务消息（源码行 166）

```java
SendResult sendResult = rocketMQTemplate.sendMessageInTransaction(
    ORDER_TRANSACTION_TOPIC, msg, context);
```

**`sendMessageInTransaction` 的内部流程**：

```
RocketMQTemplate.sendMessageInTransaction(topic, msg, arg)
  │
  ├── 1. 发送半消息到 Broker（Consumer 不可见）
  │
  ├── 2. Broker 回调 Producer：
  │       OrderTransactionListener.executeLocalTransaction(msg, arg)
  │         └─→ OrderTransactionService.executeLocalTransaction(userId, request, ...)
  │               └─→ @Transactional → INSERT t_order + t_order_item + t_local_message
  │                   → 成功: return COMMIT（Broker 投递半消息→Consumer 可见）
  │                   → 失败: return ROLLBACK（Broker 删除半消息）
  │
  ├── 3. sendResult.getSendStatus() == SEND_OK?
  │       → 半消息投递成功，本地事务已在 Listener 中执行
  │
  └── 4. context.getOrderId() != null?
          → 本地事务成功 → 发送延时消息（30min 超时关单）
          → null → 释放幂等键 → 抛出异常
```

**`executeLocalTransaction` 的实现**（`OrderTransactionService`）：

```java
@Transactional(rollbackFor = Exception.class)
public Order executeLocalTransaction(Long userId, OrderCreateRequest request,
        String orderNo, BigDecimal totalAmount, BigDecimal discountAmount,
        BigDecimal payAmount, Context context) {
    // 1. INSERT t_order
    // 2. INSERT t_order_item
    // 3. INSERT t_local_message (同事务，保证对账)
    // 全部成功 → @Transactional 提交 → 返回 COMMIT
    // 任一失败 → @Transactional 回滚 → 返回 ROLLBACK
}
```

**为什么需要独立的 `OrderTransactionService`？** Spring 的 `@Transactional` 基于 AOP 代理——同一个类内的自我调用不触发事务。`createOrder` 在 `OrderService` 中，如果直接在 `OrderService` 内加 `@Transactional` 方法——调用不会经过代理。分离到 `OrderTransactionService` 保证了 `@Transactional` 生效。

### Step 5：延时消息兜底（源码行 183）

```java
sendCloseDelayMessage(context.getOrderId(), orderNo, userId);
```

30 分钟后投递 `ORDER_CLOSE_TOPIC`，由 `OrderCloseConsumer` 消费 → 超时关单。与 XXL-Job 每分钟扫描形成双重保障。

---

## 函数执行流程图

```
user POST /order/create
  │
  ├─ [幂等] SETNX order:idempotent:{bizId} (24h TTL)
  │    └─ 失败 → IDEMPOTENT_REJECT(40201)
  │
  ├─ [锁] SETNX order:create:lock:{userId} (UUID, 10s TTL)
  │    └─ 失败 → 释放幂等键 → LOCK_ACQUIRE_FAIL
  │
  ├─ [金额] generateOrderNo + totalAmount - discountAmount
  │
  ├─ [事务消息]
  │    sendMessageInTransaction(ORDER_TRANSACTION_TOPIC, msg, context)
  │    └─ 半消息到 Broker → 回调 executeLocalTransaction()
  │         └─ @Transactional → INSERT order+item+local_message
  │         → COMMIT/ROLLBACK
  │
  ├─ [延时] sendCloseDelayMessage (delayLevel=16, ~30min)
  │
  ├─ [事件] appendEvent(CREATED)  ← Event Sourcing
  ├─ [快照] takeSnapshot(CREATED)
  ├─ [映射] orderNoMapping.insert(orderNo, userId, orderId)
  │
  └─ [解锁] safeUnlock(lockKey, lockValue)  ← Lua 原子释放
```

---

## 面试 Q&A

### Q1：为什么用事务消息而不是先写 DB 再发 MQ？

**答案**：先写 DB 再发 MQ——DB 写入成功但 MQ 发送失败，订单已创建但下游（inventory）不知道。DB 写入和 MQ 发送之间没有原子性保证。

RocketMQ 事务消息将顺序反转：**先发半消息（Broker 保存但不可见）→ 再写 DB → 根据 DB 结果 Commit/Rollback**。Broker 在半消息阶段持有了消息——如果 DB 失败，Broker 丢弃消息（Consumer 看不到）；如果 DB 成功，Broker 投递消息。

**追问**：如果 Producer 在执行本地事务后、返回 Commit 之前崩溃了，半消息会怎样？

→ RocketMQ 会定期回查 `checkLocalTransaction()`。如果 Producer 崩溃超过回查时间（默认 60 秒），Broker 主动问 Producer"这条消息的状态是什么？"Producer 查本地 DB 确认订单是否真的创建了——如果已创建，返回 COMMIT；如果没有，返回 ROLLBACK。这就是事务消息的最终一致性保证。

### Q2：SETNX 幂等和数据库唯一键幂等有什么区别？

**答案**：SETNX 在**入口处**拦截（毫秒级，Redis 操作），数据库唯一键在**事务提交时**拦截（秒级，需要走完 ShardingSphere 路由 + SQL 执行 + 事务提交）。

SETNX 的优势是快——重复请求在 1ms 内被拒绝，不会浪费 MySQL 和 ShardingSphere 的资源。但 SETNX 不是持久化的（Redis 重启丢失），24h TTL 也意味着 24h 后"幂等"失效。对于订单这种业务，24h 后重新下单是合理行为——客户端应该生成新的 `bizIdentifier`。

**追问**：如果两个请求同时到达，SETNX 怎么处理？

→ Redis SETNX 是原子的——只有一个请求能成功（返回 true，继续下单），另一个返回 false（立即拒绝）。Redis 单线程执行模型保证了这个原子性。

### Q3：分布式锁的 UUID value 有什么作用？

**答案**：区分"我的锁"和"别人的锁"。`safeUnlock` 执行 Lua 脚本——先 GET value，与 UUID 比较，匹配才 DEL。如果锁过期被其他请求获取，新请求的 UUID 与旧的不同——Lua 的比较不匹配，不会误删别人的锁。

**追问**：如果业务逻辑执行时间超过了 10 秒 TTL，锁自动过期了——`safeUnlock` 会误删新请求的锁吗？

→ 不会。锁过期后新请求获取了新的 UUID。旧请求的 `safeUnlock` 比较 UUID——不匹配→Lua 返回 0→不删除。但旧请求的业务逻辑可能已经结束——此时新请求的锁已经获取，两个请求可能并发执行。10 秒的 TTL 是保守值——正常的 `createOrder` 在 50ms 内完成，远小于 TTL。

---

## 生产故障实验

### 实验：验证幂等拦截的原子性

```bash
# 同一个 bizIdentifier 并发 3 次
for i in 1 2 3; do
  curl -s -X POST http://localhost:19011/api/order/create \
    -H "X-User-Id: 40001" \
    -d '{"skuItems":[{"skuId":999,"quantity":1}],"couponId":null,"addressId":1,"bizIdentifier":"idempotent-test-001"}' &
done
wait

# 结果：1 次 200（成功），2 次 40201（请勿重复下单）
# 即使并发同时到达，SETNX 原子性保证只有一个成功
```
