# 08 — 库存业务全景

> **前置阅读**：[架构文档](01-inventory-module.md) · [03-分桶预扣减](03-bucket-pre-deduct.md) · [05-TCC 分布式事务](05-tcc-fence.md) · [07-Canal 缓存一致性](07-canal-cache.md)
> **测试验证**：[测试 1-9](02-inventory-test-record.md) — 全部 9/9 通过
> **深度文档索引**：03 分桶 · 04 Lua · 05 TCC · 06 MQ · 07 Canal

## 业务定位：库存服务在全链路中的位置

```
用户视角的完整流程：

  浏览商品(product) → 加购物车(cart) → 下单(order) → 支付(payment) → 发货
                              │              │              │
                              │    order 调用 inventory     │
                              │    ├─ preDeduct (L1/L2)    │
                              │    └─ tccTry (TCC)         │
                              │              │              │
                              │         30分钟窗口           │
                              │     ┌──────┴──────┐         │
                              │ 支付成功        超时未付     │
                              │     │              │         │
                              │  confirm       release/Cancel│
                              │     │              │         │
                              │ locked→0    available恢复    │
```

库存服务不面向用户——它是被 order 服务调用的**内部服务**。用户感知不到库存扣减的存在，但库存扣减失败会直接阻塞下单。

**业务的强约束**：库存不准 = 超卖（用户付了钱但没货）或 少卖（有货但告诉用户缺货）。超卖是不可接受的——会触发退款、补偿、客服成本。少卖虽然可接受但损害营收。

---

## 全链路端到端时序

### 正常下单路径（L1/L2）

```
T0    用户点击"下单"
T0+1  order → Feign → inventory.preDeduct(skuId=999, qty=3)
        │
        ├── L1: prededuct.lua (Redis, ~1ms)
        │      DECRBY bucket:2 3, DECRBY total 3, HSET prededuct, EXPIRE 1800
        │
        ├── L2: syncSend INVENTORY_TOPIC:PRE_DEDUCT (~20ms)
        │      RocketMQ 确认投递 → 返回 200 给 order
        │
T0+250ms  order 返回给用户 "下单成功"
T0+13s   InventoryDeductConsumer 消费 PRE_DEDUCT
           UPDATE available -= 3, locked += 3 WHERE available >= 3 (attempt=1)
T0+?     用户支付 → order → inventory.confirm(orderId)
           confirm.lua HDEL prededuct → MQ CONFIRM → locked -= 3

最终状态：
  Redis: total-3 (已扣)
  MySQL: available-3, locked=0 (confirmed)
```

### 超时未支付路径

```
T0     下单成功，预扣 3 件
T0+30m  30 分钟窗口到期
           │
           ├── PreDeductTimeoutJob SCAN inventory:prededuct:*
           │      TTL ≤ 0 → release.lua (INCRBY total+3, bucket+3, HDEL)
           │      日志: "回退库存: predeductKey=inventory:prededuct:777001, skuId=999, qty=3"
           │
           └── 用户再次浏览商品 → 库存已恢复 → 可继续下单
```

### TCC 路径（多服务协调）

```
order 服务
  │
  ├── inventory.tccTry("tx:001", 3001, [{999, 10}])
  │     Fence INSERT status=1 → tryFreeze: available-10, freezing+10 → 返回 true
  │
  ├── coupon.tccTry("tx:001", ...)
  │     锁定优惠券 → 返回 true
  │
  ├── payment.check("tx:001")
  │     │
  │     ├── 全部 Success → inventory.tccConfirm("tx:001", 3001)
  │     │     Fence UPDATE status=1→2 → confirmFreeze: freezing-10
  │     │
  │     └── 任一 Fail → inventory.tccCancel("tx:001", 3001)
  │           Fence UPDATE status=1→3 → cancelFreeze: freezing-10, available+10
```

---

## 跨模块交互矩阵

### Inventory 作为被调用方（Feign）

| 调用方 | 接口 | 场景 | 一致性需求 |
|------|------|------|:--:|
| order | preDeduct | 下单时预扣 | 最终一致（L3 兜底） |
| order | confirm | 支付成功确认 | 最终一致 |
| order | release | 取消订单释放 | 最终一致 |
| order | tccTry | TCC 冻结 | 强一致（Fence） |
| order | tccConfirm | TCC 确认 | 强一致 |
| order | tccCancel | TCC 取消 | 强一致 |
| home (BFF) | stock | 商品详情展示 | 读缓存 |

### Inventory 对外调用

| 调用 | 方式 | 说明 |
|------|:--:|------|
| 无 @FeignClient | — | inventory 不主动调用任何服务 |
| MQ 生产 | syncSend → INVENTORY_TOPIC | L2 异步落 MySQL |
| Canal 消费 | RocketMQ → INVENTORY_CACHE_TOPIC | 缓存失效 |

