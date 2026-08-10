# my-xhs-inventory 库存服务模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-inventory/` |
| 端口 | 19009 |
| 服务名 | `my-xhs-inventory`（Nacos） |
| 数据库 | `my_xhs_inventory`（MySQL 13309 Master / 13313 Slave，读写分离） |
| Java 源文件 | 19 个（含 DTO/Consumer/Job/HotDetector/Config） |
| Lua 脚本 | 3 个（prededuct / release / confirm） |
| 启动类 | `InventoryApplication.java` |
| 扫描包 | `com.myxhs.inventory`, `com.myxhs.common` |

**职责边界**：扣减 SKU 可用库存，提供预扣减/确认/释放/查询接口。被 order 服务通过 Feign 调用。

**核心设计理念**：三级扣减保证 + 分桶路由解热点 + TCC 分布式事务（MySQL 层）

```
L1: Redis 分桶预扣（Lua 原子，毫秒级，用户即刻拿到结果）
    ├─ 普通 SKU：2 桶
    └─ 热点 SKU：自动扩容到 8 桶

L2: MQ 异步扣 MySQL（持久化，Consumer 幂等 + 乐观锁重试 3 次）

L3: 定时对账修复（凌晨 3 点，Redis → MySQL）
    ├─ 分桶完整性校验（∑bucket == total）
    └─ 预扣超时回退（每 5 分钟 SCAN 过期记录）
```

**TCC 路径（订单服务 Feign → inventory）**：

```
Try:  available_stock → freezing_stock（Fence 防悬挂/幂等）
Confirm: freezing_stock → 0（支付成功）
Cancel:  freezing_stock → available_stock（取消/超时，Fence 空回滚）
```

---

## 1. 数据模型

### 1.1 数据库表 — `t_inventory`

```sql
id              BIGINT PRIMARY KEY        -- 雪花 ID（ASSIGN_ID）
sku_id          BIGINT NOT NULL           -- SKU ID，唯一索引 uk_sku_id
available_stock INT DEFAULT 0             -- 可用库存
locked_stock    INT DEFAULT 0             -- 锁定库存（预扣未确认，L1→L2 中间态）
freezing_stock  INT DEFAULT 0             -- TCC 冻结库存（TCC Try 阶段占用）
deleted         TINYINT DEFAULT 0         -- 逻辑删除（@TableLogic）
created_at      DATETIME
updated_at      DATETIME
```

**三个库存字段的语义**：

| 字段 | L1/L2 路径 | TCC 路径 |
|------|-----------|---------|
| `available_stock` | 可用库存，预扣减时与 `locked_stock` 互换 | Try 时减，Cancel 时加回 |
| `locked_stock` | 预扣锁定，Confirm 时清零 | 不使用 |
| `freezing_stock` | 不使用 | Try 时加，Confirm/Cancel 时减 |

> 两条路径互斥：L1/L2 操作 `(available, locked)` 对，TCC 操作 `(available, freezing)` 对。

**为什么保留两条并行路径而非统一？**

L1/L2 和 TCC 是两种不同场景的解决方案：
- L1/L2：适用于**最终一致性可接受**的高并发扣减（秒杀）。Redis 预扣 1ms 返回，用户无需等待 MySQL 事务。一致性由 L3 对账兜底。
- TCC：适用于**要求强一致性**的订单履约（如 order 需要 inventory + coupon + payment 三方都成功才确认）。Fence 表 + @Transactional 保证 ACID。

两条路径使用不同的 MySQL 字段（`locked_stock` vs `freezing_stock`），互不干扰。同时保留而非统一的原因：如果强制 TCC 路径走 Redis 分桶，Try→Confirm→Cancel 三阶段需要在 Redis 侧也实现 Fence 机制，增加复杂度且 Redis 不适合做持久化 Fence 记录。

### 1.2 Redis Key 设计

```
# 总可用库存（hash tag {skuId} 保证与 bucket Key 同 slot）
inventory:{skuId}:total                     → String, 值=所有桶库存之和

# 分桶库存
inventory:{skuId}:bucket:{0..N-1}           → String, 值=该桶库存数

# 预扣记录（按 orderId 组织，独立 slot）
inventory:prededuct:{orderId}               → Hash
  └─ {skuId}           → quantity          （扣减数量）
  └─ {skuId}:bucket    → bucketNo          （来源桶号）

# 辅助 Key
inventory:bucket:count:{skuId}              → String, 值=分桶数
inventory:paused:{skuId}                    → String, 扩容暂停标记（30s TTL）
inventory:resize:{skuId}                    → String, 扩容分布式锁（SETNX, 10s）
inventory:hot:window:{skuId}                → ZSet, 热点检测滑动窗口
inventory:canal:version:{skuId}             → String, Canal 版本号（7d TTL，防乱序）
```

**写入端口**：

