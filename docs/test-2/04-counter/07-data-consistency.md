# 三层一致性保障剖析

> 综合：`CounterBuffer`(03) + `reconcile`(04) + `MQ Consumer`(06)
> 验证：`02-counter-test.md` §3.1 / §3.2

---

## 1. 三层架构总览

```
写入请求（MQ 事件 / HTTP API）
  │
  ├─ Redis INCR/DECR ──────────────────────── 实时生效（<1ms），用户可见
  │
  └─ Buffer.add ─── 攒批（合并同 Key 增减）
       │
       ├─ Layer 1: Buffer-Trigger 刷盘 ──────────── 5s 或 100 条触发，批量 SQL
       │    └─ 成功 → MySQL 持久化 ✅
       │
       ├─ Layer 2: 失败重试 ─────────────────────── 递增退避（100/200/300ms）×3
       │    └─ 成功 → MySQL 持久化 ✅
       │
       └─ Layer 3: 对账修复 ─────────────────────── 每天凌晨 3am，游标全表扫描
            └─ Redis↔MySQL 逐条比对 → 修复差异
```

每一层覆盖上一层的失败窗口。三层全失败才会导致永久的 Redis↔MySQL 不一致——而 Layer 3 每天执行一次，意味着不一致窗口最长为一个调度周期（24h）。

---

## 2. Layer 1：Buffer-Trigger 正常刷盘

**触发时机**：Buffer 累计 100 次写入，或每 5 秒定时触发。

**执行流程**：

```
scheduledFlush() / add() 满量触发
  → doFlush():
      1. 双 Buffer 交换（snapshot = buffer; buffer = new CHM）
      2. 过滤 delta=0（+1 再 -1 的合零 Key）
      3. 按唯一索引排序（防死锁）
      4. batchUpsert（INSERT ON DUPLICATE KEY UPDATE）
```

**覆盖的场景**：正常运行时，所有写入在 5s 内到达 MySQL。

**失效条件**：
- MySQL 不可用（连接池满、网络分区、主从切换中）
- SQL 执行超时（锁等待过长）
- 刷盘线程被 OOM 杀掉

---

## 3. Layer 2：失败重试

**触发条件**：`batchUpsert` 抛异常。

```java
catch (Exception e) {
    retryFlush(batch);  // 递增退避：100ms → 200ms → 300ms
}
```

**重试策略分析**：

```
T0:   刷盘失败（MySQL 连接超时）
T+0s: 第 1 次重试（sleep 100ms） → 仍失败（MySQL 还在恢复）
T+0s: 第 2 次重试（sleep 200ms） → 仍失败
T+0s: 第 3 次重试（sleep 300ms） → 仍失败
T+0:  记录 ERROR 日志 + 丢弃 → 等 Layer 3 对账修复
```

**为什么 3 次后丢弃？**

| 因素 | 分析 |
|------|------|
| 耗时 | 3 次重试总共 600ms + SQL 执行时间 < 1s——不会阻塞其他刷盘 |
| 成功率 | 短暂抖动（连接池满、慢查询）→ 3 次大概率成功；长时间故障 → 100 次也没用 |
| 代价 | 重试 100 次的线程占用 vs 对账修复一次全量扫描——后者成本更低 |
| 兜底 | Layer 3 对账修复覆盖 Layer 2 的失败 |

**关键区别**：Layer 2 的重试是 **同步的、本批次的**——与 Layer 1 在同一次 Buffer flush 中执行。它不是独立的调度任务。

---

## 4. Layer 3：对账修复

**触发时机**：每天凌晨 3:00，XXL-Job Admin 调度 `counterReconcileJob`。

**扫描方式**：游标分页（`SELECT ... WHERE id > lastId LIMIT 1000`），全表扫描 t_counter。

**修复策略**：

```
for each record (DB):
    redisCount = multiGet(redisKey)  // Pipeline 批量 GET
    if redisCount ≠ dbCount:
        if redisCount == 0 && dbCount > 0:
            SET redisKey = dbCount   // Redis 恢复
        else:
            UPDATE t_counter SET count_value = redisCount  // DB 修正
```

**为什么凌晨 3 点？**
- 业务低峰期，MySQL Buffer Pool 压力最小
- 全表扫描虽然游标分页，仍会驱逐热数据页
- 修复期间无用户流量竞争，UPDATE 不会产生死锁

**手动触发**：`POST /api/counter/reconcile`（@RateLimit 60s/2）——运维发现不一致时可立即修复。

---

## 5. 端到端时间线：从写入到持久化

```
T=0     MQ 事件到达 → Redis INCR（用户立即可见 count=+1）
        └─ Buffer.add(+1)

T+0~5s  Layer 1 触发 ──→ batchUpsert ──→ MySQL 持久化 ✅
        如果失败 ↓
        Layer 2 重试（100/200/300ms）──→ 成功 ✅
        如果仍失败 ↓
        记录 ERROR 日志，等待 Layer 3

T+次日   Layer 3 对账修复 ──→ 扫描全表 ──→ 发现不一致 ──→ UPDATE DB ✅
```

**最短不一致窗口**：0（Layer 1 成功，5s 内写入 MySQL）

**最长不一致窗口**：~24 小时（Layer 1+2 失败到次日 Layer 3 执行）。此期间内 Redis 值是准确的——用户看到的计数是正确的——只是 MySQL 滞后。如果在此期间 Redis 也丢失数据（RDB 恢复异常），则数据永久丢失。

---

## 6. 故障矩阵

