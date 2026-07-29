# 03 — 分桶预扣减深度分析

> **前置阅读**：[架构文档 §4.1 (分桶预扣完整逻辑)](01-inventory-module.md) · §4.4 (三级扣减时序) · §7.1 (热点检测)
> **测试验证**：[测试 2 (preDeduct)](02-inventory-test-record.md) — traceId `334fa5a81fac4323947c524b844a4308`
> **下游文档**：[04-Lua 脚本三重奏](04-lua-scripts.md) · [06-MQ 消费链路](06-mq-consumer.md)

## 问题：为什么不能直接在 Redis 用 DECR？

最简单的库存扣减：`DECR inventory:{skuId}:total`。单次 O(1)，原子操作，天然防超卖。为什么 inventory 模块搞出了分桶、Lua、三级扣减这一整套复杂设计？

答案：**单 Key 热点瓶颈**。

秒杀场景下，所有用户对同一个 SKU 的扣减请求全部打向同一个 Redis Key。单台 Redis 单 Key 的 QPS 上限约 10 万（取决于网络/CPU）。超过这个量级，Redis 成为瓶颈——用户排队等 DECR 返回。分桶的本质：**将 1 个热点 Key 拆成 N 个冷 Key，每个 Key 独立 DECRBY，总 QPS = N × 单 Key QPS**。

```
单个 Key 方案：
  所有请求 → inventory:{skuId}:total (QPS 瓶颈)

分桶方案：
  请求 hash(userId) → inventory:{skuId}:bucket:0 (QPS/2)
                    → inventory:{skuId}:bucket:1 (QPS/2)
```

---

## 数据模型：三 Key 体系

源码位置：`InventoryService.java:113-121`

```java
private static final String BUCKET_KEY_PREFIX = "inventory:{%d}:bucket:";
private static final String TOTAL_KEY_PREFIX   = "inventory:{%d}:total";
private static final String PREDEDUCT_KEY_PREFIX = "inventory:prededuct:";

private static String totalKey(Long skuId)     { return String.format(TOTAL_KEY_PREFIX, skuId); }
private static String predeductKey(Long orderId) { return PREDEDUCT_KEY_PREFIX + orderId; }
private static String bucketKey(Long skuId, int bucketNo) {
    return String.format(BUCKET_KEY_PREFIX, skuId) + bucketNo;
}
```

| Key | 类型 | 示例 | 用途 |
|-----|:--:|------|------|
| `inventory:{skuId}:total` | String | `inventory:{999}:total` → 497 | 总可用库存，快速判断库存是否充足 |
| `inventory:{skuId}:bucket:{N}` | String | `inventory:{999}:bucket:2` → 163 | 分桶库存，实际扣减的目标 |
| `inventory:prededuct:{orderId}` | Hash | `inventory:prededuct:777001` → {999:3, 999:bucket:2} | 预扣记录，按订单组织 |

**hash tag `{skuId}` 的设计**：`inventory:{999}:total` 和 `inventory:{999}:bucket:N` 通过 `{skuId}` 确保在 Redis Cluster 的同一 slot。这保证了 prededuct.lua 可以在单个节点上原子操作 total + 所有桶 Key。预扣记录 `inventory:prededuct:{orderId}` 没有 hash tag，独立 slot，不参与 Lua 内部的 hash slot 约束。

---

## 全链路代码追踪：一次预扣减的完整执行流程

入口：`POST /api/inventory/preDeduct` → `InventoryController.preDeduct()` → `InventoryService.preDeduct()`

源码位置：`InventoryService.java:204-285`

### Step 0：扩容暂停检查（源码行 210-214）

```java
String pauseKey = "inventory:paused:" + skuId;
if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(pauseKey))) {
    throw new RuntimeException("SKU扩容中，稍后重试");
}
```

为什么需要暂停标记？扩容需要清空旧桶、重算新桶，如果扩容期间允许预扣，会出现"旧桶扣了但新桶没有"的数据不一致。暂停窗口的请求抛 RuntimeException → 调用方（OrderTransactionConsumer）MQ 重试兜底。