| 端口 | 用途 |
|:--:|------|
| 16380 | Cache Redis（allkeys-lru），Canal 缓存失效 Consumer 删除目标 |
| 16381 | Business Redis（noeviction），业务 Key（预扣/分桶/对账/热点） |

实际 `@Bean` 注入的 `StringRedisTemplate` 是 Spring Boot 默认 Bean，经 Sentinel 发现 Master（16379）写入。

**hash tag `{skuId}` 的作用**：确保 `inventory:{skuId}:total` 和 `inventory:{skuId}:bucket:N` 落在 Redis Cluster 的同一 slot，从而 prededuct.lua 可以在单个节点上原子操作 total + 所有桶 Key。

### 1.3 Entity — Inventory

```java
@TableName("t_inventory")
public class Inventory {
    @TableId(type = IdType.ASSIGN_ID)  // 雪花算法
    private Long id;
    private Long skuId;               // 唯一索引
    private Integer availableStock;
    private Integer lockedStock;
    private Integer freezingStock;
    @TableLogic                      // 逻辑删除
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

---

## 2. 接口清单（9 个 REST 端点）

| 方法 | 路径 | 说明 | 可见性 | 限流 |
|:----:|------|------|:------:|------|
| POST | `/api/inventory/init` | 库存初始化（DB→Redis 分桶） | 管理后台 | @RateLimit 5/60s |
| POST | `/api/inventory/preDeduct` | 预扣减（下单时 order Feign 调用） | 内部（Feign） | — |
| POST | `/api/inventory/confirm` | 确认扣减（支付成功后 order Feign） | 内部（Feign） | — |
| POST | `/api/inventory/release` | 释放库存（取消/超时 order Feign） | 内部（Feign） | — |
| POST | `/api/inventory/reinit` | 重新初始化（管理后台应急修复） | 管理后台 | @RateLimit 2/60s |
| GET | `/api/inventory/stock/{skuId}` | 查询可用库存 | 公开 | — |
| POST | `/api/inventory/tcc/try` | TCC Try（冻结库存） | 内部（order Feign） | — |
| POST | `/api/inventory/tcc/confirm` | TCC Confirm（确认冻结） | 内部（order Feign） | — |
| POST | `/api/inventory/tcc/cancel` | TCC Cancel（解冻） | 内部（order Feign） | — |

### 2.1 POST /api/inventory/init — 库存初始化

```json
// Request
{ "skuId": 100, "totalStock": 1000, "bucketCount": 2 }

// Response
{ "code": 200, "message": "success" }
```

**内部逻辑**（`InventoryService.initStock`）：

1. SETNX `inventory:{skuId}:total` = totalStock（幂等检查，已存在则抛异常）
2. MySQL：无记录则 INSERT，有则 UPDATE `available_stock=totalStock, locked_stock=0`
3. 均匀分配：`perBucket = total / N`，余数给桶 0
4. 写入 N 个桶 Key + 桶数量 Key

**边界条件**：调用前须 `DELETE inventory:{skuId}:total`（如重新初始化）

### 2.2 POST /api/inventory/preDeduct — 预扣减

```json
// Request
{ "orderId": 3001, "skuId": 100, "quantity": 2, "userId": 1001 }