| 故障 | Layer 1 | Layer 2 | Layer 3 | 最终结果 |
|------|:---:|:---:|:---:|------|
| MySQL 短暂不可用（< 1s） | ❌ | ✅ | — | Layer 2 修复 |
| MySQL 长时间故障（> 10s） | ❌ | ❌ | ✅ | Layer 3 修复 |
| Redis 主从切换（< 3s） | ✅ | — | — | 无影响（Sentinel 自动切换） |
| Redis 集群完全不可用 | ❌ | ❌ | ❌ | 所有计数丢失（需从 MySQL binlog 恢复） |
| JVM OOM Kill | — | — | ✅ | Buffer 数据丢失，Layer 3 从 MySQL 恢复 Redis |
| Redis RDB 数据丢失 | — | — | ✅ | Layer 3 以 MySQL 为准恢复 Redis |
| Buffer 刷盘数据错误 | ❌ | ❌ | ✅ | Layer 3 以 Redis 为准修正 MySQL |
| MQ 重复消费（m3 修复前） | — | — | ❌ | Redis 和 MySQL 同时偏高且一致，Layer 3 无法发现（两边值相同，无差异可修复） |

**关键观察**：Layer 3 是唯一能处理"数据已写入 MySQL 但值错误"的场景。Layer 1 和 Layer 2 只管"把数据写进 MySQL"，不管"写进去的值对不对"。

---

## 7. 漏洞分析：三层以外的风险

### 风险 1：Redis 有数据但 MySQL 没有

```
Buffer crash → snapshot lost → Redis=10, MySQL 无记录

Layer 3 以 MySQL 为扫描基准 → 不发现 Redis-only 数据
Layer 1+2 已失效（Buffer 丢失）
→ Redis 活跃（用户看到 10），但如果 Redis 再次崩溃 → 数据永久丢失
```

**缓解**：Redis Sentinel 高可用 + AOF 持久化 → Redis 数据丢失概率低。但这是设计上的信息不对称——只有 Redis 能看到"MySQL 缺失了什么"，对账修复看不到。

### 风险 2：Redis 和 MySQL 同时丢失

```
灾难场景：机房断电 → Redis RDB 回退 + MySQL 未刷盘数据丢失

Layer 3 执行时：Redis=0, MySQL=旧值
→ 以 MySQL 为准 → 恢复到旧值 → 新计数永久丢失
```

**缓解**：MySQL binlog 跨机房同步 + Redis AOF everysec + UPS 供电 → 同时丢失概率极低。但对于金融级一致性，这是不可接受的——这也是为什么高价值计数（订单量、支付金额）不走 Counter 模块的直接原因。

### 风险 3：Layer 3 执行时的并发写入

```
Layer 3 正在 UPDATE t_counter SET count_value=10 ← 以 Redis 为准
同时，Buffer 正在 batchUpsert: INSERT ... count_value = count_value + 2

→ MySQL 行被 Layer 3 覆盖为 10，Buffer 的 +2 可能丢失
```

**发生概率**：凌晨 3 点几乎没有用户流量 → Buffer 几乎为空 → 并发写入概率极低。即使发生，Layer 3 的覆盖操作会丢失一个 Buffer 的增量（最多丢失 5s 窗口的写入），下次 Layer 3 再修复。

---

## 8. 与 analytics 模块的一致性对比

| 维度 | counter | analytics |
|------|------|------|
| 数据模型 | 单一数值（count_value） | 双向关系（ZSet members） |
| 写路径 | Redis INCR → Buffer → MySQL | Redis Lua → MySQL INSERT |
| 一致性层数 | 3 层 | 3 层（Buffer / 重试 / repairUserRelationships） |
| 对账修复内容 | 修正数值差异 | 修正数值 + 重建丢失的关系（增删 ZSet members） |
| 跨服务一致性 | 不涉及（独立计数） | 涉及（关注关系需要双向一致） |
| 最大不一致窗口 | 24h | 24h（同一 XXL-Job Admin 调度） |

counter 的一致性模型比 analytics 简单——因为 counter 不维护关系数据，只维护独立数值。复杂的关系一致性已经留在 analytics 模块的 `FollowCounterRepairJob` 中处理。这是模块边界设计的成功——每个模块只需要保证自己领域数据的一致性。

---

## 9. 根本局限：一致 ≠ 正确

三层一致性保障的是 **Redis↔MySQL 数据一致**，不是 **数据与真实事件一致**。

```
用户点赞 1 次
  → MQ 重复投递 2 次 → Redis INCR ×2 = 2
  → Buffer flush → MySQL = 2

Layer 3 对账: Redis=2, MySQL=2 → 一致，无需修复
真实正确值: 1（用户只点了 1 次赞）
```

三层一致性无法修复"两边都错但彼此一致"的数据。这是设计的固有边界——counter 只保证计数系统的内部一致性，不保证数据源的准确性。数据源的准确性由 MQ 的去重（m3 修复）和 analytics 端生产者的断言来保证。

**三层架构的职责边界**：

| 层 | 保证什么 | 不保证什么 |
|------|------|------|
| 全三层 | Redis 和 MySQL 的数值相等 | Redis/MySQL 的数值是真实值 |
| MQ 去重（m3） | 同一条消息不被重复处理 | 消息本身是正确的 |
| analytics Producer | LikeEvent 代表一次真实的用户点赞 | 用户没有刷赞、没有脚本操作 |

---

## 关联文档

- `01-counter-module.md` — 架构总览
- `03-buffer-trigger.md` — Layer 1: Buffer 攒批刷盘
- `04-reconcile.md` — Layer 3: 对账修复算法
- `06-mq-consumer.md` — MQ 消费（一致性的入口）