inventory 是纯服务提供方——不依赖其他业务服务。自我完备的设计减少了故障传播链。

---

## 故障模式矩阵

| 组件故障 | 预扣减 | 确认 | 释放 | 库存查询 | TCC Try/Confirm/Cancel |
|------|:--:|:--:|:--:|:--:|:--:|
| **Redis 宕机** | ❌ 500 | ❌ 500 | ❌ 500 | ✅ MySQL 兜底 | ✅ 不依赖 Redis |
| **MySQL 宕机** | ✅ Redis 正常 | ⚠️ MQ 消息积压 | ⚠️ MQ 消息积压 | ❌ 500 | ❌ 500 |
| **RocketMQ 宕机** | ❌ syncSend 超时→回滚 Redis | ✅ fire-and-forget（但不落库） | ✅ fire-and-forget | ✅ 不受影响 | ✅ 不依赖 MQ |
| **Nacos 宕机** | ✅ 已有缓存 | ✅ | ✅ | ✅ | ✅ |
| **Canal 宕机** | ✅ 不受影响 | ✅ | ✅ | ⚠️ 缓存不刷新（MySQL 兜底） | ✅ |
| **XXL-Job 宕机** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **L1 Lua 执行中 OOM** | ❌ 全部回滚 | N/A | N/A | N/A | N/A |

**恢复路径**：

| 故障 | 恢复方式 | 恢复时间 |
|------|------|:--:|
| Redis 宕机 | Sentinel 自动 failover → 新主接受写入 | 秒级 |
| MQ 消息积压 | Consumer 恢复后批量消费 + 乐观锁重试 | 分钟后 |
| MySQL 宕机 | 主从切换（13309→13313） | 秒级 |
| L1→L2 不一致 | L3 凌晨对账以 Redis 为准修复 | 12-24h |
| TCC Fence 不一致 | Fence 表持久化 → 手动重放 Confirm/Cancel | 分钟 |

---

## 一致性边界分析

### L1/L2 路径的一致性窗口

```
时刻 T0: Redis DECRBY total → 用户看到扣减结果
时刻 T0+13s: MQ 消费 → MySQL available -= qty
时刻 T0+13s 至 凌晨 3 点: Redis↔MySQL 不一致窗口
凌晨 3 点: L3 对账 → 以 Redis 为准修复 MySQL

最坏情况：MQ 消息丢失 → Redis 少了，MySQL 没少 → 凌晨对账时强制 UPDATE MySQL
```

**为什么会存在不一致窗口？** L1 追求毫秒级响应（Redis），L2 追求持久化（MySQL）。两者之间用 MQ 桥接——MQ 的投递延迟（~13s）就是不一致窗口。如果 MQ 消息永久丢失（DLQ 满），不一致窗口延长到凌晨对账（最长 24 小时）。

### TCC 路径的一致性边界

TCC 没有 MQ 桥接——Try→Confirm/Cancel 直接操作 MySQL，与调用方 order 的 `@Transactional` 在同一数据库事务上下文中。Fence 表的唯一键 `(xid, branch_id)` 保证幂等、防悬挂、空回滚。

TCC 的不一致窗口只存在于 order 服务的协调层——如果 order 崩溃在 Confirm 和 Cancel 之间，Fence 表持久化了状态的中间值（status=1），后续可以通过重放 Confirm 或 Cancel 恢复。

### 对账的作用范围

L3 对账只修复 L1/L2 路径的不一致——比对 `Redis total` 和 `MySQL available_stock`。以下场景不在对账范围内：

1. **TCC 冻库**（`freezing_stock`）：不由 L3 处理，Fence 表自己管理状态
2. **L1/L2 路径中的 locked_stock**：对账只看 `available`，不检查 `locked` 的准确性
3. **分桶分布偏移**：对账只检查分桶和 = total，不检查桶间是否均匀

---

## 两条路径的完整对比

| 维度 | L1/L2 路径 | TCC 路径 |
|------|------|------|
| **触发方式** | order Feign → preDeduct | order 编排 → tccTry |
| **资源锁定** | Redis DECRBY（毫秒级） | MySQL available→freezing（事务级） |
| **持久化** | MQ 异步 → MySQL（~13s 延迟） | MySQL 同步（事务内） |
| **回滚** | release.lua INCRBY | cancelFreeze available+freezing- |
| **一致性** | 最终一致（L3 对账） | 强一致（Fence 表） |
| **并发能力** | Redis Lua 分桶（10w+ QPS） | MySQL 行锁（<1w QPS） |
| **适用场景** | 秒杀、高并发下单 | 多服务协调（订单+库存+券+支付） |
| **故障影响** | MQ 丢失→24h 内对账修复 | Fence 表持久化→手动重放恢复 |
| **数据字段** | available_stock + locked_stock | available_stock + freezing_stock |