// Response
{ "code": 200, "message": "success" }
// 库存不足
{ "code": 10001, "message": "库存不足" }
```

**内部逻辑**（`InventoryService.preDeduct`）：

0. 扩容暂停检查（`inventory:paused:{skuId}` 存在则抛异常，MQC 重试兜底）
1. 读取分桶数（不存在则抛"库存未初始化"）
2. 热点检测（异步触发扩容，不阻塞本次请求，见 §7.1）
3. 执行 prededuct.lua（全部 KEYS 传入，见 §5.1）
4. 根据返回值（1 成功/0 不足/-1 重复/-2 未初始化）处理
5. L2 MQ 同步发送（失败则 Lua 原子回滚 + 抛异常）

### 2.3 POST /api/inventory/confirm — 确认扣减

```json
// Request
{ "orderId": 3001 }
```

**内部逻辑**（`InventoryService.confirmDeduct`）：

1. HGETALL `inventory:prededuct:{orderId}` 获取所有 SKU（空则幂等返回）
2. 逐个 SKU 执行 confirm.lua 删除预扣记录
3. syncSend `CONFIRM` MQ 事件（**fire-and-forget**：不检查返回值，失败不抛异常也不回滚。对应风险见已知问题 §11.5）

### 2.4 POST /api/inventory/release — 释放库存

```json
// Request
{ "orderId": 3001 }
```

**内部逻辑**（`InventoryService.releaseStock`）：

1. HGETALL 预扣记录（空则幂等返回）
2. 逐个 SKU：HGET `{skuId}:bucket` 获取来源桶号
3. 执行 release.lua（totalKey + predeductKey + bucketKey 三 Key 原子回退）
4. 发送 `RELEASE` MQ 事件（异步）

**回退目标**：回到来源桶（而非桶 0）。预扣时通过 `HSET {skuId}:bucket bucketNo` 记录来源，释放时 HGET 后精准回退。

### 2.5 POST /api/inventory/reinit — 重新初始化

```json
// Request
{ "skuId": 100, "totalStock": 2000, "bucketCount": 4 }
```

**内部逻辑**（`InventoryService.reinitStock`）：

1. 从 MySQL 读 `available_stock + locked_stock` 作为真实库存（忽略 `freezing_stock` 的 TCC 路径）
2. `keys inventory:{skuId}:bucket:*` + DEL total/bucketCount/paused（**注意：用 KEYS 而非 SCAN**，管理后台偶发调用，KEYS 阻塞可接受）
3. 按新桶数重新分桶

### 2.6 GET /api/inventory/stock/{skuId} — 查询库存

```json
// Response
{ "code": 200, "data": { "skuId": 100, "availableStock": 998, "lockedStock": 2, "bucketCount": 2, "initialized": true } }
```

**内部逻辑**（`InventoryService.getStock`）：

1. Redis GET `inventory:{skuId}:total` → 命中直接返回
2. 未命中：MySQL 查询 → 判断是否需要回填
3. 回填判断（三条件）：
   - `bucket:count` Key 存在 → 回填（Canal 部分删除）
   - `canal:version` Key 存在 → 回填（Canal 全量删除）
   - 均不存在 → 不回填（从未初始化，只返回 MySQL 数据）
4. 回填用 Pipeline 批量 SET（非 SETNX，保证覆盖 Canal 删除后的 Key）

### 2.7-2.9 TCC 接口

```json
// Try
POST /api/inventory/tcc/try
{ "xid": "order:ORDER20260101001", "branchId": 1001, "skuItems": [{"skuId": 100, "quantity": 2}] }

// Confirm
POST /api/inventory/tcc/confirm
{ "xid": "...", "branchId": 1001, "skuItems": [...] }

// Cancel
POST /api/inventory/tcc/cancel
{ "xid": "...", "branchId": 1001, "skuItems": [...] }
```

**TCC Fence 状态机**：

```
Try:    INSERT (status=1) → 主键冲突=幂等放行 或 悬挂拒绝(status=3)
        执行业务：available-=qty, freezing+=qty

Confirm: status=1 → UPDATE status=2 (CAS)
         执行业务：freezing-=qty
         幂等：已是 status=2 直接返回

Cancel:  INSERT (status=3) → 主键冲突=幂等(status=3)或转Cancel(status=1→3)
         执行业务：freezing-=qty, available+=qty
         拒绝：已是 status=2(已Confirm) 不可Cancel
```

**fence 表**：`t_tcc_fence (xid, branch_id, action_name, status)` — status: 1=Try, 2=Confirmed, 3=Cancelled

---

## 3. Mapper — 6 个自定义 SQL（乐观锁）

| 方法 | SQL | 乐观锁条件 |
|------|-----|-----------|
| `deductStock(skuId, qty)` | `available -= qty, locked += qty` | `WHERE available >= qty AND deleted=0` |
| `confirmDeduct(skuId, qty)` | `locked -= qty` | `WHERE locked >= qty AND deleted=0` |
| `releaseStock(skuId, qty)` | `available += qty, locked -= qty` | `WHERE locked >= qty AND deleted=0` |
| `tryFreeze(skuId, qty)` | `available -= qty, freezing += qty` | `WHERE available >= qty` |
| `confirmFreeze(skuId, qty)` | `freezing -= qty` | `WHERE freezing >= qty` |
| `cancelFreeze(skuId, qty)` | `available += qty, freezing -= qty` | `WHERE freezing >= qty` |

> `tryFreeze/confirmFreeze/cancelFreeze` 的乐观锁条件 **不含 `deleted=0`**（与 L1/L2 的 `deductStock/confirmDeduct/releaseStock` 不同）。这是源码中的实际差异。

---

## 4. 核心流程

### 4.1 分桶预扣 — `prededuct.lua` 完整逻辑

```
输入: KEYS[1]=total, KEYS[2]=prededuct, KEYS[3..N+2]=buckets
      ARGV[1]=skuId, ARGV[2]=orderId, ARGV[3]=quantity,
      ARGV[4]=bucketCount, ARGV[5]=userId, ARGV[6]=expireSec

1. HGET prededuct skuId → 存在则返回 -1（幂等）
2. GET total → nil 则返回 -2（未初始化）
3. total < quantity → 返回 0（库存不足，快速路径）
4. routeBucket = userId % bucketCount
5. GET KEYS[3+routeBucket] >= quantity？
   ├─ 是：DECRBY bucket, DECRBY total, HSET prededuct, EXPIRE → 返回 1
   └─ 否：遍历其他桶（从 routeBucket+1 开始，环形）
          找到则同上 → 返回 1
