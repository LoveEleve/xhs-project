# 06 — MQ 消费链路深度分析

> **前置阅读**：[架构文档 §4.5-4.6](01-inventory-module.md) · §5.2 (3 Consumer + 1 Producer) · [03-分桶预扣减](03-bucket-pre-deduct.md)
> **测试验证**：[测试 2-4](02-inventory-test-record.md) — syncSend 全链路 traceId 验证
> **下游文档**：[07-Canal 缓存一致性](07-canal-cache.md) · [08-库存业务全景](08-business-architecture.md)

## 概览：3 Consumer + 1 Producer 的角色矩阵

```
InventoryService (Producer)
  │
  ├── syncSend INVENTORY_TOPIC:PRE_DEDUCT ──→ InventoryDeductConsumer
  ├── syncSend INVENTORY_TOPIC:CONFIRM    ──→ (fire-and-forget)
  └── syncSend INVENTORY_TOPIC:RELEASE    ──→ (fire-and-forget)

Canal (Binlog)                              OrderService (Transaction Listener)
  │                                           │
  └── INVENTORY_CACHE_TOPIC ──→                └── ORDER_TRANSACTION_TOPIC ──→
       InventoryCacheEvictConsumer                OrderTransactionConsumer

消息方向：Producer → Broker → Consumer
消息量级：库存扣减 ~100/s (正常), Canal 缓存失效 ~10/s, 订单事务 ~100/s
```

| Consumer | Topic | Group | 最大重试 | 幂等键 | 核心逻辑 |
|------|-------|------|:--:|------|------|
| InventoryDeductConsumer | INVENTORY_TOPIC | inventory-deduct-consumer-group | 5 | msgId (24h) | PRE_DEDUCT 3 次退避 / CONFIRM / RELEASE |
| InventoryCacheEvictConsumer | INVENTORY_CACHE_TOPIC | inventory-cache-evict-consumer-group | 5 | es 版本号 | Canal binlog → Lua 版本比较 → SCAN 删 Redis |
| OrderTransactionConsumer | ORDER_TRANSACTION_TOPIC | inventory-order-transaction-consumer-group | 5 | msgId (24h) | 订单事务消息 → 逐 SKU preDeduct |

---

## InventoryDeductConsumer — L2 MySQL 落库的最终防线

源码：`InventoryDeductConsumer.java`（137 行）

```java
@RocketMQMessageListener(
    topic = "INVENTORY_TOPIC",
    consumerGroup = "inventory-deduct-consumer-group",
    selectorExpression = "*",
    maxReconsumeTimes = 5
)
public class InventoryDeductConsumer implements RocketMQListener<MessageExt> {
```

### 三重幂等保证

第 1 层：`MessageIdempotentHelper.isFirstProcess(msgId, 24h)` — 基于 Redis SETNX 的快速去重。如果同一个 msgId 已被消费，直接 return 不处理。24 小时 TTL 覆盖 RocketMQ 的最大重试窗口。

第 2 层：Mapper SQL 乐观锁 WHERE 条件 — `WHERE available_stock >= #{quantity}` 等。即使 idempotentHelper 漏过（Redis 主从切换期间的短暂不一致），MySQL 的 WHERE 条件保证不会扣成负数。

第 3 层：L3 对账 — 如果前两层都失败（极端情况），凌晨 3 点对账以 Redis 为准修复 MySQL。

```java
@Override
public void onMessage(MessageExt msg) {
    MqTraceHelper.restoreTraceId(msg);
    try {
        // 第 1 层幂等
        if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msg.getMsgId(), IDEMPOTENT_TTL_SECONDS)) {
            return;
        }
        // 解析消息 → 第 2 层乐观锁 + 第 3 层对账兜底
        switch (event.getAction()) {
            case "PRE_DEDUCT" -> handlePreDeduct(event);
            case "CONFIRM"    -> handleConfirm(event);
            case "RELEASE"    -> handleRelease(event);
        }
    } catch (Exception e) {
        throw new RuntimeException("库存消费失败", e);  // 触发 MQ 重投
    }
}
```

### PRE_DEDUCT 的 3 次退避重试

源码：`InventoryDeductConsumer.java:86-110`

