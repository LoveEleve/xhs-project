# 对账修复算法深度分析

> 源码：`CounterService.reconcile()` + `CounterReconcileJob.java`
> 验证：`02-counter-test.md` §1.1(XXL-Job) / §3.2

---

## 1. 问题域：Redis↔MySQL 不一致的成因

| 场景 | 形成机制 | Redis | MySQL | 影响 |
|------|------|:---:|:---:|------|
| Buffer 刷盘失败 | DB 临时不可用，重试 3 次全失败 | 最新 | 滞后 | 计数偏高显示 |
| Redis RDB 恢复异常 | AOF 截断或 RDB 版本偏差 | 0 或丢失 | 有值 | 计数归零 |
| 进程 OOM Kill | Buffer 中未刷盘数据丢失 | 最新 | 滞后 | DB 缺少写入 |
| 手动运维操作 | DBA 直接改 DB 或 Ops 误清 Redis Key | 不确定 | 不确定 | 双向偏差 |

**核心矛盾**：Redis 是"实时正确"但"非强持久"，MySQL 是"强持久"但"可能滞后"。对账修复的职责就是弥合这个 gap。

---

## 2. 修复策略矩阵

```java
for (each record in batch):
    long redisCount = parse(redisValues[i]);
    long dbCount = dbCounter.getCountValue();

    if (redisCount != dbCount) {
        if (redisCount == 0 && dbCount > 0) {
            // 策略 A：Redis 恢复（以 DB 为准）
            SET redisKey = dbCount;
        } else {
            // 策略 B：DB 修正（以 Redis 为准）
            UPDATE t_counter SET count_value = redisCount;
        }
        fixedCount++;
    }
```

| 场景 | 决策 | 理由 |
|------|------|------|
| Redis > 0 且 ≠ DB | **以 Redis 为准** → 修正 DB | Redis 是实时 INCR/DECR 的结果，比 DB 更"新鲜" |
| Redis = 0 且 DB > 0 | **以 DB 为准** → 恢复 Redis | 业务不会把计数减到 0 以下，Redis=0 意味数据可能丢失 |
| Redis > 0 且 DB 无记录 | 不在这个算法中 | reconcile 只扫描 DB 中存在的记录，DB 无记录不会被发现。但这恰好是"Buffer 未刷盘"场景——Redis 有值说明计数是真实的，DB 无记录说明 Buffer 还没刷。等 Buffer 下次刷盘就一致了 |

### 为什么 Redis=0 + DB>0 用 DB 恢复 Redis？

如果 Redis 是权威源，Redis=0 意味着计数确实是 0——但 DB 显示有值说明之前有计数操作（DB 有历史记录）。这两个冲突的信息中，DB 更可信——因为：

1. **Redis 数据可能丢失**：AOF 截断、RDB 恢复失败、哨兵切换后新 Master 数据不全
2. **DB 数据有审计**：MySQL binlog 比 Redis AOF 更可靠
3. **业务不会产生计数=0 的 DB 行**：如果 DB 有这一行，一定有过的计数 > 0

---

## 3. 游标分页：为什么不是 OFFSET？

```sql
-- ❌ OFFSET 深分页
SELECT * FROM t_counter WHERE deleted=0
  ORDER BY id LIMIT 1000 OFFSET 90000;
-- 扫描 91000 行，丢弃前 90000 行 → O(n²) 复杂度

-- ✅ 游标分页
SELECT * FROM t_counter WHERE id > lastId AND deleted=0
  ORDER BY id ASC LIMIT 1000;
-- 利用主键索引，只扫描 1000 行 → O(n)
```

**游标分页的前提**：排序字段（id）是单调递增的主键，且有索引。满足这两个条件时，`WHERE id > lastId` 等价于书签（bookmark）——每次从上次结束位置继续，不用从头扫描。

**为什么每批 1000 条？**

| 批次大小 | 扫描速度 | OOM 风险 | Redis 压力 |
|:---:|------|:---:|------|
| 100 | 慢（N/100 批） | 低 | 低 |
| 1000（当前） | 适中 | 低 | 1 次 multiGet×1000 |
| 10000 | 快（N/10000 批） | 中 | multiGet 单个请求过大 |
| 全表 | 最快（1 批） | 高（OOM） | 不可控 |