6. 全部桶不足 → 返回 0
```

**为什么 userId 路由而非随机？**
同一用户的连续请求总是落在同一桶，避免热点用户在多个桶间跳跃导致的局部热点。但如果该桶被扣空，会自动遍历到其他桶（桶间均衡）。

### 4.2 释放库存 — `release.lua` 完整逻辑

```
输入: KEYS[1]=total, KEYS[2]=prededuct, KEYS[3]=bucket
      ARGV[1]=skuId

1. HGET prededuct skuId → nil 则返回 0（已释放/已确认）
2. INCRBY bucket qty  （回退到来源桶）
3. INCRBY total qty
4. HDEL prededuct skuId, HDEL prededuct skuId:bucket
5. HLEN prededuct == 0 → DEL prededuct（清理空 Hash）
```

### 4.3 确认扣减 — `confirm.lua` 完整逻辑

```
输入: KEYS[1]=prededuct
      ARGV[1]=skuId

1. HGET prededuct skuId → nil 则返回 0
2. HDEL prededuct skuId
3. HLEN prededuct == 0 → DEL prededuct
```

**为什么 confirm 不改动 total/bucket？** 因为预扣时已经 DECRBY 了。确认只是将预扣"转正"——删除预扣记录标记，MySQL 层 locked_stock→0。

### 4.4 三级扣减完整时序

```
order服务                  inventory服务                  MQ Consumer           MySQL
   │                           │                              │                  │
   │ POST /preDeduct           │                              │                  │
   ├──────────────────────────>│                              │                  │
   │                      ┌────┤                              │                  │
   │                      │L1  │ prededuct.lua               │                  │
   │                      │    │ DECRBY total, DECRBY bucket │                  │
   │                      └────┤ HSET prededuct              │                  │
   │                           │ syncSend INVENTORY_TOPIC     │                  │
   │                           ├─────────────────────────────>│                  │
   │    { "code": 200 }      │ │                            │                  │
   │<──────────────────────────┤                            │ handlePreDeduct  │
   │                           │                            ├─────────────────>│
   │                           │                            │ UPDATE available │
   │                           │                            │ -= qty,          │
   │                           │                            │ locked += qty    │
   │                           │                            │ (乐观锁, 3次重试) │
   │                      (对账)│                            │                  │
   │                   凌晨3点  │ reconcile: Redis vs MySQL  │                  │
   │                    以Redis为准修复MySQL                 │                  │
```

### 4.5 MQ 同步发送 vs 异步发送的选择

`sendInventoryEvent` 使用 `rocketMQTemplate.syncSend()`（超时 3 秒）。

```
为什么必须同步？
├─ 异步发送失败：Redis 已扣减，MySQL 永远不更新 → 库存凭空消失
└─ 同步发送失败：立即 rollbackPreDeduct() 执行 release.lua 回退 → 一致性恢复
```

**回退失败的兜底**：若 rollbackPreDeduct 也失败，`log.error` 打出 `"需人工介入"` + 异常信息。这类场景极端罕见（Redis 连接断开），但 30 分钟的预扣过期 + 对账任务提供最终一致性兜底。

### 4.6 MQ Consumer — InventoryDeductConsumer

消费 Topic：`INVENTORY_TOPIC`，group=inventory-deduct-consumer-group

三重幂等保证：

1. **消费者层**：`MessageIdempotentHelper.isFirstProcess(msgId, 24h)` — 快速去重
2. **Mapper 层**：乐观锁 WHERE 条件（`available >= qty` 等）
3. **对账层**：L3 最终以 Redis 为准修正

PRE_DEDUCT 特有的 **退避重试**（3 次，50ms/100ms 间隔）：
- 原因：预扣减使用 `WHERE available_stock >= quantity` 乐观锁，并发高时可能竞争失败
- 3 次重试后仍失败：不抛异常（不触发 MQ 重投），等待 L3 对账修复
- CONFIRM/RELEASE 不重试：乐观锁条件 `locked_stock >= quantity` 在正常流程下必定满足

**MQ 消息乱序风险**：如果 CONFIRM 先于 PRE_DEDUCT 到达 Consumer（MQ 分区内有序但跨分区无序），`confirmDeduct` 的 `WHERE locked_stock >= quantity` 可能因 locked_stock=0 而失败。此时 log.warn + 依赖 L3 对账修复。概率极低但存在。

### 4.7 对账修复 — InventoryReconcileJob

XXL-Job Handler: `inventoryReconcileJob`，Cron: `0 0 3 * * ?`（凌晨 3 点）

```
for each SKU in MySQL WHERE deleted=0:
    1. GET inventory:{skuId}:total → nil 则跳过
    2. redisTotal != mysql.available → UPDATE mysql SET available=redisTotal
    3. 分桶校验：∑bucket[i] vs total → 不一致以分桶和为准修正 total
