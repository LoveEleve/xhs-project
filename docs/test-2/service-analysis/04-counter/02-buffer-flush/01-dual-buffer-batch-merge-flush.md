# 双 Buffer 攒批刷盘 + 凌晨对账修复

> **源码**: CounterBuffer(244行) + CounterReconcileJob(46行) + CounterMapper+batchUpsert  
> **核心思想**: 攒批合并 → 双Buffer交换 → 定频/满量触发 → 重试3次 → 对账兜底  
> **写放大抑制**: 10000 次点赞 → 合并 2000 个不同 Key → 1 次批量 SQL（-80% 写 DB 压力）

---

## 1. CounterBuffer 全景架构 (`CounterBuffer.java`)

### 1.1 三层数据流

```
┌──────────────┐    写请求 (并发)    ┌──────────────────┐
│ CounterService│ ──── INCR/DECR ──► │ Redis INCR (L1)  │ ← 实时
│ increment()   │                    │ 原子, <0.5ms      │
│ decrement()   │                    └──────────────────┘
│ incrWithDedup │                     
└──────┬───────┘                    
       │ add(targetType, targetId,    
       │     countType, delta)        ┌──────────────────┐
       └──────────────────────────────► CounterBuffer     │
                                      │ 攒批合并 (内存)  │
                                      └──────┬───────────┘
                                             │
              触发条件 (先到先触发):           │
              ├─ 满量: bufferSize >= 100      │ doFlush()
              └─ 定时: @Scheduled 5000ms      │
                                             ▼
                                      ┌──────────────────┐
                                      │ MySQL t_counter   │
                                      │ INSERT ON DUPLICATE│← 批量
                                      │ KEY UPDATE +delta  │
                                      └──────────────────┘
      失败场景:
      ├─ 重试 3 次 (100ms/200ms/300ms)  ── 成功 → 返回
      └─ 全失败 → 记日志 ────── 凌晨 3点 ──► CounterReconcileJob
                                           │ 游标分页扫描 DB
                                           └─► reconcile() 兜底
```

### 1.2 数据结构

```java
// CounterBuffer.java:55
private volatile ConcurrentHashMap<String, AtomicLong> buffer;

// Key: "targetType:targetId:countType"
// Value: 累计增量 (AtomicLong → 无锁合并)
```

**为什么 `volatile`？** `doFlush()` 中执行 `buffer = new ConcurrentHashMap<>()` 的双 Buffer 交换。volatile 保证 add() 线程立即看到新 buffer 引用，避免写入被丢弃到旧 buffer 的幽灵引用。

**为什么 `@Contended`（行 39）？** `Contended` 注解来自 `jdk.internal.vm.annotation.Contended`，对 `AtomicInteger bufferSize` 在 CPU 缓存行的伪共享防护。在高并发 add 场景下，bufferSize 被频繁 CAS 修改，如果不隔离，会与相邻对象的 cache line 竞争。这是 JVM 层面的极致性能优化。

### 1.3 双 Buffer 交换方案 (`doFlush()`, 行 142-195)

```
写入线程 (并发)                        刷盘线程
┌──────────┐                        ┌─────────────┐
│ add(+1)  │──► buffer["1:20001:1"] │             │
│          │──► bufferSize++        │ tryLock()──► │
│ add(+1)  │──► buffer["1:20001:1"] │               │
│          │                        │ snapshot=    │ step 1
│          │                        │     buffer   │
│          │                        │ buffer = new │ step 2 ← 交换!
│          │                        │ bufferSize=0 │
│ add(+1)──│──► buffer["2:20002:1"] │ (新buffer)    │ ← 不丢失！
└──────────┘                        │               │
                                    │ 对 snapshot   │ step 3
                                    │ 合并→排序→DB  │
                                    └───────────────┘
```

**关键设置**：第 146-148 行：
```java
ConcurrentHashMap<String, AtomicLong> snapshot = buffer;  // 换出当前 buffer
buffer = new ConcurrentHashMap<>();   // 立即创建新 buffer
bufferSize.set(0);                     // 重置计数
```

