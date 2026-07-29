# 03 — claim_coupon.lua 深度分析

> **前置阅读**：[架构文档 §4.3](01-coupon-module.md) · [04-Lua 脚本三重奏(07-inventory)](../07-inventory/04-lua-scripts.md)
> **测试验证**：[测试 2 (领券)](02-coupon-test-record.md)

## 为什么 2 个 Key 就够用了？

`claim_coupon.lua` 只操作两个 Key：

```
KEYS[1] = coupon:{templateId}:stock        — 券库存
KEYS[2] = coupon:{templateId}:claimed:{userId} — 用户领取次数
```

对比 inventory 的 `prededuct.lua`（2+N 个 Key，N=桶数），coupon 的 Lua 设计更简洁。核心原因是**不需要分桶**。

inventory 的瓶颈来自**同一个 SKU** 的高并发——秒杀时所有用户抢同一商品，单 Key `inventory:{skuId}:total` 成为瓶颈。分桶把 1 个热点拆成 N 个冷 Key。

coupon 的瓶颈是**不同模板**——用户 A 领满减券，用户 B 领折扣券，用户 C 领无门槛券。不同模板的 `coupon:{templateId}:stock` 是不同的 Key，天然分散。不需要分桶。如果同一个模板成为热点（如"双11限量券"），瓶颈在**模板级别**而非 Key 级别——这是业务问题（卖完了就没了），不需要技术分桶。

---

## 源码分析

源码：`lua/claim_coupon.lua`（49 行）

```lua
-- KEYS[1] = coupon:{templateId}:stock
-- KEYS[2] = coupon:{templateId}:claimed:{userId}
-- ARGV[1] = perUserLimit (每人限领数)

local stockKey = KEYS[1]
local claimedKey = KEYS[2]
local perUserLimit = tonumber(ARGV[1])

-- 1. 检查券库存是否初始化
local stock = redis.call('GET', stockKey)
if not stock then
    return -3  -- 未初始化
end

-- 2. 检查库存是否充足
if tonumber(stock) <= 0 then
    return -1  -- 已领完
end

-- 3. 检查用户是否已达限领上限
local claimed = tonumber(redis.call('GET', claimedKey) or '0')
if claimed >= perUserLimit then
    return -2  -- 已达限领
end

-- 4. 扣减库存
redis.call('DECR', stockKey)

-- 5. 记录用户领取次数
redis.call('INCR', claimedKey)

return 1  -- 领取成功
```

### 设计决策分析

**决策 1：检查顺序——库存在前，限领在后**

```lua
-- 先检查库存（共享资源）
if tonumber(stock) <= 0 then return -1 end

-- 再检查限领（用户独享资源）
if claimed >= perUserLimit then return -2 end
```

如果反过来——先检查限领再检查库存——结果相同但浪费了 Redis 的 GET 操作：库存已为 0 时所有用户都会被限领检查浪费一次 GET。先检查库存可以在库存耗尽时立即短路。

**决策 2：用 DECR/INCR 而非 DECRBY/INCRBY**

每次领券只扣 1 张，`DECR`（默认减 1）比 `DECRBY key 1` 少一个参数，少一次 Redis 协议解析。这是一个**微优化**——在高并发领券场景下（如秒杀），每减少一次 Redis 命令解析都是有意义的。

`INCR` 同理：用户领取次数每次只 +1，不需要 `INCRBY key 1`。

**决策 3：Lua 不做幂等——由 MQ 和 DB 兜底**

inventory 的 `prededuct.lua` 有 `HGET predeductKey skuId → return -1` 的幂等检查。coupon 的 `claim_coupon.lua` 没有幂等——Lua 只负责原子扣减，不关心是否重复。幂等工作由两层外部机制承担：

| 层 | 机制 | 场景 |
|------|------|------|
| MQ Consumer | `MessageIdempotentHelper.isFirstProcess(msgId)` | RocketMQ at-least-once 重复投递 |
| MySQL | `uk_claim_no` 唯一索引 | Consumer 重复消费时 INSERT 触发 DuplicateKeyException |

为什么不在 Lua 做？因为 Lua 没有持久化标识"这个 msgId 已处理过"的能力——它只能检查 Redis Key。如果用一个 Redis Set 记录所有已处理的 msgId，内存会无限增长。把幂等交给 MySQL 的 uk_claim_no 更合理。

**决策 4：-3 分支的处理**

当 `GET stock → nil` 时返回 -3，调用方 `CouponService.claimCoupon` 执行：

```java
case -3 -> {
    initStockFromDb(template);  // MySQL remain_count → Redis SETNX
    result = claim_coupon.lua 重试;
}
```

`initStockFromDb` 用 `setIfAbsent` 初始化——如果并发中另一请求已初始化，`setIfAbsent` 返回 false（不覆盖），本请求的重试会直接成功（因为 Redis 已有值）。这是一个**乐观初始化**模式——不需要分布式锁。

---

## 与 inventory prededuct.lua 的深度对比

| 维度 | claim_coupon.lua | prededuct.lua |
|------|------|------|
| **行数** | 49 行 | 87 行 |
| **KEYS** | 2（stock + claimed） | 2+N（total + prededuct + N buckets） |
| **检查项** | stock > 0 → claimed < limit | HGET 幂等 → total 初始化 → total >= qty → bucket >= qty |
| **扣减** | DECR stock + INCR claimed | DECRBY bucket + DECRBY total |
| **循环** | 无 | 有（桶间遍历） |
| **幂等** | 无（外部两层兜底） | HGET prededuct（Lua 内部） |
| **返回码** | 4 种（1/-1/-2/-3） | 4 种（1/0/-1/-2） |
| **热点处理** | 模板级天然分散 | 分桶 + ZSet 滑动窗口检测 |

