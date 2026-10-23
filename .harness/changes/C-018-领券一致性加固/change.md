---
id: C-018
slug: 领券一致性加固
status: planned
created: 2026-10-23
updated: 2026-10-23
iteration: ITER-018
relations:
  relates_to:
    - slug: C-007
      reason: Design B/Outbox 基线
    - slug: C-017
      reason: 分片/规模化基线
---

# C-018 领券一致性加固（规划）

> 目标：彻底关闭 C-017 review 暴露的两个窗口：
> ① Redis stock key 丢失时"用异步账本重建"可能补高（超发方向）；
> ② Lua 扣减成功、Outbox 写入前崩溃（少卖 + 用户丢券）。
> 约束：无运行时验证（编译+单测）；Redis Cluster；不改变 01–09 文章口径。

---

## 0. 不变量（先定标准）

对每个模板 `T`：

```
total(T)        = 模板发放总量
issued(T)       = COUNT(t_user_coupon WHERE coupon_id=T)          // 持久事实，只增不减
in_flight(T)    = 已扣 Redis 库存、但还没有 durable Outbox 的领取
unconsumed(T)   = 已写 Outbox、但消费者还没落 user_coupon 的领取
stock(T)        = total(T) − issued(T) − in_flight(T) − unconsumed(T)
```

**安全方向**：任何不确定时，宁可 `stock` 偏小（少卖），绝不允许偏大（超发）。

---

## 1. 问题与根因

| 问题 | 根因 |
|---|---|
| `-3` 重建补高 | `remain_count` 是异步账本，消费者滞后时偏高；Redis key 丢失后用它重建 = 超发 |
| Lua→Outbox 崩溃 | stock/claimed 已扣，但领取事件尚未持久化；进程崩溃后无人知道这次扣减 |

---

## 2. 方案总览

### 2.1 领取意图：Lua 原子写"pending 标记"

Lua 新增一个 **ZSET**（与 stock/claimed 同 slot）：

```
KEYS[1] myxhs:coupon:{T}:stock
KEYS[2] myxhs:coupon:{T}:claimed:{userId}
KEYS[3] myxhs:coupon:{T}:claim:pending      // ZSET: member=userId:claimNo, score=nowMs
KEYS[4] myxhs:coupon:{T}:rebuild            // "1"=重建中
ARGV[1] perUserLimit
ARGV[2] userId:claimNo
ARGV[3] nowMs
```

Lua 逻辑：

```
GET rebuild -> 若=1 返回 -4（重建中）
GET stock -> 不存在 -3；<=0 -1
GET claimed >= limit -> -2
DECR stock; INCR claimed
ZADD pending nowMs member          // 与扣减同一原子操作
return 1
```

Java 侧：

```
Lua 返回 1
→ SET myxhs:coupon:claim:writing:{claimNo}=1 EX 60   // 防恢复任务与 Outbox 写入竞态
→ 写 Outbox（Design B 原逻辑）
→ 成功：ZREM pending member；DEL writing
→ 双次失败：return_coupon.lua 回滚 + ZREM + DEL writing + 报错
```

### 2.2 恢复任务：处理"扣了库存但没 Outbox"的意图

`CouponClaimPendingRecoveryJob`（XXL-Job，每分钟）：

```
SCAN myxhs:coupon:*:claim:pending
→ ZRANGEBYSCORE key -inf (now-5min) LIMIT 100
→ 对每个 member(userId:claimNo)：
    writing 键存在 → 跳过（写入中）
    Outbox 存在(claimNo) → ZREM（已持久化，交给补发/消费）
    Outbox 不存在 → return_coupon.lua 回滚 stock/claimed + ZREM + 指标告警
```

- 5 分钟 + writing 键 60s，覆盖"Outbox 写入慢/崩溃"；
- 回滚使用现有 `return_coupon.lua`（INCR stock + DECR claimed）。

### 2.3 Outbox 消费标记：`consumed_at`

新增字段 `t_coupon_outbox.consumed_at DATETIME NULL`：

- 消费者在 `user_coupon` 落库后（含 duplicate 路径）更新 `consumed_at=NOW()`；
- 这是"unconsumed(T)"的唯一依据，用于重建排空判断；
- 跨库（user_coupon 分片 / outbox ds_0）不能同事务：先落库、再标记；标记失败由重建等待/告警兜底（安全方向）。

### 2.4 受控重建：不再用 `remain_count` 自动补

`CouponStockRebuildService.rebuild(T)`：