```

**潜在风险**：凌晨 3 点对账时若有积压的 L2 MQ 消息，以 Redis 为准修复后 MQ 消费可能导致多扣。概率极低（凌晨 MQ 积压罕见），且下一次对账会再修复。

### 4.8 预扣超时回退 — PreDeductTimeoutJob

`@Scheduled(fixedRate=300000)` 每 5 分钟执行，Redisson 分布式锁（tryLock(0, 240s)）

```
1. SCAN inventory:prededuct:*
2. GET TTL → TTL <= 0（已过期 TTL=0，或不存在 TTL=-2）
3. HGETALL → 过滤 :bucket 辅助字段
4. release.lua 原子回退（防止与用户主动释放双重回退）
```

**为什么必须主动扫描而非依赖 Redis Key 过期？**

Redis Key 过期是惰性删除 + 定期删除。如果 `inventory:prededuct:{orderId}` 过期后没有任何访问，可能长时间不被物理删除。此期间的库存处于"幽灵锁定"状态——Redis total 已经扣了，但 order 不会来 confirm/release。主动扫描发现 TTL ≤ 0 的记录并强制 release.lua 回退。

**为什么 release.lua 而非 HGETALL + INCRBY + DEL？** 分步操作在 HGETALL 和 DEL 之间有竞态窗口：用户主动 releaseStock 和定时回退可能同时操作同一个 orderId，导致双重回退（库存凭空增加）。Lua 的 HGET + HDEL 原子性保证只有一个操作成功。

---

## 5. 中间件交互

### 5.1 Redis — 3 个 Lua 脚本 + Pipeline

| 脚本 | 路径 | 操作 Key 数量 | 返回值语义 |
|------|------|:--:|------|
| `prededuct.lua` | `resources/lua/prededuct.lua` | 2 + bucketCount | 1 成功 / 0 不足 / -1 重复 / -2 未初始化 |
| `release.lua` | `resources/lua/release.lua` | 3 (total, prededuct, bucket) | >0 释放数量 / 0 不存在 |
| `confirm.lua` | `resources/lua/confirm.lua` | 1 (prededuct) | >0 确认数量 / 0 不存在 |

**Spring Bean 预加载**：`RedisScriptConfig` 将 3 个脚本注册为 `DefaultRedisScript<Long>` Bean，避免每次执行时重复加载解析。Redis 会缓存脚本 SHA1，后续走 EVALSHA。

**Pipeline 使用场景**：
- `reloadStockToRedis`：Canal 删除缓存后的回填（total + buckets + bucketCount 批量 SET）
- `resizeBuckets`：热点扩容时的桶重分配（新桶 SET + 旧桶 DEL + total SET + bucketCount SET）

### 5.2 RocketMQ — 3 个 Consumer + 1 个 Producer

| 角色 | Topic | Group | 说明 |
|------|-------|------|------|
| Producer | `INVENTORY_TOPIC` | inventory-producer-group | syncSend，超时 3s，失败回滚 Redis |
| Consumer | `INVENTORY_TOPIC` | inventory-deduct-consumer-group | L2 扣 MySQL，msgId 幂等，PRE_DEDUCT 3 次退避 |
| Consumer | `INVENTORY_CACHE_TOPIC` | inventory-cache-evict-consumer-group | Canal 缓存失效，es 版本防乱序 |
| Consumer | `ORDER_TRANSACTION_TOPIC` | inventory-order-transaction-consumer-group | 订单事务消息，逐 SKU 预扣 |

**消息 Tag**：`INVENTORY_TOPIC:PRE_DEDUCT`, `INVENTORY_TOPIC:CONFIRM`, `INVENTORY_TOPIC:RELEASE`

**OrderTransactionConsumer 幂等键选择**：

使用 `msgId` 而非 `orderNo` 的原因（源码注释明确）：
- 如果幂等标记在循环前设置（orderNo），实例崩溃后新实例因 orderNo 已存在幂等标记而跳过全部 SKU
- 使用 msgId：每个 SKU 处理失败抛异常 → MQ 重投 → 新的 msgId → 可重新处理未成功的 SKU

`pseudoOrderId` 的设计：`orderNo.hashCode() & 0x7FFFFFFF` — 保证非负 long 用于 prededuct.lua 的幂等键。

### 5.3 Canal — Binlog 缓存一致性

```
MySQL t_inventory UPDATE
    → Canal binlog 监听（instance: inventory_instance）
        → RocketMQ INVENTORY_CACHE_TOPIC
            → InventoryCacheEvictConsumer
                → 版本号检查（es 防乱序）
                    → SCAN DEL inventory:{skuId}:bucket:*
                    → DEL inventory:{skuId}:total
                    → DEL inventory:bucket:count:{skuId}