### Step 1：读取分桶数（源码行 217-221）

```java
String bucketCountStr = stringRedisTemplate.opsForValue()
    .get(BUCKET_COUNT_KEY_PREFIX + skuId);
if (bucketCountStr == null) {
    throw new BizException(ResultCode.PARAM_INVALID, "库存未初始化");
}
int bucketCount = Integer.parseInt(bucketCountStr);
```

### Step 2：热点检测（源码行 223-233）

```java
if (hotSkuDetector.recordAndCheck(skuId) && bucketCount < hotBucketCount) {
    final int currentBuckets = bucketCount;
    inventoryAsyncExecutor.execute(() -> {
        try { resizeBuckets(skuId, currentBuckets, hotBucketCount); }
        catch (Exception e) { log.warn("[库存] 热点扩容失败", e); }
    });
}
```

`recordAndCheck(skuId)` 内部逻辑（`HotSkuDetector.java`）：

```java
public boolean recordAndCheck(Long skuId) {
    String key = HOT_WINDOW_PREFIX + skuId;
    long nowSec = System.currentTimeMillis() / 1000;

    // 1. ZADD：秒级时间戳 + 线程 ID + 纳秒作为 member
    r.opsForZSet().add(key, nowSec + ":" + Thread.currentThread().getId()
        + ":" + System.nanoTime(), nowSec);

    // 2. 清窗口外数据
    r.opsForZSet().removeRangeByScore(key, 0, nowSec - 10);

    // 3. ZCARD >= 100 → 热点
    Long count = r.opsForZSet().zCard(key);

    // 4. 冷 SKU 自动过期
    r.expire(key, 30, TimeUnit.SECONDS);

    return count != null && count >= HOT_THRESHOLD;
}
```

**ZSet 滑动窗口设计要点**：

1. **member 去重**：`nowSec + ":" + threadId + ":" + nanoTime` — 同秒内同一线程的两次调用因 nanoTime 不同而成为两个 member。避免了纯秒级时间戳的重复覆盖问题。

2. **score = nowSec**：秒级时间戳作为 score，ZREMRANGEBYSCORE 清理 10 秒前的数据。为什么是秒级？精确到毫秒会使 ZSet member 数量激增（同一秒可能数千条），秒级精度在热点阈值 100 的情况下足够。

3. **30 秒 TTL**：冷 SKU 的 ZSet Key 在 30 秒无操作后自动删除，防止内存膨胀。

### Step 3：构建 KEYS 列表（源码行 239-245）

```java
List<String> keys = new ArrayList<>(2 + bucketCount);
keys.add(totalKeyStr);       // KEYS[1] = total
keys.add(predeductKeyStr);   // KEYS[2] = prededuct
for (int i = 0; i < bucketCount; i++) {
    keys.add(bucketKey(skuId, i));  // KEYS[3..N+2] = buckets
}
```

**为什么必须把所有桶 Key 传入 KEYS？**（M14 修复的核心）

Redis Cluster 要求 Lua 脚本操作的所有 Key 必须在同一 hash slot。旧版代码在 Lua 内部用 `redis.call('GET', 'inventory:' .. skuId .. ':bucket:' .. i)` 动态拼接 Key，但 Redis 无法验证动态拼接的 Key 是否与已传入的 Key 同 slot → 直接拒绝执行（CROSSSLOT 错误）。

M14 修复方案：调用方在 Java 层预先构建所有 Key 传入 KEYS。Redis 可以验证所有 KEYS 是否同 slot，通过后 Lua 内部用 `KEYS[i]` 访问——不涉及动态拼接。

### Step 4：执行 prededuct.lua（源码行 247-256）

```java
Long result = stringRedisTemplate.execute(
    preDeductScript,
    keys,
    String.valueOf(skuId),        // ARGV[1]
    String.valueOf(orderId),      // ARGV[2]
    String.valueOf(quantity),     // ARGV[3]
    String.valueOf(bucketCount),  // ARGV[4]
    String.valueOf(userId),       // ARGV[5]
    String.valueOf(preDeductExpireSeconds)  // ARGV[6]
);
```