三步之间的时序窗口：step2 到 step3 之间，新写入会进入新 buffer（不丢失），而刷盘线程在 snapshot 上操作。这比单 Buffer + clear() 安全得多——单 Buffer 方案下，add() 在 clear() 之后、flush 完成之前的写入会被清空。

### 1.4 合并策略

```
原始写入:  {"1:20001:1" → [+1, +1, -1, +1]}  ── 4次 add
合并结果:  {"1:20001:1" → +2}                ── 1次 batchUpsert

合并后为 0: {"1:20001:1" → [+1, -1]}        ── 跳过 (delta=0)
```

`AtomicLong.addAndGet()` 在 `computeIfAbsent` 中自动合并同一 Key 的多次增量。根据代码注释，10000 次点赞可能合并为 2000 个不同 Key，写入量减少 80%。

### 1.5 防死锁排序 (`doFlush()`, 行 179-182)

```java
flushList.sort(Comparator.comparing(CounterFlushDTO::getTargetType)
        .thenComparing(CounterFlushDTO::getTargetId)
        .thenComparing(CounterFlushDTO::getCountType));
```

**为什么需要排序？** MySQL `INSERT ON DUPLICATE KEY UPDATE` 会在唯一索引 `uk_target_count(target_type, target_id, count_type)` 上加锁。如果两个事务对同一行加锁但顺序不同（事务 A 先锁 (1,20001) 再锁 (2,20002)，事务 B 先锁 (2,20002) 再锁 (1,20001)），就会死锁。

排序确保所有事务的加锁顺序一致，消除死锁可能。

### 1.6 锁策略

```java
// CounterBuffer.java:70
private final ReentrantLock flushLock = new ReentrantLock();
```

| 场景 | 锁方式 | 原因 |
|------|------|------|
| 正常运行（add 触发/message scheduled） | `tryLock()` (行 128) | 非阻塞 — 不阻塞 add 写入线程 |
| 优雅停机 | `lock()` (行 230) | 阻塞 — 必须等待刷盘完成再退出 |

**tryLock 语义**：如果其他线程正在刷盘，本次刷盘调用直接跳过，不等待。因为刷盘正在处理的数据已包含之前的写入，本次触发是多余的。

---

## 2. 触发条件详解

### 2.1 满量触发 (`add()` 行 91-93)

```java
public void add(int targetType, long targetId, int countType, long delta) {
    String key = targetType + ":" + targetId + ":" + countType;
    buffer.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(delta);

    if (bufferSize.incrementAndGet() >= MAX_BUFFER_SIZE) {
        flush();  // 满 100 条触发送盘
    }
}
```

阈值 100 是保守设置——10K QPS 下 < 10ms 就会触发一次刷盘，确保数据不会在内存中积压太久。

### 2.2 定时触发 (`scheduledFlush()` 行 105-110)

```java
@Scheduled(fixedRate = 5000)
public void scheduledFlush() {
    if (!buffer.isEmpty()) {
        flush();
    }
}
```

**为什么用 `@Scheduled` 而不是 XXL-Job？**（注释行 99-103）
- 高频任务（5 秒），XXL-Job 调度开销可能大于执行时间
- 每个实例刷自己的 buffer（ConcurrentHashMap），不存在多实例竞争
- XXL-Job 只能选一个 Executor 执行，其他实例 buffer 积压

这是正确的设计决策。

### 2.3 优雅停机 (`shutdown()`, 行 227-237)

```java
@PreDestroy
public void shutdown() {
    flushLock.lock();  // 阻塞，确保刷盘一定执行
    try {
        doFlush();
    } finally {
        flushLock.unlock();
    }
}
```

`CounterBuffer` 同时实现 `GracefulShutdownHook` 接口（行 43），由 Nacos 注销后 `GracefulShutdownListener` 调用 `onShutdown()`（行 239-243）。这样保证了顺序：先 Nacos 注销（停止新流量），再刷盘，再 JVM 退出。

---

## 3. 批量 Upsert (`CounterMapper.batchUpsert`)

```sql
-- CounterMapper.java:27-35
INSERT INTO t_counter (id, target_type, target_id, count_type, count_value)
VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?), ...
ON DUPLICATE KEY UPDATE
    count_value = count_value + VALUES(count_value),
    updated_at = NOW()
```

