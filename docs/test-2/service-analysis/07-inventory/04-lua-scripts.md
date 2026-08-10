# 04 — Lua 脚本三重奏深度分析

> **前置阅读**：[架构文档 §4.1-4.3 (三个 Lua 脚本)](01-inventory-module.md) · [03-分桶预扣减](03-bucket-pre-deduct.md)
> **测试验证**：[测试 2-4](02-inventory-test-record.md) — prededuct/release/confirm 全链路
> **下游文档**：[05-TCC 分布式事务](05-tcc-fence.md) · [06-MQ 消费链路](06-mq-consumer.md)

## 三个 Lua 脚本：为什么不用 Redis 原生命令分步操作？

三个脚本看似简单（prededuct 68 行、release 48 行、confirm 37 行），但没有一个可以用 Redis 原生命令替代。原因：每一步都是**多 Key 多操作的事务**：

| 脚本 | 操作 Key 数 | 操作步骤 | 如果分步操作会怎样？ |
|------|:--:|------|------|
| prededuct.lua | 2 + bucketCount | HGET + GET + DECRBY×2 + HSET×2 + EXPIRE | bucket 扣了但 total 没扣 → 分桶和不一致 |
| release.lua | 3 | HGET + INCRBY×2 + HDEL×2 + HLEN + DEL | 用户同时 release → 双重回退 → 库存凭空增加 |
| confirm.lua | 1 | HGET + HDEL + HLEN + DEL | confirm 不需要多 Key 但需要 HLEN→DEL 的条件逻辑 |

Redis 原生命令（GET/SET/INCRBY）是单个 Key 的原子操作，但不跨 Key。只有 Lua 脚本能把多 Key 操作串成**一个原子事务**。

---

## prededuct.lua — 分桶预扣的原子引擎

源码：`resources/lua/prededuct.lua`（87 行）

### 实际源码关键片段

```lua
-- 源码第 32-37 行：ARGV 一次性类型转换（而非每次 inline tonumber()）
local skuId = ARGV[1]
local orderId = ARGV[2]
local quantity = tonumber(ARGV[3])
local bucketCount = tonumber(ARGV[4])
local userId = tonumber(ARGV[5])
local expireSeconds = tonumber(ARGV[6])

-- 0. 幂等检查
local existingQty = redis.call('HGET', predeductKey, skuId)
if existingQty then return -1 end

-- 1. 检查总库存是否初始化
local totalStock = redis.call('GET', totalKey)
if not totalStock then return -2 end

-- 2. 快速检查总库存（避免无意义的桶遍历）
if tonumber(totalStock) < quantity then return 0 end

### 设计决策分析

**决策 1：幂等检查前置（阶段 0 在阶段 1 之前）**

如果把幂等检查放在 DECRBY 之后——在 DECRBY 之前 HGET 检查——如果 Key 已存在（重复请求），直接返回 -1，避免网络传输和计算浪费。

**为什么用 HGET 而非 EXISTS？** `EXISTS predeductKey` 只检查 Key 是否存在。但一个 orderId 的预扣记录可能包含多个 SKU，需要用 HGET 检查**特定 skuId 是否已存在**——这是字段级别的幂等，而非 Key 级别的幂等。

**决策 2：总库存快速检查（阶段 1）**

```lua
if tonumber(totalStock) < tonumber(ARGV[3]) then return 0 end
```

如果总库存都不够，不需要遍历桶。一个 O(1) 的 GET 操作避免了 O(N) 的桶遍历。在库存即将耗尽的场景下（如秒杀尾声），大量请求会触发这个快速路径——大幅减少无效的桶遍历开销。

**决策 3：`999:bucket` 辅助字段**

```lua
redis.call('HSET', KEYS[2], ARGV[1] .. ':bucket', routeBucket)
```

为什么需要记录来源桶号？releaseStock 需要知道把库存回退到哪个桶。如果没有这个字段，只能回退到桶 0——导致桶 0 的库存虚高，其他桶正常，桶间分布倾斜。M12 修复前 Javadoc 声称"回退到桶 0"，M12 后通过 `:bucket` 精准回退。这个辅助字段增加了每笔预扣 ~20 bytes 的存储（field 名 + field 值），但换来了释放时的精准回退能力。

**决策 4：桶间遍历的环形顺序**

```lua
for offset = 1, bucketCount - 1 do
    local i = (routeBucket + offset) % bucketCount