```java
private void handlePreDeduct(InventoryDeductEvent event) {
    int maxRetry = 3;
    for (int i = 0; i < maxRetry; i++) {
        int affected = inventoryMapper.deductStock(event.getSkuId(), event.getQuantity());
        if (affected > 0) {
            log.info("[库存L2] 预扣减MySQL成功: skuId={}, qty={}, attempt={}",
                event.getSkuId(), event.getQuantity(), i + 1);
            return;
        }
        // 乐观锁竞争失败 → 退避重试
        if (i < maxRetry - 1) {
            Thread.sleep(50L * (i + 1));  // 50ms, 100ms
        }
    }
    // 3 次重试耗尽 → 不抛异常，等 L3 对账
    log.warn("[库存L2] 预扣减MySQL失败(3次重试后仍库存不足): skuId={}（等待L3对账修复）",
        event.getSkuId());
}
```

**为什么只有 PRE_DEDUCT 需要退避重试？**

| 操作 | 乐观锁条件 | 是否会因并发失败？ | 重试策略 |
|------|------|:--:|------|
| PRE_DEDUCT | `WHERE available_stock >= qty` | 是（多用户抢同一 SKU） | 3 次退避 |
| CONFIRM | `WHERE locked_stock >= qty` | 否（locked 只由此 orderId 扣过） | 不重试 |
| RELEASE | `WHERE locked_stock >= qty` | 否（同上） | 不重试 |

PRE_DEDUCT 在高并发下可能遇到乐观锁竞争——两个事件的 Consumer 同时 UPDATE 同一 SKU，其中一个的 `WHERE available >= qty` 在此刻因另一个已经扣了而不满足。退避重试让竞争自然解决（50ms 后另一个 Consumer 的 UPDATE 已提交，当前 Consumer 重新读取 `available`）。

**3 次重试后仍失败 → 不抛异常 → 不触发 MQ 重投**。原因：再重投也是同样的乐观锁竞争问题，增加 Broker 压力但不增加成功率。依赖 L3 对账修复是最优选择。

### CONFIRM 和 RELEASE 的 fire-and-forget

源码注释明确：

```java
// 确认扣减：locked_stock 减少
private void handleConfirm(InventoryDeductEvent event) {
    int affected = inventoryMapper.confirmDeduct(event.getSkuId(), event.getQuantity());
    if (affected > 0) { log.info("确认扣减MySQL成功"); }
    else { log.warn("确认扣减MySQL失败（等待L3对账修复）"); }
    // 不抛异常，不触发重投
}
```

**为什么不重试？** confirm 和 release 的乐观锁条件 `WHERE locked_stock >= qty` 在正常流程下必定满足（locked 就是由对应的 PRE_DEDUCT 扣的）。如果失败，说明 PRE_DEDUCT 的 MQ 消息没有被消费（locked=0）——这是上游问题，重试 confirm 自身没有意义。

---

## OrderTransactionConsumer — 订单事务消息的预扣入口

源码：`OrderTransactionConsumer.java`（118 行）

这个 Consumer 不是 inventory 内部消息——它消费**订单服务**发送的事务消息（RocketMQ TransactionMessage）。

```java
@RocketMQMessageListener(
    topic = "ORDER_TRANSACTION_TOPIC",
    consumerGroup = "inventory-order-transaction-consumer-group",
    selectorExpression = "*",
    maxReconsumeTimes = 5
)
```

### 消息流

```
OrderService (下单)
  │
  ├── OrderTransactionListener.executeLocalTransaction()
  │     └── 订单落库 → COMMIT 事务消息
  │
  └── RocketMQ 投递 ORDER_TRANSACTION_TOPIC
        │
        └── OrderTransactionConsumer
              └── 逐 SKU 调用 inventoryService.preDeduct()
```

### pseudoOrderId 的设计

```java
long pseudoOrderId = orderNo.hashCode() & 0x7FFFFFFF;
```

用 orderNo 的 hashCode 作为 orderId（用于 prededuct.lua 的幂等键）。`& 0x7FFFFFFF` 保证非负（`Math.abs(Integer.MIN_VALUE)` 仍为负数，用位运算更安全）。