**为什么是 `count_value + VALUES(count_value)` 而不是 `VALUES(count_value)`？**

因为这是增量写入而非全量覆盖。Buffer 中的 delta 是增量（+1、-1），如果覆盖写入 `count_value = VALUES(count_value)`，多实例并发写入时会发生覆盖错误：

```
实例 A flush: delta=+1 → DB count_value = 10+1 = 11 ✅
实例 B flush: delta=+1 → DB count_value = 10+1 = 11 ❌ (应为 12)
```

实际情况是 `ON DUPLICATE KEY count_value = count_value + VALUES(count_value)`：实例 B 写的是 `count_value = 11 + 1 = 12` ✅。

**注意**：这个机制假设 **所有实例写入的 delta 值都在 VALUES 中**。如果有个实例认为 delta=+1 而实际应为 +3（比如合并了 3 次 +1），就会写入错误值。但 Buffer 的合并在单个实例内完成，delta 值已经过合并，所以不会出现这种问题。

---

## 4. 对账修复 (`CounterReconcileJob.java` + `CounterService.reconcile()`)

### 4.1 调度模型

```java
// CounterReconcileJob.java:34
@XxlJob("counterReconcileJob")
public void reconcile() {
    int fixedCount = counterService.reconcile();
    XxlJobHelper.handleSuccess("修复 " + fixedCount + " 条");
}
```

XXL-Job Admin 配 Cron = `0 0 3 * * ?`（每天凌晨 3 点）。Admin 的路由策略保证只调度一个 Executor 实例执行，因此 **无需分布式锁**（注释行 19）。

### 4.2 修复规则（重申之前文档内容，作完整性说明）

| 条件 | 操作 | 原因 |
|------|------|------|
| redisCount ≠ dbCount (redisCount > 0) | `updateCountValue(id, redisCount)` | Redis 是实时权威源 |
| redisCount == 0 && dbCount > 0 | `SET(redisKey, dbCount)` | Redis 可能丢失数据 |
| DB 有记录但 Redis 无记录 | **不处理** | 新 Key，等下次计数事件建立 |

### 4.3 覆盖盲区

**对账不覆盖的场景**：Redis 有值、DB 无记录。因为 `reconcile()` 以 DB 游标扫描为基准（`WHERE id > lastId`），如果 DB 中完全没有对应行（可能因为 Buffer 刷盘全失败且从未有这条记录），对账扫描不会发现它。

**为什么这不算问题？** Buffer 刷盘全失败（重试 3 次 + 100ms+ 递增退避）且 MySQL 宕机的概率组合极低。而且即使发生，该计数在 DB 中不存在，下次 Buffer 刷盘成功时会 INSERT 一条新记录。

---

## 5. 工程维度审查

### 5.1 并发安全

| 场景 | 保障 |
|------|------|
| add() 并发写入同一 Key | `ConcurrentHashMap.computeIfAbsent` + `AtomicLong.addAndGet` → 无丢失合并 |
| bufferSize 并发自增 | `AtomicInteger.incrementAndGet` → CAS 保证 |
| 满量触发 + 定时触发同时执行 | `ReentrantLock.tryLock()` → 只有一个线程刷盘 |
| 双 Buffer 交换可见性 | `volatile` + 先赋值 `snapshot=旧` 再 `buffer=新` → add() 立即看到新引用 |
| 优雅停机 + 正常运行并发 | `lock()` 阻塞 → 等待正常刷盘完成后再强制刷盘 |

### 5.2 死锁分析

| 资源 | 加锁顺序 | 风险 |
|------|------|------|
| flushLock | tryLock 非阻塞 + lock 仅停机使用 | 不会死锁（单一锁） |
| MySQL 行锁 (多行 batchUpsert) | 按 `(targetType, targetId, countType)` 排序统一加锁顺序 | 消除跨行死锁 |
| Buffer 内部操作 | 无额外锁 (ConcurrentHashMap + Atomic* 无锁) | 无死锁 |

### 5.3 可靠性

