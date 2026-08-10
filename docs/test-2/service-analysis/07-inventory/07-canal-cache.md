# 07 — Canal 缓存一致性深度分析

> **前置阅读**：[架构文档 §5.3 (Canal Binlog)](01-inventory-module.md) · §2.6 (Cache-Aside 回填) · [06-MQ 消费链路](06-mq-consumer.md)
> **测试验证**：[测试 5 (stock 查询)](02-inventory-test-record.md) — Redis 缓存命中 + Cache-Aside 回填路径
> **下游文档**：[08-库存业务全景](08-business-architecture.md)

## 为什么 inventory 用 Canal 而非延迟双删？

product 模块用延迟双删（先删 Redis → 等 500ms → 再删一次）保证缓存一致性。inventory 用 Canal binlog 监听。为什么不同？

### 延迟双删的 500ms 窗口问题

```
延迟双删流程：
  T0: UPDATE MySQL → available 500→497
  T0: DELETE Redis total → nil
  T0+200ms: 并发读请求 → Redis miss → 从 MySQL 读 500（旧值！）→ SET Redis total=500
  T0+500ms: 第二次 DELETE → 删掉刚回填的 500（但并发读可能已经读了 500 返回给用户了）

问题：T0+200ms 到 T0+500ms 的窗口中，并发读可能读到旧值并回填，第二次删除来不及拦截。
如果读操作足够快（<300ms），500ms 窗口内可能命中旧值。
```

### Canal 的零窗口方案

```
Canal 流程：
  T0: UPDATE MySQL → available 500→497
  T0: Canal 监听 binlog → 解析 event → 发送 INVENTORY_CACHE_TOPIC
  T0+~50ms: Consumer 收到消息 → Lua 版本检查 → SCAN DEL Redis

关键：Consumer 删除缓存在 MySQL 写入之后、下一个读请求之前完成。
不存在"读写竞态窗口"——因为删除操作不依赖时间延迟，而是依赖 Binlog 的事件驱动。
```

**为什么 product 能用延迟双删？** product 是读多写少的场景（商品信息变更频率低），500ms 的短暂不一致窗口可以接受——用户看到旧价格 500ms 后刷新就对了。inventory 是写多且强一致的场景（库存不准 = 超卖 / 少卖），必须 Canal 的零窗口方案。

---

## Canal 完整链路：从 Binlog 到 Redis 删除

### Step 1：Canal Instance 配置

文件：`config/canal/conf/inventory_instance/instance.properties`

```
canal.instance.master.address = 127.0.0.1:13309
canal.instance.filter.regex = my_xhs_inventory\\.t_inventory
canal.mq.topic = INVENTORY_CACHE_TOPIC
```

Canal 伪装成 MySQL Slave，连接本地 MySQL（127.0.0.1:13309），订阅 `my_xhs_inventory.t_inventory` 的 binlog。只监听这一个表，减少网络传输和解析开销。

### Step 2：Canal 消息格式

```json
{
  "database": "my_xhs_inventory",
  "table": "t_inventory",
  "type": "UPDATE",
  "data": [{"id": "...", "sku_id": "999", "available_stock": "497", "locked_stock": "3"}],
  "old": [{"available_stock": "500"}],
  "es": 1715510539000,
  "ts": 1715510539000
}
```

**关键字段**：

- `es`（event sequence）：严格递增的序列号，用于防乱序
- `data`：变更后的行数据，多行变更时是数组
- `old`：变更前的行数据（仅 UPDATE 有）
- `type`：INSERT / UPDATE / DELETE

### Step 3：Consumer 处理

源码：`InventoryCacheEvictConsumer.java`

```java
@Override
public void onMessage(MessageExt msg) {
    MqTraceHelper.restoreTraceId(msg);
    try {
        JSONObject canalMsg = JSON.parseObject(new String(msg.getBody(), StandardCharsets.UTF_8));
        if (!canalMsg.containsKey("database") || !canalMsg.containsKey("data")) {
            return;  // 消息格式异常，跳过
        }
        handleCanalMessage(canalMsg, msg.getMsgId(), msg.getReconsumeTimes());
    } catch (Exception e) {
        throw new RuntimeException("库存缓存失效处理失败（可重试）", e);
    }
}
```

`handleCanalMessage` 内部按事件类型分发：