**pseudoOrderId 的冲突风险**：`hashCode()` 可能冲突（不同 orderNo 产生相同 hashCode）。如果冲突，第二个订单的预扣会被 Lua 的幂等检查拦截（返回 -1 → 不报错 → 库存不变）。概率极低（32 位 hashCode 冲突 ≈ 1/2^32），但生产环境中会表现为"下单成功但未预扣库存"——由 L3 对账检测。

### msgId 幂等的 rebalance 安全设计

```java
// 使用 msgId 而非 orderNo
if (!idempotentHelper.isFirstProcess(BIZ_TYPE, msg.getMsgId(), IDEMPOTENT_TTL_SECONDS)) {
    return;
}
```

**为什么不能用 orderNo 做幂等键？**

```
旧版设计（orderNo 幂等，循环前设置）：
  1. SET orderNo as consumed
  2. for (skuItems) { preDeduct(sku1) }  ← 成功
  3. for (skuItems) { preDeduct(sku2) }  ← 实例崩溃！
  → 新实例收到重投消息 → orderNo 已标记为 consumed → 跳过 → sku2 未预扣！

新版设计（msgId 幂等，循环后设置）：
  1. for (skuItems) { preDeduct(sku1) }  ← 成功
  2. for (skuItems) { preDeduct(sku2) }  ← 实例崩溃！
  → SET msgId as consumed? NO — 循环还未完成，未设置幂等
  → 新实例收到重投消息 → msgId 未被标记 → 重新处理所有 SKU
  → sku1 的 prededuct.lua 返回 -1（幂等放行）→ 不报错
  → sku2 的 prededuct.lua 正常执行 → 兜底成功
```

**msgId 级别幂等 + Lua SKU 级别幂等的双层设计**：msgId 防止同一条消息被重复消费整条链路，Lua 幂等防止重试时已完成 SKU 被多扣。

---

## InventoryCacheEvictConsumer — Canal 缓存失效

源码：`InventoryCacheEvictConsumer.java`（304 行）

```java
@RocketMQMessageListener(
    topic = "INVENTORY_CACHE_TOPIC",
    consumerGroup = "inventory-cache-evict-consumer-group",
    maxReconsumeTimes = 5
)
```

### es 版本号防乱序

Canal 消息中的 `es`（event sequence）字段严格递增。如果网络延迟导致旧消息在新消息之后到达，直接用旧版本覆盖会导致缓存回退到过期数据。

```java
// 原子版本比较（Lua 脚本内）
String versionCheckScript = """
    local currentVersion = tonumber(redis.call('GET', KEYS[1]) or '0')
    local newVersion = tonumber(ARGV[1])
    if newVersion > currentVersion then
        redis.call('SETEX', KEYS[1], tonumber(ARGV[2]), tostring(newVersion))
        return 1
    else
        return 0
    end
    """;
```

**完整的防乱序流程**：

```
T1  binlog_es=100 → Canal → MQ → Consumer → Lua: 100 > 0(初始) → SET version=100 → DEL cache
T2  binlog_es=101 → Canal → MQ → Consumer → Lua: 101 > 100 → SET version=101 → DEL cache
T3  binlog_es=100 → (网络延迟，旧消息) → Consumer → Lua: 100 > 101? NO → return 0 → skip
```

版本号 Key `inventory:canal:version:{skuId}` TTL=7 天，防止冷 SKU 的版本号永久占用内存。

### SCAN 而非 KEYS

```java
cursor = stringRedisTemplate.scan(
    ScanOptions.scanOptions()
        .match(String.format("inventory:{%d}:bucket:*", skuId))
        .count(64)
        .build());
while (cursor.hasNext()) { stringRedisTemplate.delete(cursor.next()); }
```

**KEYS 命令会阻塞 Redis**（O(N) 遍历整个 keyspace），生产环境禁止使用。SCAN 是增量遍历——每次返回 64 条，中间允许其他命令执行，不阻塞 Redis。

为什么是 64？SCAN 的 count 是建议值，Redis 可能返回更多或更少。64 是经验值——在"一次 round-trip 获取尽量多 Key"和"不长时间占用 CPU"之间取平衡。

### 缓存删除后的 Cache-Aside 回填