```

**版本号防乱序机制**（`inventory:canal:version:{skuId}`）：

```lua
-- Lua 原子版本比较
local currentVersion = redis.call('GET', KEYS[1]) or '0'
if tonumber(ARGV[1]) > tonumber(currentVersion) then
    redis.call('SETEX', KEYS[1], ttl, ARGV[1])
    return 1  -- 可以删除缓存
else
    return 0  -- 旧消息，跳过
end
```

**为什么删缓存而非更新缓存？**
1. Canal 消息可能乱序，直接 SET 可能将新值覆盖为旧值
2. 删除是幂等的
3. Cache-Aside：下一个读请求从 MySQL 回填最新值
4. 即使回填后 MySQL 又有新变更，Canal 会再次触发删除

### 5.4 XXL-Job

| Job | Handler | 调度 | 分布式协调 | 说明 |
|-----|---------|------|-----------|------|
| `InventoryReconcileJob` | `@XxlJob("inventoryReconcileJob")` | Cron 凌晨 3 点 | Admin 保证单实例执行 | Redis→MySQL 对账 |
| `PreDeductTimeoutJob` | `@Scheduled(fixedRate=300000)` | 5 分钟 | Redisson tryLock(0, 240s) | 过期预扣回退 |

### 5.5 Nacos — 服务注册

- 注册名：`my-xhs-inventory`
- namespace: `my-xhs`, group: `DEFAULT_GROUP`
- 被 order/home 的 `@FeignClient` 调用

### 5.6 Sentinel

- Dashboard: `21.130.247.89:8858`
- Client port: 8728
- Feign Sentinel 已启用

---

## 6. 配置

### 6.1 关键业务配置（application.yml）

```yaml
inventory:
  bucket:
    default-count: 2      # 普通 SKU 默认分桶数
    hot-count: 8           # 热点 SKU 分桶数
  prededuct:
    expire-seconds: 1800   # 预扣记录过期时间（30分钟）

server:
  port: 19009
  tomcat:
    threads:
      max: 100             # 锁竞争场景，Tomcat 线程数低配
```

### 6.2 数据库（application-datasource.properties）

```
Master: 21.130.247.89:13309/my_xhs_inventory
Slave:  21.130.247.89:13313/my_xhs_inventory
```

### 6.3 线程池

**Tomcat**：max=100, min-spare=10（库存是锁竞争密集场景，高线程数反而增加竞争）

**异步扩容线程池**：core=2, max=4, queue=50, CallerRunsPolicy

```
为什么不是默认的 AbortPolicy？
- 扩容是优化手段（非核心链路），丢了也没关系
- CallerRunsPolicy 让调用线程自己执行扩容，最多阻塞一个预扣请求
- 比 AbortPolicy 更安全：不会因队列满而丢任务
```

### 6.4 自动装配排除

排除 5 个不需要的自动装配以减少内存和启动时间：Security, Batch, Quartz, WebSocket, JMX

---

## 7. 热点检测 + 动态扩容

### 7.1 HotSkuDetector — ZSet 滑动窗口

```
ZSet Key: inventory:hot:window:{skuId}
窗口: 10 秒
阈值: >= 100 次请求

每个预扣请求:
1. ZADD  {nowSec}:{threadId}:{nanoTime} → score=nowSec
2. ZREMRANGEBYSCORE 0 {nowSec-10} （清窗口外数据）
3. ZCARD → >= 100 则判定热点
4. EXPIRE 30s （冷 SKU 的 Key 自动清理）
```

**内存优化**：使用秒级时间戳 + 线程 ID + 纳秒作为 member 后缀（而非毫秒级），大幅减少 ZSet member 数量。同秒内多次请求会因不同 threadId/nanoTime 而作为不同 member 计入 ZCARD。

### 7.2 平滑扩容 — resizeBuckets

```
触发条件：HotSkuDetector 判定热点 AND 当前桶数 < 8