```java
switch (type) {
    case "INSERT", "UPDATE" -> evictCache(skuId, canalVersion, type);
    case "DELETE" -> evictCacheCompletely(skuId, canalVersion);
}
```

---

## es 版本号防乱序：Lua 原子比较

### 为什么 Canal 消息会乱序？

Canal → RocketMQ → Consumer 这条链路中，消息可能因为网络延迟、MQ 分区重分配、Consumer rebalance 等原因乱序到达：

```
场景：连续两次 UPDATE
  T1: available 500→497 (es=100)
  T2: available 497→492 (es=101)
  
Consumer 收到顺序：T2(es=101) 先到 → DEL cache → T1(es=100) 后到 → 如果直接处理 → DEL cache（无意义，已经删了）

但如果 T1 和 T2 之间有更复杂的操作（如 init → preDeduct → confirm），乱序处理可能导致缓存删除操作基于过期的 binlog 状态。
```

### 原子版本比较

Consumer 在删除缓存之前执行一次 Lua 原子版本检查：

```java
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

Long versionResult = stringRedisTemplate.execute(
    new DefaultRedisScript<>(versionCheckScript, Long.class),
    List.of("inventory:canal:version:" + skuId),
    String.valueOf(canalVersion),
    String.valueOf(CANAL_VERSION_TTL_SECONDS)  // 7 天
);
```

**为什么用 Lua 而非 GET + 判断 + SET？** 三步操作（GET → 比较 → SET）之间可能穿插其他操作——如果两个 Consumer 线程同时处理同一个 SKU 的不同版本：

```
分步操作（非原子）：
  线程 A(es=101): GET → "100" → 101 > 100? YES → SET 101
  线程 B(es=102): GET → 可能读到 "100"（A 还没 SET）→ 102 > 100? YES → SET 102
  → 两个都 SET 成功，但 A 的 SET 可能因为时序问题被 B 覆盖（取决于操作系统调度）

Lua 原子操作：
  线程 A(es=101): GET + 比较 + SET → 原子执行 → version=101
  线程 B(es=102): GET + 比较 + SET → 原子执行 → 102 > 101 → SET → version=102
  → 串行化执行，版本号单调递增 ✓
```

### TTL = 7 天：防止版本号 Key 永久占用内存

```
inventory:canal:version:999 → SETEX → 7 天后自动删除

冷 SKU：7 天无 binlog 变更 → version Key 过期 → 下次变更时从 0 开始（100 > 0 → 通过）
热 SKU：每天有变更 → EXPIRE 被刷新 → 永远不过期
```

---

## SCAN 删除策略：为什么不是 KEYS 或 UNLINK？

### KEYS 的阻塞问题

```
KEYS inventory:{999}:bucket:*
  → Redis 单线程遍历整个 keyspace，O(N)
  → 执行期间 Redis 不接受任何其他命令
  → 如果 keyspace 有 100 万个 Key，KEYS 可能阻塞 500ms+
  → 阻塞期间所有预扣减、库存查询全部超时！
```

### SCAN 的增量遍历

```java
cursor = stringRedisTemplate.scan(
    ScanOptions.scanOptions()
        .match(String.format("inventory:{%d}:bucket:*", skuId))
        .count(64)
        .build());
while (cursor.hasNext()) {
    String key = cursor.next();
    stringRedisTemplate.delete(key);
}
```

SCAN 是游标式增量遍历——每次返回 `count` 条建议值（最多返回 `count` 条，可能更少），中间允许其他命令执行。不阻塞 Redis。

**count=64 的选择**：SCAN 的 count 是建议值，Redis 可能返回的条数在 1-64 之间。64 在 "一次 round-trip 返回足够多 Key" 和 "不长时间占用 CPU" 之间取平衡。如果 count 设为 1000，单次 SCAN 可能返回全部匹配的 Key——但这也意味着 Redis 要花更多 CPU 时间在这一轮 SCAN 上，暂时阻塞其他命令。

### 为什么不直接 SET 新值而是删缓存？

Canal Consumer 不直接更新 Redis 缓存值（如 `SET inventory:{999}:total 497`），而是删除缓存让下一个读请求回填。

**原因**：

1. **Canal 消息可能乱序**：如果直接 SET 值，旧版本消息（es=100，available=500）后到达时会覆盖新版本（es=101，available=497）。删除操作是幂等的——DEL 100 次和 DEL 1 次结果相同。