1000 是个平衡点——t_counter 典型规模 10 万行的话，100 批 multiGet + 100 批逐条比较，几分钟内完成。

---

## 4. multiGet 批量 GET：N 次往返 → 1 次

```java
// 收集本批所有 Key
List<String> redisKeys = new ArrayList<>(batch.size());
for (Counter dbCounter : batch) {
    redisKeys.add(buildRedisKey(targetType, targetId, countType));
}

// 一次 multiGet 替代 1000 次 GET
List<String> redisValues = stringRedisTemplate.opsForValue().multiGet(redisKeys);
```

**multiGet vs Pipeline 的选择**：

| | multiGet | Pipeline (executePipelined) |
|------|:---:|:---:|
| 命令数 | 1 个（MGET） | N 个（GET×N） |
| 网络往返 | 1 次 | 1 次 |
| 返回值类型 | `List<String>`（含 null） | `List<Object>`（含 null bytes） |
| 适用场景 | 纯批量 GET | 混合多种操作 |

reconcile 只需要批量 GET，用 `multiGet` 比 `executePipelined` 更简洁——一行代码，自动封装 MGET 命令。

**null 安全性**：

```java
if (redisValues == null) redisValues = Collections.emptyList();  // multiGet 整体失败

String redisValue = i < redisValues.size() ? redisValues.get(i) : null;  // 边界保护
long redisCount = redisValue != null ? Long.parseLong(redisValue) : 0;    // 空值保护
```

三层防护：
1. `multiGet` 返回 null → 替换为空列表
2. 索引越界 → `i < size()` 检查
3. 单个 GET 返回 null（Key 不存在） → `Long.parseLong(null)` 不会发生

---

## 5. 覆盖修正 vs 增量修正：DB UPDATE 的语义选择

对账发现了不一致，怎么修正？有两种选择：

**修正 DB（策略 B）**：`UPDATE t_counter SET count_value = redisCount WHERE id = ?`

```sql
-- reconcile 发现 Redis=10, DB=5
UPDATE t_counter SET count_value = 10, updated_at = NOW() WHERE id = 12345;
```

**注意**：使用 `SET count_value = redisCount`（覆盖）而不是 `count_value = count_value + gap`（增量）。因为对账发现的差异不一定来自一次刷盘失败——可能是多次累积的结果。覆盖式修正确保最终值精确等于 Redis 值。

**恢复 Redis（策略 A）**：`SET redisKey = dbCount`

这是一个 Redis SET 操作，不设 TTL——和正常的计数器 Key 一样是永久 Key。因为业务可能立即对此 Key 做 INCR/DECR，不能设 TTL。

---

## 6. XXL-Job 调度：counterReconcileJob

```java
@XxlJob("counterReconcileJob")
public void reconcile() {
    int fixedCount = counterService.reconcile();
    XxlJobHelper.handleSuccess("对账修复完成，修复 " + fixedCount + " 条");
}
```

**XXL-Job Admin 配置**：

```
Job: counterReconcileJob
Cron: 0 0 3 * * ?（每天凌晨 3 点）
路由策略: 第一个
阻塞策略: 串行执行
失败重试: 0 次（不重试——下次调度再处理）
```

**为什么凌晨 3 点？** 业务低峰期，DB IO 压力最小。全表扫描 t_counter 虽然是游标分页，但仍占用 MySQL 的 Buffer Pool。

**为什么路由策略是"第一个"？** 对账修复不能多实例并发——会导致重复修正（两个实例同时 SET Redis + UPDATE DB，产生竞争）。但又不需要 Redisson 分布式锁——因为 XXL-Job 的"第一个"路由策略保证只有一个 Executor 执行。

**为什么失败不重试？** 对账是批量操作——如果失败，可能是中途某批 SQL 超时。此时已修复的数据是有效的（已 write DB），未修复部分下次调度再处理。重试反而可能重复修正已修正的数据（策略 B 中的 `SET count_value = redisCount` 是覆盖操作，重复执行无害——但重试造成的额外全表扫描不必要）。