```

从 `routeBucket + 1` 开始环形搜索，而非从桶 0 开始。原因：如果所有"溢出"请求都从桶 0 开始找，桶 0 会被过度借用——本来桶 0 已经拿了余数（`perBucket + remainder`），再被所有用户的溢出请求"借用"，会加速耗尽。环形搜索让溢出均匀分布：routeBucket=1 的用户从桶 2 开始找，routeBucket=2 的用户从桶 3 开始找，形成循环轮转。

---

## release.lua — 原子回退的三 Key 事务

源码：`resources/lua/release.lua`（49 行）

```lua
-- KEYS[1] = inventory:{skuId}:total
-- KEYS[2] = inventory:prededuct:{orderId}
-- KEYS[3] = inventory:{skuId}:bucket:{sourceBucket}
-- ARGV[1] = skuId

-- 1. 获取预扣数量
local quantity = redis.call('HGET', KEYS[2], ARGV[1])
if not quantity then return 0 end  -- 已释放/已确认

-- 2. 回退到来源桶
redis.call('INCRBY', KEYS[3], quantity)

-- 3. 回退总库存
redis.call('INCRBY', KEYS[1], quantity)

-- 4. 删除预扣记录
redis.call('HDEL', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[2], ARGV[1] .. ':bucket')

-- 5. 清理空 Hash
if redis.call('HLEN', KEYS[2]) == 0 then
    redis.call('DEL', KEYS[2])
end

return quantity
```

### 为什么必须用 Lua 而非分步操作？

这是三个脚本中最需要原子性保证的——因为它与用户手动 releaseStock、PreDeductTimeoutJob 同时竞争同一个 orderId 的释放操作。

**竞态场景**：

```
时刻 T1：定时任务 SCAN 到 inventory:prededuct:777001，TTL=-1（已过期）
时刻 T2：用户手动 POST /api/inventory/release {"orderId":777001}

如果分步操作：
  T1: HGETALL 777001 → {"999":"3"}  （定时任务读到正在被释放的数据）
  T2: release.lua → INCRBY total +3, HDEL prededuct  （用户手动释放成功）
  T1: INCRBY total +3              （定时任务又加了一次！）
  → total 多出 3，库存凭空增加

用 Lua 原子操作：
  T1: (空过，Lua 未触发)
  T2: HGET "999" → "3" → HDEL → INCRBY → 返回 3
  T1: HGET "999" → nil → 返回 0 → 不做任何操作
  → 只回退一次，total 正确
```

关键：`HGET` 和 `HDEL` 在 Lua 中是原子的。定时任务和用户请求**不会看到对方的中间状态**。Lua 在 Redis 单线程中执行，`HGET` 读到 "3" 后立刻 `HDEL` 删除，另一个请求的 `HGET` 只能读到 nil——谁先抢到 HGET 谁就获得回退权。

### 为什么回退到来源桶（KEYS[3]）？

调用方通过 HGET `{skuId}:bucket` 获取来源桶号，构造对应的 bucket Key 传入。精准回退到来源桶而非桶 0：

```
错误做法（回退到桶 0）：
  预扣: bucket:2 166→163
  释放: bucket:0 168→171  ← 桶 0 多了不该多的

正确做法（回退到来源桶）：
  预扣: bucket:2 166→163
  释放: bucket:2 163→166  ← 桶间分布不变
```

---

## confirm.lua — 最简脚本的精确语义

源码：`resources/lua/confirm.lua`（38 行，修复后；修复前 37 行）

```lua
-- KEYS[1] = inventory:prededuct:{orderId}
-- ARGV[1] = skuId