流程：
1. SETNX inventory:resize:{skuId} = 1（10s TTL，分布式锁）
2. 设置 inventory:paused:{skuId} = 1（30s TTL，暂停预扣标记）
3. GET 各桶库存 → 求和 totalStock
4. Pipeline 写入新桶（perBucket + remainder）→ 删除多余旧桶
5. 更新 bucketCount Key + total Key
6. DELETE paused Key（恢复预扣）
7. DELETE resize Key（释放锁）
```

**暂停窗口的影响**：暂停标记存在时，preDeduct 抛 RuntimeException → 由 MQ Consumer（OrderTransactionConsumer）重试兜底。

---

## 8. 异常处理

| 场景 | 处理方式 | 最终一致性 |
|------|---------|-----------|
| Redis 库存不足 | 返回 `ResultCode.STOCK_NOT_ENOUGH` | — |
| 库存未初始化 | 返回参数错误 | — |
| MQ 发送失败 | release.lua 回滚 Redis | 回滚失败则等 5 分钟超时回退或凌晨对账 |
| MQ 消费失败（PRE_DEDUCT） | 3 次退避重试 | 重试耗尽等 L3 对账 |
| MQ 消费失败（CONFIRM/RELEASE） | 不重试，log.warn | 等 L3 对账 |
| 预扣超时 | PreDeductTimeoutJob 主动回退 | release.lua 原子保证 |
| Canal 版本降级（乱序） | Lua 版本号比较跳过 | 下次新版本消息覆盖 |
| 重复预扣（幂等） | prededuct.lua 返回 -1，不报错 | — |
| TCC 悬挂 | Fence status=3 拒绝 Try | — |
| TCC 空回滚 | cancelFence INSERT status=3 | — |
| TCC Confirm 重复 | confirmFence 幂等放行 | — |
| TCC Cancel 覆盖 Confirm | cancelFence 拒绝（status=2 不可取消） | — |
| 扩容中预扣 | throw RuntimeException | MQ 重试兜底 |

---

## 9. 安全

| 措施 | 实现 |
|------|------|
| 单次扣减上限 | `@Max(999)` 防止恶意请求一次扣空所有库存 |
| init 限流 | `@RateLimit(5/60s)` 防止脚本重放 |
| reinit 限流 | `@RateLimit(2/60s)` 防止管理操作滥用 |
| 乐观锁 | 所有 Mapper SQL 带 `WHERE available >= qty` 等条件 |
| 分布式锁 | Redisson tryLock — resize + 预扣超时任务 |
| SETNX 幂等 | initStock 用 `setIfAbsent` 防止并发重复初始化 |

---

## 10. 跨模块对比

### 10.1 vs counter 模块的 Buffer-Trigger 模式

| 维度 | counter | inventory |
|------|---------|-----------|
| 扣减路径 | INCR/DECR → Buffer → 定时刷盘 | Lua 原子 DECRBY → MQ 同步发 → Consumer 扣 DB |
| 一致性 | 最终一致（Buffer 攒批可能丢 5 分钟） | 最终一致（MQ 同步发失败即回滚，极端情况 24h 对账） |
| 热点处理 | 无分桶（counter 操作天然分散到不同 target） | 分桶（2→8）+ 滑动窗口检测热点 |
| 死信 | 无 MQ，Buffer 失败 3 次重试 | MQ retry 5 次 + 死信队列 |

### 10.2 vs product 模块的 Cache-Aside

| 维度 | product | inventory |
|------|---------|-----------|
| 缓存模式 | 多级缓存（Caffeine + Redis + Canal） | Cache-Aside（Redis + Canal 失效 + MySQL 回填） |
| 写一致性 | 延迟双删（500ms 窗口） | Canal 主动失效（无窗口） |
| 为什么不同？ | product 读多写少，500ms 不一致可接受 | inventory 写多且强一致要求（库存不准=超卖/少卖），必须 Canal |

### 10.3 vs cart 模块的 Lua 脚本

| 维度 | cart | inventory |
|------|------|-----------|
| 脚本数量 | 3 个（addItem / removeItem / mergeItems） | 3 个（prededuct / release / confirm） |
| 操作复杂度 | 购物车 CRUD（HSET/HDEL/HGETALL） | 库存算术（DECRBY/INCRBY/+ 桶间遍历） |
| Cluster 兼容 | KEYS 传入所有 Key | KEYS 传入所有 Key（M14 修复前动态拼接失败） |
| hash tag | 无（单 Key 操作） | `{skuId}` 保证 total+bucket 同 slot |

---

## 11. 已知问题（源码验证）

### 11.1 getStock 回填与分桶数的一致性问题

**源码位置**：`InventoryService.java:413-468`

`getStock` 回填时使用 `inventory.getAvailableStock()`（MySQL 值）重新分桶。但 MySQL 的 `available_stock` 是 L2 消费后更新的，可能与 Redis 当前值不一致。

**分析**：这是 Canal 缓存失效路径的设计选择——Canal 删除缓存后，读请求从 MySQL 回填。如果回填后 Canal 再次触发删除（新一轮更新），会自然修正。

### 11.2 reinitStock 忽略 freezing_stock

**源码位置**：`InventoryService.java:661-694`

```java
int totalStock = inventory.getAvailableStock() + inventory.getLockedStock();
```

当 TCC 路径有活跃的 Try（freezing_stock > 0）时，reinit 仅计算 `available + locked`，忽略 `freezing`。导致 Redis 重新初始化后多出 freezing 对应的库存量。

**严重程度**：仅管理后台手动操作，且 TCC 路径与 L1/L2 路径互斥，实际场景概率极低。

### 11.3 OrderTransactionConsumer 中异常吞并

**源码位置**：`OrderTransactionConsumer.java:83-103`

```java
for (JsonNode item : skuItems) {
    try {
        inventoryService.preDeduct(preDeductRequest);
    } catch (Exception e) {
        throw new RuntimeException("库存预扣减失败: skuId=" + skuId, e);
    }
}
```

第 1 个 SKU 预扣成功，第 2 个 SKU 失败时：第 1 个 SKU 已扣 Redis（不可逆，预扣记录存在），但整个消息重试后第 1 个 SKU 的 prededuct.lua 会因幂等返回 -1（不报错）。不会导致多扣，但第 1 个 SKU 的 L2 MQ 事件已发送，Consumer 可能重复消费。Consumer 有 msgId 去重保护。

### 11.4 TCC 三方法缺少 deleted=0 条件

**源码位置**：`InventoryMapper.java:52-71`

`tryFreeze`, `confirmFreeze`, `cancelFreeze` 三个 TCC Mapper 方法的 WHERE 条件均缺少 `AND deleted=0`（对比 L1/L2 的 `deductStock`/`confirmDeduct`/`releaseStock` 都有此条件）。这意味着逻辑删除的 SKU 仍可能被 TCC Try→Confirm→Cancel 操作。

**原因**：TCC 路径的乐观锁条件仅校验 `available >= qty` 或 `freezing >= qty`，未考虑逻辑删除标记。严重程度低：逻辑删除的 SKU 不会再被下单，`available_stock` 为 0 时 `tryFreeze` 的乐观锁会自动拦截。

### 11.5 confirm/release MQ fire-and-forget 风险

**源码位置**：`InventoryService.java:329,388`

```java
// confirm: fire-and-forget（不检查返回值）
sendInventoryEvent(orderId, skuId, quantity, "CONFIRM");

