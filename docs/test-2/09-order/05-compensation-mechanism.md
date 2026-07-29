# 05 — 补偿机制全景

> **前置阅读**：[架构文档 §4.2-4.3](01-order-module.md) · [03-事务消息下单](03-transaction-message.md)
> **源码**：`LocalMessageRetryJob` · `OrderCloseJob` · `OrderMappingRepairJob` · `OrderCompensationConsumer` · `OrderCloseConsumer`

## 为什么需要补偿机制？

下单链路涉及多个服务——order 创建订单后需要 inventory 预扣、coupon 核销。取消订单时需要 inventory 释放、coupon 退券。任何一个跨服务调用都可能失败：

```
Feign inventory.release → 网络超时 → 订单取消了但库存没释放
MQ 延时消息未投递 → 超时关单未触发 → 订单永远停留在"待付款"
映射表写入失败 → 用户按 orderNo 查不到订单
```

补偿机制就是**"失败了再试"的自动化**——不是重试 HTTP 请求（瞬时失败可能永久失败），而是将失败信息持久化，由定时任务异步重试。

---

## 三层补偿全景

```
Layer 1: 即时重试
  cancelOrder → CompletableFuture(3s timeout)
    ├── inventory.release() ──→ 失败 → Layer 2
    └── coupon.returnCoupon() ─→ 失败 → Layer 2

Layer 2: MQ 补偿消息
  sendCompensationMessage → ORDER_COMPENSATION_TOPIC
    → OrderCompensationConsumer (maxReconsumeTimes=3)
      → 重试 Feign 调用 → 失败 → Layer 3

Layer 3: 本地消息表 + 定时补发
  INSERT t_local_message (同下单事务)
    → LocalMessageRetryJob (每 30 秒扫描)
      → 指数退避重试(1m,2m,4m,8m,16m) → 死信后 deadLetterScanJob(每小时)
```

---

## Layer 1：CompletableFuture 并行 Feign + 3s 超时控制

```java
// cancelOrder 和 onPaymentFailed 都使用这个并行模式
CompletableFuture<Void> releaseFuture = CompletableFuture.runAsync(
    () -> releaseInventory(orderId, userId));
CompletableFuture<Void> returnFuture = CompletableFuture.runAsync(
    () -> returnCouponIfUsed(order));

CompletableFuture.allOf(releaseFuture, returnFuture)
    .get(3, TimeUnit.SECONDS);
```

**为什么并行？** 释放库存和退还优惠券互不依赖——两个 Feign 调用可以同时发出。串行需要 `T_inventory + T_coupon`，并行只需要 `max(T_inventory, T_coupon)`。

**为什么有 3s 超时？** Feign 调用本身有 connect-timeout(500ms) + read-timeout(3000ms)。如果网络分区导致 Feign 一直挂——不加超时会永久阻塞取消订单的 HTTP 响应。3 秒后抛 TimeoutException → `log.warn("降级依赖补偿机制")` → 取消订单的主流程不受影响 → 失败的 Feign 调用由 Layer 2 补偿。

**为什么降级后不抛异常？** `cancelOrder` 的主流程已经完成了——Event Sourcing 记录了取消事件，`t_order.status` 已更新为 4。从用户的角度——订单已经取消了。库存释放和优惠券退还的失败不应该让用户看到——它们在后台异步补偿。

---

## Layer 2：sendCompensationMessage → MQ Consumer

```java
private void sendCompensationMessage(String operationType, Long orderId,
        Long userId, String failReason) {
    CompensationMessage msg = CompensationMessage.builder()
        .orderId(orderId)
        .userId(userId)
        .operationType(operationType)  // RELEASE_STOCK / RETURN_COUPON
        .failReason(failReason)
        .retryCount(0)
        .maxRetryCount(3)
        .createdAt(LocalDateTime.now())
        .build();

    rocketMQTemplate.syncSend(ORDER_COMPENSATION_TOPIC,
        MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(jsonPayload).build()));
}
```

`OrderCompensationConsumer` 消费 `ORDER_COMPENSATION_TOPIC`（maxReconsumeTimes=3）：

```java
@RocketMQMessageListener(topic = "ORDER_COMPENSATION_TOPIC",
        consumerGroup = "order-compensation-consumer-group",
        maxReconsumeTimes = 3)
public class OrderCompensationConsumer {
    public void onMessage(MessageExt msg) {
        switch (msg.getOperationType()) {
            case "RELEASE_STOCK"  → retry: inventoryFeignClient.releaseStock(...)
            case "RETURN_COUPON"  → retry: couponFeignClient.returnCoupon(...)
        }
    }
}
```