### 完整执行时间线

```
03:00:00  XXL-Job Admin 触发 counterReconcileJob
03:00:00  CounterReconcileJob.reconcile() 开始
03:00:00  ├─ SELECT * FROM t_counter WHERE id > 0 LIMIT 1000        (第 1 批)
03:00:01  │  └─ multiGet 1000 Redis keys → 999 一致, 1 不一致
03:00:01  │     └─ UPDATE t_counter SET count_value=10 WHERE id=xxx (修正 1 条)
03:00:01  ├─ SELECT * FROM t_counter WHERE id > lastId LIMIT 1000   (第 2 批)
03:00:01  │  └─ multiGet → 全部一致, 无修正
03:00:01  ├─ ... (重复直到 batch.size() < 1000)
03:00:30  └─ 扫描完毕, 共修复 N 条
03:00:30  XxlJobHelper.handleSuccess("对账修复完成，修复 N 条")
```

### 手动触发：`POST /api/counter/reconcile`

除了凌晨 3 点的定时执行，还可以通过 REST API 手动触发：

```
POST /api/counter/reconcile
→ 200 {"code":200,"data":N}  // 返回修复条数
```

**何时使用手动触发？**

| 场景 | 手动触发必要？ |
|------|:---:|
| 发现 Redis 和 DB 数据明显不一致 | ✅ 立即修复 |
| Buffer 刷盘多次失败，日志中看到重试错误 | ✅ 确认恢复 |
| 凌晨 3 点任务失败（Admin 不可达） | ✅ 手动补偿 |
| 日常运维检查 | ❌ 等凌晨自动执行 |

手动触发有 @RateLimit(60s/2) 保护——60 秒内最多 2 次，防止频繁全表扫描。

---

## 7. 边界场景深入分析

### 场景 1：MySQL 行存在但 Redis Key 缺失

```
Redis: GET myxhs:counter:1:20001:1 → null (Key 不存在)
MySQL: count_value=42

reconcile: redisValues[i] == null → redisCount = 0
           redisCount(0) ≠ dbCount(42) → redisCount==0 && dbCount>0
           → 策略 A: SET redisKey = "42" (恢复 Redis)
```

这属于"策略 A：Redis 恢复"。即使 Key 不存在（不是值=0），`redisValues[i] == null` 也会被解析为 `redisCount = 0`，触发策略 A。和 "Redis 值为 0 + DB 有值" 的修复行为完全一致——用 DB 的值恢复 Redis。

### 场景 2：批次内部分修复失败

```
Batch #5: 1000 条 → multiGet 成功 → 发现 5 条不一致
  → UPDATE #1: 成功
  → UPDATE #2: 成功
  → UPDATE #3: 失败！（DB 连接超时）
  → 抛异常 → reconcile() 整体结束
  → XXL-Job 标记任务失败
  → fixedCount 只计了 2 条（#1+#2）
```

**影响**：已修复的 2 条是有效的（DB 已更新），未修复的 3 条下次凌晨调度再处理。不会有数据丢失——Redis 的值始终是正确的（没被改），只是 DB 暂时没跟上。

**为什么不做事务回滚？** 对账修复不需要事务保证——部分修复不会产生错误数据。`SET count_value = redisCount` 无论执行多少次结果都相同（幂等覆盖），所以部分失败后下次再执行也不会有问题。

### 场景 3：Redis 连接中断时的行为

```
multiGet(1000 keys) → Lettuce 抛 RedisConnectionFailureException
→ 异常传播：reconcile() → CounterReconcileJob.reconcile()
→ catch (Exception e) → log.error → XxlJobHelper.handleFail("对账修复异常")
→ XXL-Job 标记任务失败，凌晨 3 点调度时重试
```

**不会触发误修复**：因为 `multiGet` 在 Redis 不可达时直接抛异常，不会走到 `redisValues == null → Collections.emptyList()` 的分支。那行代码处理的是 multiGet 返回 null 的边缘情况（如空 key 列表、序列化异常），不是连接中断。