// release: 同样 fire-and-forget
sendInventoryEvent(orderId, skuId, quantity, "RELEASE");
```

confirm 和 release 的 MQ 发送使用 fire-and-forget 模式：虽然内部是 `syncSend`，但调用方不检查返回值。如果 MQ 发送失败，Redis 层已完成操作（confirm.lua 删除了预扣记录，或 release.lua 回退了库存），但 MySQL 永远不会更新（`locked_stock` 不减少或不回退）。最终依赖 L3 对账修复。

**与 preDeduct 的区别**：preDeduct 的 syncSend 失败会立即 `rollbackPreDeduct()` 回滚 Redis。confirm/release 选择 fire-and-forget 的原因是这两步已在业务上闭环（预扣记录的删除/回退已原子完成），MySQL 更新是延后持久化，容忍短暂不一致。

### 11.6 releaseStock Javadoc 过时（与实现不一致）

**源码位置**：`InventoryService.java:336-346`

```java
// Javadoc 声称：
// "Redis 层面：预扣的库存回退到桶0 + 总库存回退 + 删除预扣记录"
// "为什么回退到桶0而不是原来的桶？"

// 实际代码（M12 修复后）：HGET :bucket 获取来源桶 → 精准回退
Object bucketNoObj = stringRedisTemplate.opsForHash()
    .get(predeductKey, fieldName + ":bucket");
int bucketNo = bucketNoObj != null ? Integer.parseInt(bucketNoObj.toString()) : 0;
```

Javadoc 声称回退到"桶 0"（注释说"预扣记录中没有记录从哪个桶扣的"），但在 M12 修复后，preDeduct 已通过 `HSET {skuId}:bucket bucketNo` 记录来源桶，release 时 HGET 后精准回退。Javadoc 未同步更新。fallback: `:bucket` 不存在时默认回退到桶 0。



---

## 12. 总结

**架构特征**：

| 维度 | 描述 |
|------|------|
| 扣减模式 | 三级保证：L1 Redis Lua 分桶预扣 → L2 MQ 异步扣 MySQL → L3 定时对账 |
| 并发控制 | Redis Lua 原子（单线程天然无锁）+ MySQL 乐观锁 |
| 热点解决 | ZSet 滑动窗口检测 → 异步扩容 2→8 桶 |
| 缓存一致性 | Canal binlog → 主动删缓存 → Cache-Aside 回填（vs product 延迟双删） |
| 一致性强度 | 最终一致性（高概率即时 + L3 凌晨对账兜底） |
| 分布式事务 | 两套路径：L1/L2（预扣+释放镜像操作） \| TCC（Fence 表 + 3 阶段状态机） |
| 服务粒度 | 无直接 Feign 调用其他服务，只作为被调用方 |
| MQ Producer 策略 | 同步发送（失败回滚 Redis）而非异步 |
| 源码行数 | InventoryService 695 行（核心） |