2. **读请求回填的是最新值**：下一个 `GET /stock/{skuId}` 从 MySQL 读取 → 得到最新值（MySQL 是 Binlog 的来源，天然是权威数据）→ 回填 Redis。

3. **即使回填后有新变更**：回填完成 → MySQL 再次变更 → Canal 再次发送消息 → Consumer 再次删除缓存 → 下一个读请求再次回填。这是一个自愈循环。

---

## Cache-Aside 回填：三条件决策树

源码：`InventoryService.getStock()`（413-468 行）

```
GET /api/inventory/stock/{skuId}
  │
  ├── REDIS GET inventory:{999}:total → 命中
  │     └── return {availableStock=497, initialized=true}
  │
  └── REDIS GET → nil（未命中）
        │
        ├── MySQL SELECT → available=492, locked=3
        │
        ├── 回填判断（三条件）：
        │     ├── bucket:count:{skuId} 存在？
        │     │     → 是：Canal 部分删除 → reloadStockToRedis(495, bucketCount)
        │     │     → 否：检查下一条件
        │     ├── canal:version:{skuId} 存在？
        │     │     → 是：Canal 全量删除 → reloadStockToRedis(495, defaultBucketCount)
        │     │     → 否：从未初始化 → 只返回 MySQL 数据，不回填
        │
        └── return {availableStock=492, initialized=判断结果}
```

**三个回填条件的含义**：

| 条件 | Redis 状态 | 含义 | 回填方式 |
|------|------|------|------|
| `bucket:count` 存在 | total 被删了，但辅助 Key 还在 | Canal 只删了 total/bucket，未删辅助 Key | 用原 bucketCount 回填 |
| `canal:version` 存在 | 全部被删，但版本号还在 | Canal 删了所有 Key | 用默认 bucketCount 回填 |
| 都不存在 | 无任何残留 | 从未初始化 | 不回填（避免误回填未初始化 SKU） |

**为什么需要区分"从未初始化"和"Canal 删除"？**

如果 SKU 从未初始化过（Redis 中没有任何 Key），但用户查询了库存——从 MySQL 读到数据后不应该回填 Redis。因为回填意味着用 `defaultBucketCount` 分桶——但如果生产环境该 SKU 配置了自定义桶数，回填会覆盖正确的桶配置。

回填使用 `SET`（覆盖写）而非 `SETNX`——因为 Canal 已经删过缓存，需要保证回填一定成功。多个并发读请求都会执行回填，但因为都是从 MySQL 读同一份数据再写 Redis，结果一致。

---

## 发散：Canal vs Debezium vs Maxwell

| 维度 | Canal（my-xhs 使用） | Debezium | Maxwell |
|------|:--:|------|------|
| 开发者 | 阿里巴巴 | Red Hat | Zendesk |
| 协议 | 伪装 MySQL Slave | Kafka Connect | 伪装 MySQL Slave |
| 输出 | RocketMQ / Kafka / TCP | Kafka（原生） | Kafka / stdout |
| 运维复杂度 | 低（单实例 + 配置文件） | 高（Kafka + Connect 集群） | 低（单实例） |
| 适用 | 阿里系中间件（RocketMQ） | 全系 Kafka 生态 | 轻量级 CDC |
| my-xhs 选型原因 | 与 RocketMQ 原生集成 | — | — |

**为什么不用 Debezium？** my-xhs 不需要 Kafka 生态（流处理、KsqlDB 等）。Canal 直接对接 RocketMQ，运维简单——修改 instance.properties 一行配置就能增加监听表。Debezium 需要部署 Connect 集群，对 my-xhs 的规模来说是过度工程。

---

## 面试 Q&A

### Q1：Canal 消息乱序怎么解决？如果版本号 Key 也丢了怎么办？

**答案**：Lua 原子版本比较（`GET currentVersion → compare → SET newVersion`）。只有 `newVersion > currentVersion` 时才执行缓存删除。

**追问到第二层**：如果版本号 Key 也过期了（7 天 TTL），乱序消息怎么处理？

→ TTL=7 天是保守值——7 天内至少有一次 binlog 变更就会刷新 SETEX。如果 SKU 真的 7 天无任何变更，它就不是热数据——缓存的旧值被乱序消息删除一次也不影响业务（因为不是热点）。如果真的需要更多安全保证，可以加一个 `SCAN inventory:{skuId}:*` 来确认 Key 已被删完。