```
Canal 删缓存 → 下一个 GET /stock/{skuId} →
  InventoryService.getStock():
    1. Redis GET total → nil（缓存未命中）
    2. MySQL SELECT → available=492, locked=3
    3. 检查是否初始化过 → canal:version:{skuId} 存在 → 触发回填
    4. reloadStockToRedis(492 + 3 = 495, bucketCount) → Pipeline SET
```

回填使用 `SET`（覆盖）而非 `SETNX`——因为 Canal 刚删过缓存，需要保证回填一定成功。多读并发时多个请求都会执行回填，但因为都是从 MySQL 读同一份数据再写 Redis，结果一致。

---

## Producer：syncSend 的正确性保证

源码：`InventoryService.java:531-559`

```java
SendResult sendResult = rocketMQTemplate.syncSend(
    INVENTORY_TOPIC + ":" + action,  // topic:tag 格式
    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
    3000  // 超时 3 秒
);
if (sendResult.getSendStatus() == SendStatus.SEND_OK) {
    return true;
}
```

**syncSend vs asyncSend 的选择表**：

| 调用方 | 方式 | 失败处理 | 适用原因 |
|------|:--:|------|------|
| preDeduct | syncSend + check | rollbackPreDeduct() | 必须保证 Redis→MQ 的原子性 |
| confirm | syncSend, fire-and-forget | 不处理，等 L3 | Redis 已闭环，MySQL 可延迟 |
| release | syncSend, fire-and-forget | 不处理，等 L3 | 同上 |

**为什么 3 秒超时？** inventory 模块的每个 HTTP 请求期望在 500ms 内返回。3 秒的 MQ 超时是保守值——如果 MQ Broker 3 秒内不响应，说明出现了严重问题（网络分区、Broker 故障），再等也没有意义，不如立即回滚 Redis 让用户重试。

---

## 面试 Q&A

### Q1：为什么 PRE_DEDUCT 需要 3 次退避重试而 CONFIRM 不需要？

**答案**：PRE_DEDUCT 的 SQL `WHERE available_stock >= qty AND deleted=0` 在高并发下会被其他 Consumer 的同类 UPDATE 竞争——乐观锁失败是并发冲突，退避重试给竞争者时间完成提交。

CONFIRM 的 SQL `WHERE locked_stock >= qty AND deleted=0` 的 locked_stock 只由对应的 PRE_DEDUCT 增加——它不会被其他 Consumer 竞争，乐观锁失败不应该是并发冲突导致的。

**追问**：如果 PRE_DEDUCT 的 MQ 消息还没被消费，CONFIRM 先到了会怎样？

→ `confirmDeduct` 执行 `locked_stock -= qty WHERE locked_stock >= qty`。如果 PRE_DEDUCT 没消费 → locked=0 → WHERE 不满足 → affected=0 → log.warn → 不抛异常 → 等 L3 对账。正常流程下 PRE_DEDUCT 先投递并消费，但这在 MQ 分区下不是严格有序的。

### Q2：msgId 幂等键和 Lua 幂等键是什么关系？

**答案**：两层幂等解决不同层面的重试：

| 层 | 机制 | 防什么 |
|------|------|------|
| msgId 幂等（Consumer） | `MessageIdempotentHelper.isFirstProcess()` | RocketMQ at-least-once 重复投递 |
| Lua 幂等（Service） | `HGET predeductKey skuId → return -1` | 业务层重复调用（同一订单不能扣两次） |

两个幂等层互补——msgId 防止 Consumer 重复处理同一条 MQ 消息，Lua 幂等防止预扣接口被重复调用同一个 orderId。

**追问**：如果 msgId 幂等失效了（Redis SETNX 返回 true 但实际已处理过），会发生什么？

→ Consumer 再次执行 `deductStock`，但 `WHERE available_stock >= qty AND deleted=0` 的乐观锁会拒绝（available 已在前一次扣减中减少）。这是第 2 层幂等——即使 msgId 幂等漏过，MySQL 乐观锁也能兜底。极端情况下乐观锁也失败了 → L3 对账修复。

### Q3：OrderTransactionConsumer 的 msgId 幂等和循环内异常处理有什么配合？

**答案**：关键设计是"循环前不标记幂等，循环后标记"——只有全部 SKU 的 preDeduct 都成功，才标记 msgId 为 consumed。