**prededuct.lua 的完整逻辑**（`lua/prededuct.lua`）：

```lua
-- 0. 幂等检查
local existingQty = redis.call('HGET', predeductKey, skuId)
if existingQty then return -1 end  -- 重复预扣

-- 1. 总库存检查
local totalStock = redis.call('GET', totalKey)
if not totalStock then return -2 end  -- 未初始化
if tonumber(totalStock) < quantity then return 0 end  -- 库存不足

-- 2. 计算路由桶（userId % bucketCount）
local routeBucket = userId % bucketCount
local routeKeyIdx = 3 + routeBucket

-- 3. 尝试路由桶扣减
local routeStock = tonumber(redis.call('GET', KEYS[routeKeyIdx]) or '0')
if routeStock >= quantity then
    redis.call('DECRBY', KEYS[routeKeyIdx], quantity)
    redis.call('DECRBY', totalKey, quantity)
    redis.call('HSET', predeductKey, skuId, quantity)
    redis.call('HSET', predeductKey, skuId .. ':bucket', routeBucket)
    redis.call('EXPIRE', predeductKey, expireSeconds)
    return 1  -- 成功
end

-- 4. 路由桶不足，遍历其他桶
for offset = 1, bucketCount - 1 do
    local i = (routeBucket + offset) % bucketCount
    local otherKeyIdx = 3 + i
    local otherStock = tonumber(redis.call('GET', KEYS[otherKeyIdx]) or '0')
    if otherStock >= quantity then
        redis.call('DECRBY', KEYS[otherKeyIdx], quantity)
        redis.call('DECRBY', totalKey, quantity)
        redis.call('HSET', predeductKey, skuId, quantity)
        redis.call('HSET', predeductKey, skuId .. ':bucket', i)
        redis.call('EXPIRE', predeductKey, expireSeconds)
        return 1  -- 成功
    end
end

return 0  -- 全部桶不足
```

**Lua 内部执行保证原子性**：整个脚本在 Redis 单线程中执行，`DECRBY` + `HSET` + `EXPIRE` 是原子的——要么全部成功（扣 bucket + 扣 total + 写预扣记录），要么全部失败（库存不足返回 0）。不存在"bucket 扣了但 total 没扣"的中间状态。

### Step 5：处理返回值 + MQ 同步发送（源码行 262-284）

```java
switch (result.intValue()) {
    case 1 -> {
        // L1 成功 → 同步发 MQ → 失败回滚
        if (!sendInventoryEvent(orderId, skuId, quantity, "PRE_DEDUCT")) {
            rollbackPreDeduct(orderId, skuId, quantity);
            throw new BizException(ResultCode.INTERNAL_ERROR, "库存扣减失败，请重试");
        }
    }
    case 0  -> throw new BizException(ResultCode.STOCK_NOT_ENOUGH);
    case -1 -> { /* 幂等放行，不报错 */ }
    case -2 -> throw new BizException(PARAM_INVALID, "库存未初始化");
}
```

**`sendInventoryEvent` 的同步发送**（`InventoryService.java:531-559`）：

```java
SendResult sendResult = rocketMQTemplate.syncSend(
    INVENTORY_TOPIC + ":" + action,  // "INVENTORY_TOPIC:PRE_DEDUCT"
    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
    3000  // 超时 3 秒
);
return sendResult.getSendStatus() == SendStatus.SEND_OK;
```

**为什么必须同步？** 如果异步发送失败，Redis 已扣减但 MySQL 永远不更新 → 库存凭空消失。同步发送可以在失败时立即回滚 Redis。

**`rollbackPreDeduct` 的回滚**（`InventoryService.java:565-586`）：

```java
private void rollbackPreDeduct(Long orderId, Long skuId, int quantity) {
    stringRedisTemplate.execute(
        releaseScript,
        List.of(totalKeyStr, predeductKeyStr, bucketKeyStr),
        String.valueOf(skuId)
    );
}
```

使用 `release.lua` 原子回退——INCRBY bucket、INCRBY total、HDEL prededuct。即使回滚失败，30 分钟的 TTL + PreDeductTimeoutJob 提供最终一致性兜底。