### Q2：为什么 Canal Consumer 删缓存而不是更新缓存？

**答案**：两个原因。一是 Canal 消息可能乱序（旧值覆盖新值），删除是幂等的。二是 Cache-Aside 回填从 MySQL 读取的是最新值（MySQL 是 Binlog 的源头）。

**追问到第二层**：更新缓存不是更快吗？省一次 MySQL SELECT？

→ 快但不安全。如果 UPDATE 的是 `available_stock`，直接 SET 需要知道改了什么——Canal 消息里确实有 `data.available_stock`，但"更新一个字段"和"整个缓存的有效性"是两回事。Redis 的库存缓存（`total + all buckets`）是一个逻辑整体——只更新 total 不重算 bucket 会导致分桶和不等于 total。删除全部缓存再整体回填保证了这个逻辑整体的原子性。

### Q3：Canal 宕机后怎么恢复？缓存会一直不更新吗？

**答案**：Canal 记录了消费到的 binlog position（`mysql-bin.xxxxx:offset`）。重启后从上次记录的位置继续消费，不丢消息。

**追问到第二层**：如果 Canal 宕机期间 MySQL 有 1000 次更新，重启后 Canal 需要追上 1000 条 binlog——追上的过程中缓存一直是旧值？

→ 是的。Canal 的追数据能力通常在 1000-5000 条/秒。1000 条 binlog 可能在 1-2 秒内追上。追上的过程中，`GET /stock` 从 MySQL 回填的是当前最新值（读的是 MySQL 当前快照，不依赖 Canal）。缓存短暂不一致，但用户查询得到的是正确值。

---

## 生产故障实验

### 实验 1：验证 Canal → Redis 删除链路（已验证 ✅ 2026-07-28）

```bash
# 1. 记录当前 Redis 状态
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)
print(f'Redis total before: {r.get(\"inventory:{999}:total\")}')
print(f'version before: {r.get(\"inventory:canal:version:999\")}')
"

# 2. 直接修改 MySQL（触发 binlog）
mysql -h21.130.247.89 -P13309 -uroot -p'...' my_xhs_inventory \
  -e "UPDATE t_inventory SET available_stock = 889 WHERE sku_id = 999"

# 3. 等待 Canal → MQ → Consumer 链路（~15s）
sleep 15

# 4. 验证 Redis 缓存被 Canal Consumer 删除
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)
print(f'Redis total: {r.get(\"inventory:{999}:total\")}')
# → None（被 Canal 删除！deletedKeys=4）
print(f'version: {r.get(\"inventory:canal:version:999\")}')
# → 1785222446000（Canal es 版本号）
"

# 5. 触发 Cache-Aside 回填
curl -s http://localhost:19009/api/inventory/stock/999
# → {"availableStock":889,...}  # availableStock 来自 MySQL 当前值

# 6. 验证回填结果
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)
print(f'total after backfill: {r.get(\"inventory:{999}:total\")}')
# → 889（从 MySQL 回填，matched 当前值）
"
```

**实测结果**：Canal Consumer 日志 `[库存缓存失效] 删除完成: skuId=999, canalVersion=1785222446000, eventType=UPDATE, deletedKeys=4`。Redis 缓存被删除后，`GET /stock` 触发 `reloadStockToRedis` 回填，total=889，分桶和一致。

### 实验 2：验证版本号防乱序（已验证 ✅ 2026-07-28）

```bash
# 设置版本号为超大值（> 真实的 Canal es），模拟"未来的消息"
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)
r.set('inventory:canal:version:999', '99999999999999', ex=600)
"

# 触发 MySQL 变更（Canal es ≈ 1785222000000，远小于 99999999999999）
mysql -h21.130.247.89 -P13309 -uroot -p'...' my_xhs_inventory \
  -e "UPDATE t_inventory SET available_stock = 1001 WHERE sku_id = 999"

sleep 15

# 版本号不变——Lua 判断 newVersion(1785222xxx) <= currentVersion(99999999999999) → 跳过
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)
v = r.get('inventory:canal:version:999')
print(f'version: {v}')  # → 99999999999999（未改变）
"
```

**实测结果**：Lua 版本比较正确拦截了"旧"消息（真实的 es < 人工设置值），缓存未被删除，版本号保持 99999999999999。防乱序机制有效。