**核心差异**：inventory 的并发问题来自于"同一 SKU 太多人抢"（需要分桶+遍历），coupon 的并发问题来自于"同一模板库存有限"（无需分桶，直接 DECR 到 0 就结束了）。

---

## return_coupon.lua 补充分析

源码：`lua/return_coupon.lua`（29 行）

```lua
-- 1. 检查用户领取记录
local claimed = tonumber(redis.call('GET', claimedKey) or '0')
if claimed <= 0 then
    return 0
end

-- 2. 回退库存
redis.call('INCR', stockKey)

-- 3. 减少用户领取次数
redis.call('DECR', claimedKey)

return 1
```

**为什么退券不用 DECRBY/INCRBY？** 每次退券只退 1 张。退券的 Redis 操作和 claim 对称——`INCR stock`、`DECR claimed`，正好是 claim 的 `DECR stock`、`INCR claimed` 的逆操作。

**退券的 power-to-over-return 风险**：`GET claimed → >0? → INCR stock → DECR claimed`。如果 `claimed` 值因某种原因虚高（如 MQ 回滚失败导致 claimed 未回退），退券会凭空增加 stock。但最大影响范围是每张券 1 次——因为 `claimed` 的上限 = 用户实际领取次数，而 MQ 回滚失败的概率极低。

---

## 面试 Q&A

### Q1：为什么领券 Lua 没有幂等检查，而库存预扣的 Lua 有 HGET 幂等？

**答案**：幂等落在不同层。inventory 的幂等在 Lua 内（Redis 可持续），因为预扣是"一个订单扣 N 个 SKU"——Lua 需要知道哪个 orderId 已经扣过。coupon 的幂等在 MQ 和 DB（MQ msgId + MySQL uk_claim_no），因为领券是"一次领一张"——msgId 天然唯一。

**追问**：如果 MQ msgId 和 MySQL uk_claim_no 都漏了，会发生什么？

→ 用户多领一张券。uk_claim_no 是唯一索引——同一 claim_no 的 INSERT 必定触发 DuplicateKeyException。只有在 MySQL 宕机期间 Consumer 写入失败且清理了原 msgId 的幂等标记（Redis SETNX 24h 过期），才会导致真正的重复。概率极低，且被凌晨对账检测。

### Q2：如果 Lua 执行到一半 Redis 宕机——DECR stock 成功了但 INCR claimed 没执行？

**答案**：不会发生。Redis 执行 Lua 是**原子**的——要么全部执行完（DECR + INCR 都成功），要么全部不执行（脚本整体失败）。不存在"部分执行"的中间状态。这是 Redis 单线程执行模型的保证。

### Q3：为什么 claim_coupon.lua 不用 HSET 而是用 INCR/DECR String？

**答案**：String 的 INCR/DECR 是 Redis 最快的原子操作（O(1)，纯 CPU 计算）。Hash 的 HINCRBY 也是 O(1) 但多一次 Hash 字段定位的开销。对于领券这种高频简单操作，String INCR 是最优解。如果用 Hash：`coupon:{templateId}:stats → {stock, claimed:userId}`——多个 userId 的 claimed 值在同一 Hash 中，串行化在同一个 Key 上。用独立 String 键则每个 userId 独占一个 Key，天然并行。

---

## 发散：Redis INCR 的溢出风险

`INCR stockKey` 和 `DECR stockKey` 操作的是 Redis String，底层存的是 64-bit 有符号整数。理论上限 ±9,223,372,036,854,775,807。实际中 `stock` 的上限是 `total_count`（INT, MySQL），最大约 2.1×10⁹——远小于 Redis 上限。

但 `claimed` 计数器理论上可以无限增长（用户反复领券→退券→领券）。如果某个用户对同一模板反复操作 10 万次，`claimed` 值会达到 10 万——仍在 Redis 整数范围内。不会溢出。

**实际限制**：`per_user_limit` 最大值 = 10（@Max(10)），所以 `claimed` 的正常上限 = 10。即使退券再领，claimed 的最大值也不会超过 per_user_limit + 退券次数。对于安全设计，DECR `claimedKey` 后不会变成负数（return_coupon.lua 检查 `claimed <= 0` 则 return 0）。

---

## 生产故障实验

### 实验：验证 Lua 原子性——并发领取最后一张券（已验证 ✅）

```bash
# 1. 创建限量 1 张的券
curl -X POST http://localhost:19010/api/coupon/template \
  -d '{"name":"限量1张","type":3,"discountValue":5,"minAmount":0,"totalCount":1,"perUserLimit":1,"validStart":"2025-01-01 00:00:00","validEnd":"2026-12-31 23:59:59"}'
# → {"data":{"id":2082088457380868098}}

# 2. 并发 3 个用户同时抢
for uid in 20001 20002 20003; do
  curl -s -X POST http://localhost:19010/api/coupon/claim \
    -H "X-User-Id: $uid" -d '{"templateId":2082088457380868098}' &
done
wait

# 3. 结果：1 人成功，2 人失败
# → {"code":200} 用户 20003 成功
# → {"code":30014,"message":"优惠券已领完"} ×2

# 4. 验证 Redis
redis-cli GET "coupon:{2082088457380868098}:stock" → "0"   # 扣完
redis-cli GET "coupon:{2082088457380868098}:claimed:20003" → "1"  # 领到的人
redis-cli GET "coupon:{2082088457380868098}:claimed:20001" → nil  # 没领到
```

**验证**：3 个并发请求，只有 1 个成功。Lua 的 `GET stock → >0? → DECR` 在 Redis 单线程中串行执行——第一个到达的请求读到 stock=1 > 0 → DECR → stock=0；第二个请求读到 stock=0 ≤ 0 → return -1。不会"两个人都领到最后一张"。