---

## 路由策略：为什么是 userId 取模？

### 不做随机路由

如果随机选择桶号：

```
请求 1: hash(random) → bucket:0 (扣 2)
请求 2: hash(random) → bucket:1 (扣 2)
请求 3: hash(random) → bucket:0 (再扣 2)
...
```

同一用户的多件商品（如一次买 10 件不同 SKU）可能分散到不同桶。桶间随机分布看起来均匀，但实际上会产生**桶间热点迁移**——热点用户的大量请求在桶间跳跃，即使分桶了也无法有效降低单桶压力。

### userId 取模的亲和性

```
订单 1 (userId=10001, skuId=999, qty=3):  10001 % 3 = 2 → bucket:2 ✗
订单 2 (userId=10001, skuId=998, qty=5):  10001 % 3 = 2 → bucket:2 ✗
```

同一用户的不同 SKU 预扣请求全部落到同一个桶。好处：**同一个用户的请求串行化在同一个桶内**，避免了跨桶的锁竞争。

### 桶间均衡的妥协

亲和性有一个问题：如果用户 A 的专属桶被扣空了，但用户 A 还要继续下单怎么办？

```lua
-- prededuct.lua 的桶间遍历
for offset = 1, bucketCount - 1 do
    local i = (routeBucket + offset) % bucketCount
    -- 逐个检查其他桶
end
```

路由桶不足时，Lua 循环遍历其他桶。从 `routeBucket+1` 开始环形搜索，找到第一个库存充足的桶就扣减。**这不是公平算法**（可能让某些桶长期被"借用"），但保证了**不会因为有桶为空而拒绝扣减**。对账任务（`reconcileBuckets`）会定期检查桶间分布并修正。

---

## 分桶数量：2（默认）vs 8（热点）

源码配置（`application.yml`）：

```yaml
inventory:
  bucket:
    default-count: 2    # 普通 SKU
    hot-count: 8         # 热点 SKU
```

### 为什么默认是 2 桶？

- 每个桶需要一个 Redis Key 存储库存数
- 每个预扣 Lua 脚本需要把所有桶 Key 传入 KEYS 参数
- 桶越多 → KEYS 越长 → Lua 脚本执行时的网络传输 + 内存占用越大
- 绝大多数 SKU 不是热点，2 个桶足够应对常规并发

### 为什么热桶是 8 桶？

```
默认 2 桶：单 Key QPS × 2
热桶 8 桶：单 Key QPS × 8 = 4 倍并发能力
```

8 桶时 Lua 脚本最多操作 10 个 Key（total + prededuct + 8 bucket）——仍在 Redis 单次 Lua 操作的合理范围内。超过 8 桶的收益递减：网络传输开销开始超过并发收益。

### 扩容过程（`InventoryService.resizeBuckets`，行 597-654）

```
1. SETNX inventory:resize:{skuId} = 1 (分布式锁, 10s TTL)
2. SET inventory:paused:{skuId} = 1 (暂停预扣, 30s TTL)
3. GET 各桶库存 → 求和 totalStock
4. Pipeline 写入新桶（perBucket + remainder）→ 删除多余旧桶
5. 更新 bucketCount + total
6. DELETE paused → 恢复预扣
7. DELETE resize → 释放锁
```

**暂停窗口内的请求处理**：preDeduct Step 0 检测到 `paused` Key → 抛 RuntimeException → 调用方（OrderTransactionConsumer）的 MQ 重试机制接管 → 扩容完成后重投消息。

---

## 为什么需要三级扣减保证？

### L1 不够：Redis 是内存存储，可能丢数据

- Redis 重启、主从切换、OOM → 库存数据丢失
- 仅靠 Redis 无法提供持久化保证

### L2 不够：MQ 可能延迟或失败

- syncSend 超时 3s → 回滚 Redis → 用户看到"扣减失败"
- Consumer 乐观锁竞争失败 → 3 次退避重试 → 失败等 L3

### L3 的必要性