-- 1. 获取预扣数量
local quantity = redis.call('HGET', KEYS[1], ARGV[1])
if not quantity then return 0 end

-- 2. 删除预扣记录（+ 辅助字段，修复后）
redis.call('HDEL', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[1], ARGV[1] .. ':bucket')  -- 【修复】

-- 3. 清理空 Hash
if redis.call('HLEN', KEYS[1]) == 0 then
    redis.call('DEL', KEYS[1])
end

return quantity
```

### 为什么 confirm 只做删除？

预扣时 Redis 已经 `DECRBY total + bucket`（库存实际上已扣）。confirm 只是将预扣"转正"——删除预扣记录标记。Redis 层面的库存不需要再变动。MySQL 层的 `locked_stock → 0` 由 MQ Consumer 处理。

**confirm 不操作 total/bucket 的物理论证**：

```
下单时的用户视角：
  商品详情页：库存显示 497（已反映预扣减）
  订单详情页：库存显示 497（支付成功后仍显示 497）
  → 一致！confirm 不改 Redis，用户看到的是正确的库存。

如果 confirm 再 DECRBY：
  商品详情页：库存显示 497 → 支付成功后 → 492（多扣了一次！）
  → 错误！
```

### `:bucket` 残留问题的修复

**修复前**：HDEL 只删除 `skuId` 字段，未删除 `skuId:bucket` 字段。导致 `HLEN = 1`（`:bucket` 残留），Hash Key 不被清理，依赖 EXPIRE 自然过期。

**修复后**：HSET 之后立即 HDEL 两个字段（`skuId` + `skuId:bucket`），`HLEN = 0` → DEL Key。内存立即释放。

**修复代码**（`confirm.lua`）：新增一行 `redis.call('HDEL', KEYS[1], ARGV[1] .. ':bucket')`。

---

## M14 修复：Cluster 兼容性

### 旧版的问题

旧版 prededuct.lua 在 Lua 内部动态拼接 Key：

```lua
-- ❌ 旧版：动态拼接 Key（Cluster 下报 CROSSSLOT 错误）
local bucketKey = "inventory:" .. skuId .. ":bucket:" .. bucketNo
local stock = redis.call('GET', bucketKey)
```

Redis Cluster 对 Lua 脚本的要求：**所有被操作的 Key 必须预先传入 KEYS 参数**，Redis 会检查这些 Key 是否落在同一 hash slot。动态拼接的 Key 不在 KEYS 中 → Redis 不知道它是什么 slot → 直接拒绝执行。

### 修复方案

Java 层预先构建所有 Key：

```java
List<String> keys = new ArrayList<>(2 + bucketCount);
keys.add(totalKeyStr);          // KEYS[1]
keys.add(predeductKeyStr);      // KEYS[2]
for (int i = 0; i < bucketCount; i++) {
    keys.add(bucketKey(skuId, i));  // KEYS[3..N+2]
}
```

Lua 层用 `KEYS[i]` 访问：

```lua
-- ✅ 新版：使用预先传入的 KEYS 参数
local routeKeyIdx = 3 + routeBucket
local stock = redis.call('GET', KEYS[routeKeyIdx])
```

**hash tag `{skuId}` 的配合**：`inventory:{999}:total` 和 `inventory:{999}:bucket:N` 都包含 `{999}`，CRC16 哈希后落到同一 slot。KEYS 检查通过后，Lua 可以安全操作所有 Key。

**为什么 prededuct Key 没有 hash tag？** `inventory:prededuct:777001` 不包含 `{skuId}`，CRC16 可能落到不同 slot。但 predeductKey 也通过 KEYS 传入——这意味着如果 predeductKey 与 total/bucket 不在同一 slot，KEYS 检查会失败。在当前 Sentinel 架构下这不是问题（单节点，无 slot 概念），但迁移到 Cluster 时需要让 predeductKey 也包含 hash tag。

---

## 面试 Q&A

### Q1：三个 Lua 脚本，哪个最容易出现性能瓶颈？

**答案**：prededuct.lua。它操作的 Key 数量 = 2 + bucketCount（最多 10 个），包含 O(N) 的桶遍历循环。当库存接近耗尽时，几乎每个请求都要遍历所有桶——每次 O(N) GET。release.lua 和 confirm.lua 只操作 1-3 个 Key，没有循环。

**追问**：如何优化近零库存时的桶遍历开销？

→ 阶段 1 的快速检查 `if tonumber(totalStock) < quantity then return 0` 是应对这个场景的关键优化——总库存不足时直接拒绝，不进入桶遍历循环。

### Q2：如果 prededuct.lua 执行到一半 Redis 宕机了怎么办？

**答案**：Lua 脚本在 Redis 中是**原子执行**的——Redis 不会在脚本执行中间处理其他命令。如果 Redis 在脚本执行期间宕机（如 OOM kill、进程崩溃），整个脚本的操作都不会被持久化（如果是 AOF 则不会记录不完整的脚本）。脚本要么全部成功，要么全部不生效。

**追问**：AOF 模式下脚本执行期间宕机呢？

→ Redis 的 AOF 以命令日志方式记录。Lua 脚本作为一条 `EVAL` 命令记录——如果宕机发生在 EVAL 返回之前，这条 EVAL 不会写入 AOF。恢复后从头重放 AOF，不会包含不完整的脚本执行。

### Q3：为什么 release.lua 用 INCRBY 而不是直接 SET 新值？

**答案**：因为 release.lua 可能与 preDeduct 并发——如果在 release 执行 INCRBY 的同时，另一个请求正在预扣同一个 SKU 的不同订单，INCRBY 不会覆盖那个请求的 DECRBY：

```
时间线：
  T1: 请求 A 预扣 skuId=999, qty=2 → DECRBY bucket:1 2 (bucket:1 166→164)
  T2: 请求 B 释放 skuId=999, qty=3 → INCRBY bucket:2 3 (bucket:2 163→166)
  → 两个操作操作不同的桶，互不干扰 ✓