**为什么补偿消息用单独的 Topic？** 补偿消息是"可能失败的重试"——与下单、支付等核心消息分开，避免重试风暴影响正常业务流程。

**maxReconsumeTimes=3**：如果连续失败 3 次（瞬时故障恢复的概率远低于 3 次），消息进入死信队列。`deadLetterScanJob` 每小时扫描死信——人工介入或继续重试。

---

## Layer 3：t_local_message + 定时补发

`t_local_message` 在下单的本地事务中与 `t_order`、`t_order_item` 同时写入——同一事务，原子性保证：

```java
@Transactional(rollbackFor = Exception.class)
public Order executeLocalTransaction(...) {
    orderMapper.insert(order);
    orderItemMapper.batchInsert(items);

    // 本地消息表——同事务写入
    LocalMessage localMessage = buildLocalMessage(orderNo, userId, payload);
    localMessageMapper.insert(localMessage);
}
```

**为什么需要本地消息表？** 事务消息的 `sendMessageInTransaction` 保证"半消息 + 本地事务"的原子性——**但半消息投递成功后，Consumer 消费失败怎么办？** 事务消息保证了消息一定会被投递，但不保证 Consumer 一定消费成功。

本地消息表补充了最后一块拼图——如果 Consumer 消费失败（消息丢失、MQ 积压），定时任务扫描本地消息表发现未处理的消息，重新发送。

### LocalMessageRetryJob：指数退避重试

```java
@XxlJob("localMessageRetryJob")  // 每 30 秒
public void retryPendingMessages() {
    List<LocalMessage> pending = localMessageMapper.selectPendingMessages();
    for (LocalMessage msg : pending) {
        boolean success = retryMessage(msg);
        if (success) {
            localMessageMapper.markSuccess(msg.getId());
        } else {
            localMessageMapper.markFailed(msg.getId(),
                calculateNextRetryTime(msg.getRetryCount()));
        }
    }
}
```

**指数退避策略**：

| 重试次数 | 延迟 | 累计时间 |
|:--:|:--:|:--:|
| 1 | 30 秒 | 30s |
| 2 | 60 秒 (1m) | 1m30s |
| 3 | 120 秒 (2m) | 3m30s |
| 4 | 240 秒 (4m) | 7m30s |
| 5 | 480 秒 (8m) | 15m30s |

第 5 次后标记为"死信"（status=3，`retryCount >= MAX_RETRIES=5`），停止自动重试。每小时 `deadLetterScanJob` 扫描死信——记录到 ES 日志供人工排查。

### 时间窗口保护（60 秒）

```java
public List<LocalMessage> selectPendingMessages() {
    return jdbcTemplate.query(
        "SELECT * FROM t_local_message_? WHERE status = 0 " +
        "AND next_retry_time <= NOW() " +
        "AND created_at < DATE_SUB(NOW(), INTERVAL 60 SECOND) " +  // 时间窗口
        "LIMIT 100",
        ...);
}
```

**为什么有 60 秒窗口？** 下单的事务消息 Consumer 在 ~13s 内消费。如果本地消息表记录刚创建 5 秒就被定时任务扫到——Consumer 可能还没消费。60 秒的窗口给 Consumer 足够的时间完成消费——避免定时任务和 Consumer 的重复处理。

---

## 超时关单双重保障

### 保障 1：RocketMQ 延时消息

```java
private void sendCloseDelayMessage(Long orderId, String orderNo, Long userId) {
    org.springframework.messaging.Message<String> msg = MessageBuilder
        .withPayload("{\"orderId\":" + orderId + ",\"orderNo\":\"" + orderNo + "\"}")
        .build();
    // delayLevel=16 → ~30 分钟
    rocketMQTemplate.syncSend(ORDER_CLOSE_TOPIC, msg,
        3000, 16);
}
```

RocketMQ 的 delayLevel 是内置的延迟级别——level 16 对应 30 分钟。延时消息到达后，`OrderCloseConsumer` 消费——执行 `cancelOrder`。

### 保障 2：XXL-Job 每分钟扫描

```java
@XxlJob("orderCloseJob")  // 每分钟
public void closeTimeoutOrders() {
    Long lastOrderId = 0L;
    while (true) {
        List<Order> timeoutOrders = orderMapper.selectTimeoutOrders(
            lastOrderId, 30, 100);  // 30 分钟前，游标分页，每批 100
        if (timeoutOrders.isEmpty()) break;
        for (Order order : timeoutOrders) {
            cancelOrder(order.getUserId(), order.getId());
            lastOrderId = order.getId();
        }
    }
}
```