L1（Redis）和 L2（MySQL）之间的差距就是最终一致性的窗口。L3 对账在固定时间（凌晨 3 点）以 Redis 为准修复 MySQL，闭合这个窗口。

```
L1: Redis DECRBY → 用户 < 500ms 得到反馈
L2: MQ → MySQL → 持久化 ~13s
L3: 凌晨对账 → Redis↔MySQL → 最终一致
```

---

## 面试 Q&A

### Q1：分桶预扣减如何保证不会超卖？

**答案**：Redis Lua 脚本的原子性。prededuct.lua 在 Redis 单线程中执行，HGET 幂等检查 → GET 总库存 → GET 路由桶 → DECRBY bucket → DECRBY total → HSET prededuct 六步操作是一个事务——要么全部成功，要么全部失败（库存不足返回 0）。

**追问到第二层**：Sentinel 主从架构下，主节点执行 Lua 后宕机，从节点提升为新主——但 Lua 操作的 Key 可能还没同步过来，导致库存"凭空多了"。inventory 怎么处理？

→ Sentinel 部署了 `min-replicas-to-write 1`。极端情况下 Redis↔MySQL 短暂不一致由 L3 对账修复——但 L1/L2 路径本身没有像 TCC 那样的 Fence 表做事务日志，主从切换期间的"已扣 Redis 但 MQ 未发"是理论上存在的窗口期。

### Q2：为什么 MQ 必须 syncSend 而非异步？

**答案**：异步发 MQ 失败时 Redis 已扣但 MySQL 永不更新 → 库存凭空消失。syncSend 等待 Broker 确认（超时 3s）→ 失败立即 `rollbackPreDeduct()` 执行 release.lua 原子回退。

**追问到第二层**：如果 `rollbackPreDeduct` 也失败了呢？

→ 三层兜底：release.lua 失败 → 30 分钟 TTL → PreDeductTimeoutJob 主动 SCAN + 原子回退 → L3 凌晨对账。测试中已验证 attempt=1 正常路径。

### Q3：热点检测 ZSet 为什么用秒级时间戳？

**答案**：毫秒级每个 member 独立（同一秒 ~1000 条），秒级用 `threadId + nanoTime` 后缀防重复覆盖 + ZCARD 精确计数。阈值 100 次/10 秒，秒级精度足够。

**追问到第二层**：如果 Cache Redis（16380, allkeys-lru）淘汰了 ZSet Key 会怎样？

→ inventory 的热点检测数据存在 Business Redis（16381, noeviction），不会被淘汰。如果误存 Cache Redis → 热点检测失效 → 永不扩容 → 2 桶扛全部流量 → QPS 瓶颈。

### Q4：扩容暂停期间用户的请求怎么处理？

**答案**：Step 0 检测 `paused` → 抛 RuntimeException → MQ Consumer 重试兜底。暂停窗口 < 100ms，RocketMQ 退避重试（1s/5s/10s/30s/1m × 5 次）完全覆盖。

**追问到第二层**：100 个 SKU 同时扩容，异步线程池（core=2, max=4, queue=50）如何处理？

→ CallerRunsPolicy：线程池满时调用线程自己执行 resize。那个用户的 preDeduct 被阻塞 ~100ms，但扩容不会被丢弃。比 AbortPolicy 更安全。

### Q5：预扣成功但 30 分钟未支付→库存恢复的竞态？

**答案**：PreDeductTimeoutJob 用 `release.lua` 而非分步操作——因为用户可能在同一时刻手动调用 releaseStock。

```
分步操作（危险）：
  定时:   HGETALL → 读到 qty=3
  用户:   POST /release → INCRBY +3, HDEL ✓
  定时:   INCRBY +3 → 库存凭空多出 3 件！

Lua 原子（安全）：
  定时:   HGET+HDEL 原子 → 返回 3 → INCRBY ✓
  用户:   HGET → nil（已被删除）→ 不操作 ✓
```

---

## 生产故障实验

### 实验 1：触发库存不足