如果用 SET（覆盖值）：
  T1: bucket:1 166→164
  T2: SET bucket:2 166（覆盖 163，丢失了 T1 的可能并发操作）
```

### Q4：confirm.lua 为什么是最简脚本——不能和 release.lua 合并吗？

**答案**：不能。confirm 和 release 的语义正好相反：

| 操作 | Redis 动作 | MySQL 动作 |
|------|------|------|
| confirm | HDEL prededuct（只删记录） | locked_stock - qty |
| release | INCRBY bucket + INCRBY total + HDEL（恢复库存） | available + qty, locked - qty |

confirm 不改动 total/bucket（库存已在预扣时消耗），release 必须恢复（库存回到可用池）。如果合并，需要一个额外参数区分两种语义——反而增加复杂度和出错概率。

---

## 发散：Redis Lua vs 替代方案的横向对比

inventory 用 Lua 脚本实现原子扣减——但 Redis 生态中还有其他实现原子多 Key 操作的方式。为什么选择 Lua 而非它们？

### vs Redis Transaction（WATCH + MULTI + EXEC）

```
Redis Transaction 流程：
  WATCH key1 key2          # 乐观锁监控
  MULTI                    # 开始事务
  DECRBY key1 3
  DECRBY key2 3
  EXEC                     # 如果 key1/key2 未被修改→全部执行；否则→全部放弃