```
1. SETNX myxhs:coupon:{T}:rebuild=token EX 120   // Lua 对新领取返回 -4
2. 先跑一次"pending 恢复"（处理崩溃意图）
3. 排空：ZCARD pending=0 且 COUNT(outbox WHERE template_id=T AND consumed_at IS NULL AND created_at>now-1h)=0
   （轮询，最长 30s；超时=失败，保持 rebuild 标记/告警）
4. issued = COUNT(t_user_coupon WHERE coupon_id=T)
5. stock = max(0, total(T) − issued)
6. SET stockKey=stock（覆盖，不再 setIfAbsent）
7. 可选：update remain_count=stock（对账也会修）
8. DEL rebuild；失败则保留 rebuild（继续 -4）+ 告警
```

触发方式：

- `claimCoupon` 收到 `-3`（stock 不存在）时：
  - `SADD myxhs:coupon:stock:rebuild {T}`，返回 `-4`（"活动太火爆/初始化中"）；
  - `CouponStockRebuildJob` 每分钟消费该 SET，调用 rebuild；
- 不再在请求线程里用 `remain_count` 自愈。

**为什么彻底**：重建前 fence（-4）+ 排空 pending/unconsumed + 用持久 `issued` 计算，三个条件同时满足才写 stock；任何不确定就拒绝重建（继续 -4 + 告警），绝不猜。

---

## 3. 状态机与返回码

| 返回 | 含义 | 客户端 |
|---|---|---|
| 1 | 成功 | 成功 |
| -1 | 售罄 | 售罄 |
| -2 | 达限领 | 限领 |
| -3 | 库存未初始化 | 触发重建，返回"稍后" |
| **-4** | 重建中/排空中 | "活动太火爆，请稍后" |

---

## 4. 变更清单

| 层 | 文件 | 变更 |
|---|---|---|
| Lua | `claim_coupon.lua` | 加 pending ZSET + rebuild 检查 |
| Lua | `return_coupon.lua` | 不变（复用回滚） |
| Java | `CouponService.claimCoupon/dispatchClaimResult` | 新增 -4 分派；-3 入重建队列；成功/失败 ZREM + writing |
| Java | `CouponClaimPendingRecoveryJob` | 新增恢复任务 |
| Java | `CouponStockRebuildService` + `CouponStockRebuildJob` | 新增重建服务/任务 |
| Java | `CouponOutboxMapper` | `existsByClaimNo`、`markConsumed`、`countUnconsumedRecent` |
| Java | `CouponClaimConsumer` | 落库后 `markConsumed`（含 duplicate） |
| SQL | migration `coupon/V3__outbox_consumed.sql` | `consumed_at` 列 + 索引 |
| SQL | `init-all.sql` / sharding init | outbox 表加 `consumed_at` |
| 配置 | `application.yml` | rebuild/pending 参数（可选） |

---

## 5. 测试

1. `CouponServiceTest`：-3 → 入队 + 抛 -4；-4 → 正确文案；成功 → ZREM/DEL 调用；
2. `CouponClaimPendingRecoveryJobTest`：outbox 存在→只 ZREM；不存在→回滚 Lua + ZREM；writing 键→跳过；
3. `CouponStockRebuildServiceTest`：排空成功→stock=total-issued；pending 非空→超时失败；issued>total→失败；
4. `CouponClaimConsumerTest`：落库后 markConsumed（含 duplicate）；
5. 回归：coupon 全绿 + 全仓基线。

> Lua 本身无单测（无运行时 Redis）；以脚本审阅 + Java 交互测试覆盖。运行时验证按项目口径后置。

---

## 6. 残余风险（诚实）

1. Redis 整体丢数据（pending ZSET 也丢）→ 无法知道 in-flight；只能按 `issued` 重建，**少卖**方向；需要 Redis 持久化（AOF）兜底。
2. `consumed_at` 标记失败 → 重建等待/失败（安全），但可能阻塞领取；需告警人工。
2.1 **Outbox 双次写入失败 ≠ 绝对无消息**：写超时可能已提交；回滚前应按 `claimNo` 回查（C-018 一并修）。
3. 恢复任务与 Outbox 提交的竞态：writing 键 60s + 5min 阈值降低概率；极端长事务仍有窗口。
4. `COUNT(t_user_coupon)` 在 32 分片是广播查询，重建慢；可接受（低频运维操作）。
5. 无运行时验证：分片/ZSET/Lua 行为需真实 Redis 集群回归。

---

## 7. 分期

| 期 | 内容 | 预估 |
|---|---|---|
| M1 | Lua + 返回码 + 意图标记 + 恢复 Job + 测试 | 1–1.5 会话 |
| M2 | consumed_at + 重建服务/Job + 测试 | 1–1.5 会话 |
| M3 | 文档/口径/全量回归 | 0.5 会话 |