```bash
# 初始化小库存
curl -X POST http://localhost:19009/api/inventory/init \
  -H "Content-Type: application/json" \
  -d '{"skuId":888,"totalStock":3,"bucketCount":2}'

# 第一次扣减：成功
curl -X POST http://localhost:19009/api/inventory/preDeduct \
  -H "Content-Type: application/json" \
  -d '{"orderId":777101,"skuId":888,"quantity":2,"userId":10001}'
# → {"code":200}  total → 1

# 第二次扣减：库存不足
curl -X POST http://localhost:19009/api/inventory/preDeduct \
  -H "Content-Type: application/json" \
  -d '{"orderId":777102,"skuId":888,"quantity":2,"userId":10001}'
# → {"code":10001,"message":"库存不足"}
```

**说明**：Lua 第 52 行 `if tonumber(totalStock) < quantity then return 0` 快速路径——不遍历桶，直接返回 0。

### 实验 2：触发幂等拦截

```bash
# 重复预扣同一 orderId
curl -X POST http://localhost:19009/api/inventory/preDeduct \
  -H "Content-Type: application/json" \
  -d '{"orderId":777101,"skuId":888,"quantity":1,"userId":10001}'
# → {"code":200}  # 幂等放行，库存不变
```

**说明**：Lua 返回 -1 时不抛异常，HTTP 200。库存和预扣记录都不变，防止客户端重试导致多扣。

### 实验 3：L1→L2 延迟观测

```bash
# 预扣减
curl -X POST http://localhost:19009/api/inventory/preDeduct \
  -H "Content-Type: application/json" \
  -d '{"orderId":777103,"skuId":888,"quantity":1,"userId":10001}'

# 立即查 MySQL（L2 未消费）
mysql -e "SELECT available_stock,locked_stock FROM t_inventory WHERE sku_id=888"
# → available=1, locked=0  # Redis 已扣，MySQL 未更新

# 等 15s 再查（L2 已消费）
mysql ... → available=0, locked=1  # Redis↔MySQL 一致
```

**说明**：~13s 延迟是系统正常行为——用户只看 Redis（API 秒级返回），MySQL 的延迟不影响体验。


---

## 工程分析

### 1. 分桶是否真的消除了热点？

分桶将热点从"一个 Key"转移到"N 个 Key"。在 Redis Cluster 中，这 N 个 Key 通过 hash tag `{skuId}` 落到同一个 slot——也就是说，它们仍然在**同一台 Redis 节点**上。分桶消除的是**单 Key 的 CPU 竞争**（Lua 执行期间的锁），但没有消除**单节点的 CPU 压力**。

真正的水平扩展需要将不同 SKU 的桶分散到不同 Redis 节点——但这超出了分桶的范围，属于 Redis Cluster 的分片策略。

### 2. userId 取模的一个隐患

同一个用户的所有请求都路由到同一个桶。如果一个用户大量下单（如批发商），该用户的桶会被迅速扣空，然后 Lua 遍历到其他桶——结果是该用户占用多个桶的库存。其他用户的专属桶也可能被这个用户"借走"。

**缓解措施**：`@Max(999)` 单次扣减上限 + 对账任务的桶间分布检查。

### 3. MQ 同步发送的性能代价

`syncSend` 等待 MQ Broker 确认（超时 3s）。如果 Broker 响应慢，preDeduct 的端到端延迟 = Redis Lua ~1ms + MQ syncSend ~20ms（正常）或 ~3000ms（超时）。

正常情况下来自测试验证：preDeduct rt=201ms（含 Redis Lua + MQ syncSend）。201ms 远低于 3000ms 超时，说明 Broker 响应正常。

### 4. 分桶余数分配策略

```java
int perBucket = totalStock / bucketCount;
int remainder = totalStock % bucketCount;
for (int i = 0; i < bucketCount; i++) {
    int bucketStock = perBucket + (i == 0 ? remainder : 0);
}
```

余数全给桶 0。这意味着桶 0 的库存 > 其他桶。如果 userId % N = 0 的用户恰好是高频用户，桶 0 会比预期更快耗尽。但对账任务会定期检查并重新均衡，长期来看桶间分布趋向均匀。