```

| 维度 | Lua 脚本 | Redis Transaction |
|------|---------|-------------------|
| 原子性保证 | Redis 单线程执行，绝对原子 | WATCH 乐观锁，EXEC 前被修改→全部回滚 |
| 条件逻辑 | 支持 if/else/for（Turing 完备） | 不支持——所有命令一次性提交，无分支 |
| 性能 | 一次网络往返 | 至少 3 次（WATCH + MULTI + EXEC）+ 重试可能 |
| 适合场景 | 复杂多 Key 原子操作 + 条件分支 | 简单批量写入 |

**为什么不适合 inventory**：prededuct.lua 需要 `if routeStock >= quantity` 的条件判断——Redis Transaction 做不到。WATCH 的乐观锁重试机制在秒杀高并发下会导致大量 EXEC 失败重试，吞吐量急剧下降。

### vs Redisson RLock（分布式锁）

```
RLock 方案：
  lock = redisson.getLock("inventory:lock:" + skuId)
  lock.lock()
  try {
      total = GET totalKey
      bucket = GET bucketKey
      if bucket >= quantity: DECRBY bucket quantity; DECRBY total quantity
      HSET predeductKey skuId quantity
  } finally { lock.unlock() }
```

| 维度 | Lua 脚本 | Redisson RLock |
|------|---------|----------------|
| 并发模型 | Redis 单线程，天然串行 | 分布式锁，多客户端可能竞争 |
| 延迟 | ~1ms（单次 EVALSHA） | ~5-20ms（lock 获取 + 业务逻辑 + unlock） |
| 锁超时风险 | 无 | 业务逻辑超过锁超时→锁自动释放→其他客户端获取→数据错乱 |
| 死锁风险 | 无 | 有（需 watch dog 续期） |
| Redis 故障 | 脚本原子性，要么全成功要么全失败 | 锁可能残留（需 RedLock 算法） |

**为什么不适合 inventory**：分桶的目的就是消除锁竞争——用 RLock 相当于又把竞争引回来了。一个 SKU 的所有请求在 RLock 上串行化，恰好违背了分桶的并发扩展目标。

### vs Redis Functions（Redis 7.0+）

```
# 可行但需要 Redis 7.0+
FUNCTION LOAD "#!lua name=prededuct\n
  redis.register_function('prededuct', function(keys, args)
    -- 同 Lua 脚本逻辑
  end)
"
FCALL prededuct 0 totalKey predeductKey bucketKey0 bucketKey1 skuId orderId qty userId
```

| 维度 | Lua 脚本（EVAL/EVALSHA） | Redis Functions |
|------|---------|----------------|
| 版本要求 | Redis 2.6+ | Redis 7.0+ |
| 持久化 | 不持久化（重启丢失 SHA） | 持久化到 RDB/AOF（Function 库） |
| 管理 | Spring Bean 预加载 | `FUNCTION LOAD` 部署 |
| 适用 | 一般 | 多租户/函数库共享/无状态重启 |

当前 Redis 版本 7.4.9，理论上支持 Redis Functions——但 Spring Data Redis 尚未提供 `FCALL` 的便捷封装，且 Functions 增加运维复杂度。脚本数量少（3 个）+ 部署简单（classpath 文件）使 EVAL/EVALSHA 比 Functions 更适合。

---

## 生产故障实验

### 实验 1：验证 Lua 原子性——手动模拟并发扣减

```bash
# 准备：初始化库存 10
curl -X POST http://localhost:19009/api/inventory/init \
  -H "Content-Type: application/json" -d '{"skuId":777,"totalStock":10,"bucketCount":2}'

# 并发发送 5 个扣减请求（每种扣 3 件，总共需要 15 件 > 库存 10）
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:19009/api/inventory/preDeduct \
    -H "Content-Type: application/json" \
    -d "{\"orderId\":77800$i,\"skuId\":777,\"quantity\":3,\"userId\":1000$i}" &
done
wait

# 验证：有些成功（200），有些失败（10001 库存不足）
# 成功的请求总量应该 ≤ 10
```

**观察点**：Lua 脚本保证并发下不会超卖——如果 5 个请求同时进入，Redis 串行执行 Lua，后到达的请求发现 `totalStock < 3` → 直接返回 0。即使所有请求"同时"到达，也不会出现 total 扣成负数。

### 实验 2：验证 confirm.lua 修复效果

```bash
# 1. 预扣减
curl -X POST http://localhost:19009/api/inventory/preDeduct \
  -H "Content-Type: application/json" \
  -d '{"orderId":778100,"skuId":777,"quantity":1,"userId":10001}'