**为什么需要双重保障？** 延时消息可能因 Broker 故障丢失、延迟级别不精确（RocketMQ 的 delayLevel 是离散的）。定时任务兜底——即使延时消息未投递，最晚 31 分钟内订单也会被关闭。

**游标分页**：`selectTimeoutOrders(lastOrderId, 30, 100)`——每次从 `lastOrderId` 开始取 100 条 30 分钟前创建的待付款订单。避免了 `LIMIT offset, 100` 的大 offset 性能问题。

---

## 映射表补录

`OrderMappingRepairJob` 每 5 分钟执行——扫描 `t_order` 表，发现没有映射记录的 orderId 就补录到 `t_order_no_mapping`。

```java
@XxlJob("orderMappingRepairJob")
public void repairMappings() {
    // 从 t_order 中提取最近创建的 order_no + user_id + order_id
    // 对每一条，检查映射表是否已存在
    // 不存在 → INSERT IGNORE (幂等安全)
}
```

**为什么需要补录？** `saveOrderNoMapping` 在 `createOrder` 的末尾执行——不在本地事务内。如果 JVM 在此处崩溃，映射表缺失这条记录。5 分钟的补录间隔意味着用户最多 5 分钟内无法通过 `orderNo` 查询订单。

---

## 面试 Q&A

### Q1：为什么补偿消息用同步发送而不是异步？

**答案**：补偿消息的发送在 `catch` 块中——此时 Feign 调用已经失败了，发送补偿消息是"最后的尝试"。如果异步发送——`log.error` 后返回——补偿消息发送失败不会被感知，失败的 Feign 操作永远不会被重试。syncSend 等待 Broker 确认——失败则 `log.error("补偿消息发送失败")`，至少留下了排查线索。

**追问**：如果补偿消息也发送失败了，还有什么兜底？

→ LocalMessageRetryJob 扫描 `t_local_message` 表——发现 60 秒前创建且状态=0 的消息 → 重新发送。如果连本地消息表都没写入（事务回滚了整个下单流程）——那就意味着下单本身失败了，不需要补偿。

### Q2：60 秒的时间窗口会不会太大——如果 Consumer 在 5 秒内就消费完了但本地消息表要等 60 秒？

**答案**：60 秒是为了安全——如果 Consumer 消费失败（如库存服务的乐观锁 `WHERE available < qty`），消费会被重试多次。第一次重试可能 10 秒后——如果时间窗口设 10 秒，定时任务可能和 MQ 重试并发处理同一条消息——导致重复释放库存。

60 秒足够 MQ 完成所有重试（默认 `maxReconsumeTimes=5`，间隔 ~10s→30s→1m→2m→3m）。如果超过 60 秒仍然失败——消息已进入死信队列——此时定时任务接手是安全的。

### Q3：超时关单为什么用延时消息而不是直接用 @Scheduled？

**答案**：延时消息是"精确 30 分钟后触发一次"——不需要每分钟扫描。`@Scheduled` 是定时扫描——每分钟都要查 MySQL。精度不同——延时消息是 30 分钟这个时间点的回调，而 `@Scheduled` 每分钟扫描带来的额外 MySQL 查询开销在订单量大的时候很显著。

---

## 发散：补偿 vs Saga vs TCC

| 模式 | order 的使用 | 回滚方式 | 适用场景 |
|------|------|------|------|
| 补偿 | sendCompensationMessage → Consumer 重试 | 异步重试，最终一致 | Feign 调用失败（可重试的瞬时故障） |
| Saga | — | 逆操作链：退券→退库存→退支付 | 长事务（如货到付款，数天跨度） |
| TCC | inventory 的 Fence 表 | Try→Confirm/Cancel 两阶段 | 强一致性短事务 |

order 没有直接用 TCC——因为 order 本身不持有需要"冻结"的资源（库存和优惠券由各自的 TCC/扣减机制管理）。order 的职责是编排——发起 Feign 调用，失败了就补偿重试。

---

## 生产故障实验

### 实验：验证超时关单

```bash
# 1. 创建一个订单（status=0）
CID=$(curl -s -X POST http://localhost:19011/api/order/create ...)

# 2. 不支付，等 30 分钟
#    或手动触发延时（将 delayLevel 改为 1=1s）

# 3. RocketMQ 延时消息到达 → OrderCloseConsumer 消费
#    或 orderCloseJob 每分钟扫描发现超时订单

# 4. 验证状态变更
mysql -e "SELECT status FROM t_order_0 WHERE id=$CID" → 4(已取消)

# 5. 验证库存已释放
grep "释放库存成功.*$CID" /data/workspace/my-xhs/logs/my-xhs-order/info.log
```