如果第 3 个 SKU 预扣失败抛异常：前 2 个 SKU 已通过 Lua 的 HGET 幂等标记（prededuct Hash 中有记录），消息重投后前 2 个 SKU 的 Lua 返回 -1（幂等放行），第 3 个 SKU 从头处理。

**追问**：如果 Consumer 在处理 msgId 时宕机，新 Consumer 实例收到重投消息，Lua 幂等能保证不重复扣吗？

→ 能。Lua 的 HGET 检查 prededuct Hash——只要第一次扣减成功了（HSET 写入），重投时 HGET 命中 → 返回 -1 → 不报错，跳过。这就是 M12 修复的价值——在 prededuct Hash 中持久化幂等状态。

---

## 发散：RocketMQ vs Kafka 在库存扣减场景下的对比

| 维度 | RocketMQ | Kafka |
|------|---------|-------|
| 消息模型 | Topic → Tag → ConsumerGroup | Topic → Partition → ConsumerGroup |
| 消息顺序 | 分区内有序 | 分区内有序 |
| 事务消息 | 原生支持（TransactionListener） | 需外部协调（outbox pattern） |
| 消费位点 | Broker 管理（push/pull） | Consumer 管理（offset） |
| 死信队列 | 原生 DLQ | 需手动处理 |
| 适用 | 业务消息 + 事务消息 | 流处理 + 日志 |

**为什么 my-xhs 选 RocketMQ 而非 Kafka？**

1. **事务消息**：订单创建 → inventory 预扣需要 TransactionListener 保证订单落库和消息发送的原子性。Kafka 没有原生事务消息支持。
2. **Tag 过滤**：`INVENTORY_TOPIC:PRE_DEDUCT / CONFIRM / RELEASE` 三个 Tag 由同一个 Consumer 处理，通过 switch 分发。Kafka 的 partition 不支持这种语义级别过滤。
3. **运维简单**：RocketMQ 的 Namesrv 比 Kafka 的 ZooKeeper 更轻量（my-xhs 不需要 Kafka 的流处理能力）。

---

## 生产故障实验

### 实验 1：MQ 消息丢失模拟——验证 L3 兜底

```bash
# 1. 记录当前 MySQL 状态
mysql -e "SELECT available_stock, locked_stock FROM t_inventory WHERE sku_id=999"

# 2. 预扣减（正常走 L1+L2）
curl -X POST http://localhost:19009/api/inventory/preDeduct \
  -d '{"orderId":777201,"skuId":999,"quantity":1,"userId":10001}'

# 3. 等 Consumer 处理（~15s）
sleep 15

# 4. 手动"误删" MySQL 的 locked 变化（模拟 L2 消费丢失）
mysql -e "UPDATE t_inventory SET locked_stock = locked_stock - 1 WHERE sku_id=999"

# 5. 模拟对账：对比 Redis total vs MySQL available
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)
print(f'Redis total: {r.get(\"inventory:{999}:total\")}')
# 人工判断：Redis total != MySQL available → 以 Redis 为准修复
"
```

**说明**：这个实验不实际执行对账（需要 XXL-Job 定时任务），但演示了"如果 L2 丢了数据，L3 能检测到差异"的原理。凌晨对账任务 `inventoryReconcileJob` 就是这个逻辑的自动化版本。

### 实验 2：验证 MQ 消费的 traceId 链路

```bash
# 1. 预扣减
curl -X POST http://localhost:19009/api/inventory/preDeduct \
  -d '{"orderId":777202,"skuId":999,"quantity":1,"userId":10001}'

# 2. 从 API 响应中提取 timestamp，用于 grep 日志
TS=$(date +%H:%M:%S)

# 3. 查看完整链路日志
grep "777202" /data/workspace/my-xhs/logs/my-xhs-inventory/info.log | head -10
```

**示例输出**：
```
[库存] 预扣减成功: orderId=777202, skuId=999, qty=1, userId=10001
[库存L2] 收到消息: action=PRE_DEDUCT, orderId=777202, skuId=999, qty=1
[库存L2] 预扣减MySQL成功: skuId=999, qty=1, attempt=1
```