**选择原则**：

- 如果只是单服务扣减库存（不需要与其他服务协调），用 L1/L2——性能最优
- 如果需要与 coupon/payment 等服务协调（全部成功或全部回滚），用 TCC——一致性最强
- **不能混用**：同一个 SKU 不能同时走 L1/L2 和 TCC——两者的 `available_stock` 互相竞争 MySQL 行锁，但互不感知对方的业务状态

---

## 发散：与其他库存扣减方案的对比

### vs 淘宝早期的"下单减库存"

淘宝早期采用下单即扣库存（不分离预扣和确认）。好处是简单，问题是恶意用户可通过反复下单-取消占用库存——其他正常用户看到库存为 0。

my-xhs 的 30 分钟预扣窗口 + PreDeductTimeoutJob 主动回退是这个问题的解决方案——恶意用户的订单 30 分钟后自动回退，库存回到可用池。

### vs 京东的"支付减库存"

京东采用支付成功后才扣库存。好处是不会有"已下单但未支付"的库存占用，问题是用户体验——下单时看到有货但支付时提示缺货。

my-xhs 的预扣模式在下单时就"锁定"库存——用户不会遇到支付时才缺货的尴尬。代价是 30 分钟内库存被"冻结"。

### vs 拼多多的分桶库存

拼多多的库存系统也使用分桶预扣（类似 my-xhs 的 L1/L2），但在分桶数量上更激进——热点 SKU 可能分 100+ 个桶，每个桶独立扣减，单 Key QPS 极低。

my-xhs 的热桶上限是 8——平衡了并发能力和 Lua 脚本复杂度（KEYS 参数不能太多）。如果需要更大并发，应该升级到多 Redis 节点（Redis Cluster）而非增加单节点桶数。

---

## 完整数据流一览

```
┌─────────────────────────────────────────────────────────────────┐
│                        Inventory 数据流                          │
├───────────────┬─────────────────┬──────────────────────────────┤
│   Redis       │      MQ         │      MySQL                   │
│ (16379/16381) │  (RocketMQ)     │    (13309/13313)              │
├───────────────┼─────────────────┼──────────────────────────────┤
│ total         │                 │ t_inventory                  │
│ bucket:N      │ INVENTORY_TOPIC │  ├── available_stock         │
│ prededuct:{}  │  ├── PRE_DEDUCT │  ├── locked_stock            │
│ bucket:count  │  ├── CONFIRM    │  ├── freezing_stock          │
│ hot:window    │  └── RELEASE    │  └── deleted                 │
│ paused:{}     │                 │                              │
│ resize:{}     │ INVENTORY_      │ t_tcc_fence                  │
│               │   CACHE_TOPIC   │  ├── xid                     │
│               │  └── Canal→     │  ├── branch_id               │
│               │      evict cache│  ├── action_name             │
│               │                 │  └── status (1/2/3)          │
│               │ ORDER_          │                              │
│               │  TRANSACTION_   │ Canal binlog                 │
│               │  TOPIC          │  └── inventory_instance      │
│               │  └── 订单事务    │      监听 t_inventory         │
└───────────────┴─────────────────┴──────────────────────────────┘

一致性保证：
  Redis ↔ MySQL: L3 凌晨对账（inventoryReconcileJob, XXL-Job）
  Redis ↔ Cache: Canal 缓存失效（InventoryCacheEvictConsumer）
  预扣超时: PreDeductTimeoutJob (@Scheduled 5min, Redisson 分布式锁)
  分桶完整性: reconcileBuckets (∑bucket == total)
```

---

## 生产运维 Checklist

| 检查项 | 工具 | 频率 | 告警阈值 |
|------|:--:|:--:|------|
| Redis↔MySQL 差异 | XXL-Job inventoryReconcileJob | 每天凌晨 3 点 | 差异 > 0 |
| MQ 消费延迟 | DLQ Metrics | 实时 | 延迟 > 30s |
| 预扣超时记录 | PreDeductTimeoutJob 日志 | 每 5 分钟 | 回退 > 10 条 |
| Canal 版本号 Key 数量 | Redis DBSIZE | 每周 | 增长 > 10000 |
| 热点 SKU 数量 | HotSkuDetector ZCARD | 实时 | — |
| 分桶扩容事件 | resizeBuckets 日志 | 实时 | 扩容频率 > 10/分钟 |
| TCC Fence 悬挂记录 | SQL COUNT status=3 | 每天 | 悬挂 > 0 |
| Redis 内存使用 | redis-cli INFO memory | 实时 | > 80% maxmemory |