# 2. 确认扣减（修复后）
curl -X POST http://localhost:19009/api/inventory/confirm \
  -H "Content-Type: application/json" -d '{"orderId":778100}'

# 3. 验证 prededuct Key 已被完全删除
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)
k = 'inventory:prededuct:778100'
print(f'exists={r.exists(k)}, hgetall={r.hgetall(k)}')
"
# 修复前: exists=1, hgetall={'777:bucket': '0'} ← :bucket 残留
# 修复后: exists=0, hgetall={}                ← 完全删除
```

---

## 工程分析

### 1. Lua 脚本的 SHA1 缓存

`RedisScriptConfig` 将三个脚本预加载为 Spring Bean：

```java
@Bean
public DefaultRedisScript<Long> preDeductScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(new ResourceScriptSource(
        new ClassPathResource("lua/prededuct.lua")));
    script.setResultType(Long.class);
    return script;
}
```

Spring Data Redis 的 `DefaultRedisScript` 在首次执行时发送 `EVAL`，Redis 返回 SHA1。后续执行走 `EVALSHA`（只传 SHA1，不传脚本体），减少网络传输量（脚本体 ~2KB vs SHA1 ~40 bytes）。

### 2. 三个脚本的 Key 设计对比

| 维度 | prededuct.lua | release.lua | confirm.lua |
|------|:--:|:--:|:--:|
| KEYS 数量 | 2+N | 3 | 1 |
| 总库存 | 读取 + DECRBY | INCRBY 回退 | 不操作 |
| 桶库存 | 读取 + DECRBY | INCRBY 回退 | 不操作 |
| 预扣记录 | HSET + EXPIRE | HDEL + DEL | HDEL + DEL |
| 返回负数 | -1(幂等) / -2(未初始化) | 0(不存在) | 0(不存在) |
| 循环 | 有（桶间遍历） | 无 | 无 |

release.lua 操作 3 个 Key（total + prededuct + bucket），confirm.lua 操作 1 个 Key。3→1 的递减是因为：释放需要恢复 total 和 bucket（通知 Redis"库存回来了"），确认只需要删除记录（库存早已扣完）。

### 3. prededuct.lua 中 ARGV 的类型转换策略

Lua 的 `ARGV` 全是字符串，但 prededuct.lua 在开头**一次性将所有 ARGV 转为 number**（源码第 32-37 行）：

```lua
local quantity = tonumber(ARGV[3])
local bucketCount = tonumber(ARGV[4])
local userId = tonumber(ARGV[5])
local expireSeconds = tonumber(ARGV[6])
```

后续代码直接使用 `quantity`、`bucketCount` 等局部变量，避免每次 `tonumber(ARGV[...])` 的重复调用。`tonumber` 是 O(1) 的 C 函数，但 6 次调用变为 1 次引用仍然减少了解析开销。对比 release.lua 和 confirm.lua（无需数学运算，只做字符串匹配），prededuct 的数值密集特性使得这个优化有意义。

### 4. 为什么不能把三个 Lua 脚本合并成一个"通用库存操作"脚本？

如果用一个脚本通过 ARGV 参数区分三种操作（如 `ARGV[0]="PRE_DEDUCT" | "CONFIRM" | "RELEASE"`），需要在 Lua 内部写 if/else 分支。分拆成三个独立脚本的好处：

1. **语义清晰**：文件名 = 操作名，不需要读完整个脚本才知道做什么
2. **SHA1 独立缓存**：每个脚本有独立的 SHA1，修改一个不影响另外两个的 EVALSHA 缓存
3. **错误隔离**：修改 prededuct.lua 的 bug 不会影响 release 和 confirm 的稳定性
4. **安全**：confirm 不能操作 total/bucket（即使代码写错了也没机会）