| 故障 | 影响 | 恢复 |
|------|------|------|
| Buffer 刷盘线程异常 | 单批次数据丢失 | 重试 3 次；全失败由凌晨对账恢复 |
| MySQL 短暂不可用 | flush 失败；Redis 正常服务 | 重试 + 对账兜底 |
| JVM kill -9 | Buffer 内存数据丢失 | 无方案（-9 跳过 @PreDestroy），依赖对账恢复 DB |
| 本实例 OOM | 同 kill -9 | 对账恢复 + Redis 仍然有正确值 |
| Redis 重启 | getCount miss → DB 兜底 | 回填机制；凌晨对账修复 |

**kill -9 场景**是所有 Buffer 方案的共性：内存数据无法刷盘。但从 Redis 侧的计数仍然正确（INCR 已执行），只是 DB 侧有 delta 偏移。对账会以 Redis 为准修复。

### 5.4 性能

| 操作 | 瓶颈 | 优化 |
|------|------|------|
| add() | ConcurrentHashMap computeIfAbsent | 无锁 (bucket 级锁) |
| BatchUpsert | SQL `INSERT ... ON DUPLICATE KEY` | MySQL 唯一索引 batch insert |
| 排序 | `Comparator.comparing` × 2000 | O(N log N) 内存内排序，< 1ms |
| 对账 | 游标分页 1000/批 | O(N) 扫描，Pipeline multiGet 批量读 Redis |
| 优雅停机 flush | lock() 阻塞 | 最多等待一次刷盘时间 |

---

## 6. 面试 Q&A

### Q1: 为什么 Buffer 用 ConcurrentHashMap + AtomicLong 而不是 Redis 的 INCRBY 直接写 DB？

**陷阱答案**：因为 Redis INCR 更快。

**正确答案**：不是 Redis vs Buffer 的问题，是架构分工。Redis INCR 已经执行了（见 CounterService.increment），Buffer 是专门解决**写放大**问题的。如果每次 INCR 都同步写 DB：
- 10K QPS 点赞 → 10K 次 SQL → MySQL 连接耗尽
- Buffer 攒批 5 秒 + 合并 → 10K 次 add 变成 2000 个 Key → 1 次批量 SQL
- 这是 **写入量降低 80%** 的优化，而不是 Redis vs DB 的替代

### Q2: 双 Buffer 交换方案下，add 的时机与 flush 的交换之间有没有数据丢失窗口？

```java
// doFlush() 行 146-148
ConcurrentHashMap<String, AtomicLong> snapshot = buffer;
buffer = new ConcurrentHashMap<>();
```

- 行 146: snapshot 指向旧 buffer
- 行 147: buffer 指向新 buffer（volatile → 立即可见）

在这两行之间没有其他指令，且 `buffer` 是 volatile，写 `buffer = new()` 通过 happens-before 保证所有 add() 线程立即看到新引用。**没有丢失窗口**。

### Q3: 凌晨对账修复如果耗时超过 2 小时（dedup TTL），会产生什么影响？

对账修复本身不依赖 dedup Key——对账只读 `counter` Key 的值，不涉及去重逻辑。dedup TTL 只影响 MQ 去重，与对账无关。

对账耗时主要取决于 `t_counter` 表行数 × 每批 1000 的 loop 次数。假设 100 万行 → 1000 批 → 每批 multiGet 1000 个 Key ~ 1ms → 总耗时约 1000ms + MySQL I/O。实际耗时 < 10 秒。

### Q4: 如果 `batchUpsert` 中有一行违反唯一约束，整个 batch 会失败吗？

不会。`ON DUPLICATE KEY UPDATE` 不是 `INSERT IGNORE`，遇到重复唯一键不会失败，而是执行 UPDATE。SQL 的原子性由 MySQL 的单语句事务保证——要么全部执行（INSERT + UPDATE 混合），要么全部不执行（整条 SQL 失败回滚）。

不过如果 `batchUpsert` 插入多条记录且有一条违反非 UNIQUE 约束（如 NULL NOT NULL），整个 batch 确实会回滚。但 Buffer 的 `CounterFlushDTO` 字段都是内部构建的（id 是雪花生成器），不会出现这种问题。