**与策略 A 的 null 防护的关系**：`redisValues == null` 和后续的 `redisValue == null` 防护是防御性代码——防止单个 Key 不存在时 `Long.parseLong(null)` 抛出 NPE。它们不影响 Redis 全局不可用时的行为。

**与 analytics 模块对账的对比**：

| 维度 | counter reconcile | analytics FollowCounterRepairJob |
|------|------|------|
| 修复对象 | 单一计数值（count_value） | 双向关注关系（ZSet members） |
| 修复策略 | 2 种（Redis 权威 / DB 权威） | 3 层（计数 / 关系 / 孤立记录） |
| 数据模型 | 简单 String Key | 复杂 ZSet 双向存储 |
| 游标分页 | ✅ id > lastId | ✅ 类似 |
| Pipeline | ✅ multiGet | ✅ executePipelined ZSCORE |
| 复杂度 | 低 | 高（需要重建关系链） |

counter 的对账比 analytics 简单很多——因为 counter 只管"一个数是否正确"，而 analytics 需要管"两个用户互相关注的关系是否完整"。这种简单性是关注点分离的结果：关系复杂度归 analytics，计数归 counter，各自对账各自领域的数据。

---

## 8. 实测数据与性能分析

### 实测（从 curl 测试 §3.2）

```
对账修复开始
  → 游标分页扫描 t_counter
  → 发现 1 条不一致: Redis=10, DB=5
  → 修正 DB: count_value 5→10
  → 修复完成: 1 条
```

### XXL-Job Admin 部署后自动发现的真实不一致

之前 XXL-Job 部署时，对账发现了 **1 条真实不一致**：

```
[对账修复] DB修正: key=myxhs:counter:1:2076147855673843713:2, redis=2, db=1
```

这是 m3（MQ 无幂等保护）修复前遗留的计数偏差——某个 LIKE 事件被重复消费导致收藏数偏高，对账修正后恢复一致。这证明了对账修复作为"最终一致性兜底"的有效性。

### 时间复杂度估算

| 表行数 | 批次数 | multiGet 调用 | Redis Ops | MySQL Ops | 预估耗时 |
|:---:|:---:|:---:|:---:|:---:|:---:|
| 1,000 | 1 | 1 | 1000 GET | 0~1 UPDATE | < 1s |
| 10,000 | 10 | 10 | 10000 GET | 0~10 UPDATE | ~5s |
| 100,000 | 100 | 100 | 100000 GET | 0~100 UPDATE | ~30s |
| 1,000,000 | 1000 | 1000 | 1000000 GET | 0~1000 UPDATE | ~5min |

主要开销在 Redis multiGet（网络+反序列化），MySQL UPDATE 只对不一致的行执行——通常 < 1% 的行不一致。

---

## 9. 已知限制

| 限制 | 说明 | 影响 |
|------|------|------|
| **DB 无记录的不一致不可见** | reconcile 以 DB 为扫描基准，Redis 有值但 DB 无行不会被发现 | Buffer 崩溃时 Redis 数据孤立——用户看到正确计数，但 Redis 再次崩溃（RDB 恢复）会永久丢失。这是 Buffer-Trigger 的固有 tradeoff：降低 DB 写入负担 vs 短窗口数据丢失风险 |
| **Redis 中断时不修复** | multiGet 返回 null 全部跳过（空列表） | 本批忽略，下次调度重试 |
| **无中断保护** | 全表扫描期间如果 DB 行数暴增，可能超时 | XXL-Job 有超时配置 |
| **逻辑删除行仍扫描** | `deleted=0` 过滤，但 `deleted=1` 行不修复 | 软删除行不做对账（合理） |

---

## 关联文档

- `01-counter-module.md` — §7 定时对账修复
- `02-counter-test.md` — §1.1 XXL-Job / §3.2 对账修复
- `03-buffer-trigger.md` — CounterBuffer（对账修复的第一层保护失败后的兜底）
- `07-data-consistency.md` — 三层一致性（对账是第三层）
