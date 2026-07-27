# CounterBuffer — Buffer-Trigger 攒批刷盘深度分析

> 源码：`my-xhs-counter/.../buffer/CounterBuffer.java`（244 行）
> 验证：`02-counter-test.md` §1.1 / §3.1

---

## 1. 设计动机：为什么不用 SQL 直接写 DB？

```
HTTP 请求 → Controller → Service → Redis INCR + MySQL UPDATE

每 1 次点赞 = 1 次 Redis INCR + 1 次 MySQL UPDATE
```

**QPS 换算**：

| 场景 | QPS | SQL/秒 | 说明 |
|------|:---:|:------:|------|
| 常规 | 1000 | 1000 | MySQL 轻松应对 |
| 热点笔记 | 5000 | 5000 | 开始有压力 |
| 大促峰值 | 10000 | 10000 | UPDATE 表级行锁竞争 |
| Buffer-Trigger | 10000 | ~500 | 合并后 DB 写入 20× 降低 |

**合并效果**：10000 次点赞 → 去重后可能只涉及 2000 个不同笔记 → Buffer 合并同 Key → 1 次 `batchUpsert`（每批 500 行 × 4 批）。

**为什么用 Buffer 而不是再加一层 MQ 异步写？**

直接写 DB 的问题不是"同步 vs 异步"，而是"1 次写 vs 合并写"——即使异步，每次点赞仍然产生 1 次 SQL。MQ 异步化不改写 SQL 次数。Buffer 的核心价值是合并（merge），不是异步（async）。

而且 counter 模块的 MQ 已经用在接收端（SOCIAL_TOPIC events），DB 写入在消费侧完成，再加一层 MQ 会让链路变成 `MQ→Consumer→Redis→MQ→Consumer→DB`——延迟翻倍。

---

## 2. 核心数据结构

```java
@Contended
@Component
public class CounterBuffer {
    private volatile ConcurrentHashMap<String, AtomicLong> buffer = new ConcurrentHashMap<>();
    private final AtomicInteger bufferSize = new AtomicInteger(0);
    private final ReentrantLock flushLock = new ReentrantLock();
}
```

| 字段 | 类型 | 作用 | 并发保证 |
|------|------|------|------|
| `buffer` | `volatile ConcurrentHashMap<String, AtomicLong>` | K="targetType:targetId:countType", V=累计增量 | CHM 分段锁 + AtomicLong CAS + volatile 可见性 |
| `bufferSize` | `AtomicInteger` | 写入次数（触发满量判断） | CAS 自增 |
| `flushLock` | `ReentrantLock` | 刷盘互斥（tryLock 非阻塞） | AQS 独占锁 |

### 为什么是 ConcurrentHashMap + AtomicLong？

```java
// ❌ HashMap + synchronized(add)
synchronized(buffer) {
    Long current = buffer.get(key);
    buffer.put(key, current + delta);
}

// ✅ ConcurrentHashMap + AtomicLong
buffer.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(delta);
```

CHM 的分段锁让不同 Key 的写入互不阻塞——只有同一 Key 的并发更新才竞争 AtomicLong。10000 QPS 分布在 2000 个 Key 上 → 平均每 Key 5 QPS → 锁竞争极低。

### `volatile` 的必要性

```
add() 线程（写线程）          flush() 线程（调度线程）
  buffer = CHM-1               snapshot = buffer;          ← volatile 读
  buffer.put(key, value)       buffer = new CHM-2();       ← volatile 写
```

`volatile` 保证：flush 线程在 `buffer = new CHM-2()` 之前，一定看到 add 线程对 CHM-1 的所有写入。没有 volatile，JMM 允许 flush 线程看到 stale CHM-1 状态（不见得看到 `put()` 的结果）。

**为什么不是 `final`？** 双 Buffer 交换需要给 `buffer` 赋新值，不能是 final。

---

## 3. 写路径：合并同 Key 增减

```java
public void add(int targetType, long targetId, int countType, long delta) {
    String key = targetType + ":" + targetId + ":" + countType;
    buffer.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(delta);
    if (bufferSize.incrementAndGet() >= MAX_BUFFER_SIZE) {
        flush();
    }
}
```

**合并原理**：3 次对同一 Key 的调用 `+1, +1, -1` → AtomicLong 值 = 1+1-1 = 1 → Buffer 记录 delta=+1，而不是 3 条独立记录。

**关键 API 选择**：

```java
// computeIfAbsent — 原子"不存在则创建"
// 线程 A: computeIfAbsent(key, new AtomicLong(0))
// 线程 B: computeIfAbsent(key, new AtomicLong(0))
// → 只有一个线程创建 AtomicLong，另一个拿到引用
// → 两者都正确 addAndGet(delta)，不会丢更新

// ❌ 如果用 putIfAbsent + get + addAndGet + put:
//   atomicLong = buffer.putIfAbsent(key, new AtomicLong(0))  ← 可能返回 null
//   atomicLong.addAndGet(delta)  ← NPE！
```

**bufferSize 的"近似计数"**：`bufferSize` 记录调用次数（不是 Key 数量）。原因：
- 满量触发关心"写入压力"——100 次写入无论多少 Key 都应该刷盘
- Key 数量不能反映写入压力——1 个 Key 被写 100 次和 100 个 Key 各写 1 次，数据库压力相同（都是 1 行 vs 100 行）
- `buffer.size()` 返回 Key 数量，但 CHM.size() 是 O(n) 的——不如 AtomicInteger 快

---

## 4. 双 Buffer 交换：消除数据丢失窗口

### 为什么需要双 Buffer？

```java
// ❌ 单 Buffer + clear
void flush() {
    writeDB(buffer);
    buffer.clear();  // 窗口：clear 和 writeDB 之间可能有新写入
}

// ✅ 双 Buffer 交换
void doFlush() {
    ConcurrentHashMap<String, AtomicLong> snapshot = buffer;  // 换出
    buffer = new ConcurrentHashMap<>();                       // 新 Buffer
    bufferSize.set(0);
    writeDB(snapshot);  // 新写入进新 Buffer，不受影响
}
```

**竞态窗口分析**：

```
T0: 线程 A flush snapshot=buffer           线程 B add(key, +1) → buffer(新).put()
T1: 线程 A buffer=new CHM()                线程 B 写入新 Buffer ✓（不丢失）
T2: 线程 A bufferSize.set(0)               线程 B bufferSize++
T3: 线程 A writeDB(snapshot)               snapshot 包含 T0 之前的增量 ✓
```

**单 Buffer 的问题**：如果在 `writeDB(snapshot)` 期间有新 add() → 写入旧 buffer → 被 clear 吞掉 → 数据丢失到对账修复才能恢复。

### volatile 保证的是引用可见性（不是 CHM 内容）

```java
// add() 线程:
buffer.computeIfAbsent(...).addAndGet(delta);  // ① 写入 CHM-1 内部状态

// flush() 线程:
snapshot = buffer;  // ② volatile 读 → 获取 CHM-1 引用
buffer = new CHM(); // ③ volatile 写 → 替换为新 CHM-2
writeDB(snapshot);  // ④ 处理 CHM-1 快照
```

**CHM 内容由谁保证？** ① 写入的是 CHM 内部状态，CHM 自己的分段锁/CAS 保证了后续读可见——不依赖 `buffer` 的 volatile。

**volatile 在这里的作用**：② 和 ③ 涉及 `buffer` 的引用级操作，volatile 保证：
1. flush 线程在 ② 读到的是 add 线程最近写入的 CHM 引用（如果 add 线程之前有对 `buffer` 的赋值）
2. flush 线程在 ③ 之后，后续的 add 线程读 `buffer` 会看到新的 CHM-2

**关键点**：`buffer` 的 volatile 不保证 CHM 内容的可见性——那是 CHM 自己的事。volatile 保证的是"引用交换"这个操作在多线程间的可见性——add 线程在 ③ 之后的写入进 CHM-2（新 Buffer），不被 flush 的 snapshot 覆盖。

---

## 5. 触发机制：三级递进

```
┌─ 一级：满量触发 ─────────────────────────────────────────┐
│ add() {                                                    │
│   bufferSize.incrementAndGet() >= 100 → flush()            │
│ }                                                          │
│ 100 次写入触发，理论上 1000 QPS 下每 100ms 刷一次           │
├─ 二级：定时触发 ─────────────────────────────────────────┤
│ @Scheduled(fixedRate = 5000)                                │
│   if (!buffer.isEmpty()) flush()                            │
│ 兜底机制——低流量下不会因为不到 100 条就永远不刷              │
├─ 三级：优雅停机 ─────────────────────────────────────────┤
│ @PreDestroy shutdown()                                       │
│   flushLock.lock(); doFlush(); flushLock.unlock();           │
│ 停机前最后一次刷盘，用 lock() 阻塞等待确保一定执行            │
└───────────────────────────────────────────────────────────┘
```

**为什么用 @Scheduled 而不是 XXL-Job？**

| 因素 | @Scheduled | XXL-Job |
|------|:---:|:---:|
| 执行频率 | 5s（适合） | 最小 30s（频率过高 Admin 压力大） |
| 执行范围 | 每个实例独立 Buffer | Admin 只调度一个实例 |
| 调度开销 | 0（本地调度） | 网络往返 + Admin DB 写 |
| 故障恢复 | 无（依赖进程重启） | 有失败重试 |

Buffer 是每个实例各自持有的 `ConcurrentHashMap`，用 XXL-Job 反而会"只让一个实例刷"——其他实例的 Buffer 就积压了。

---

## 6. 并发控制：tryLock vs synchronized

```java
private void flush() {
    if (!flushLock.tryLock()) {   // ← 非阻塞
        return;                    // 说明其他线程在刷盘，跳过
    }
    try { doFlush(); } finally { flushLock.unlock(); }
}
```

**为什么不用 synchronized？**

```java
synchronized(this) { flush(); }  // ❌ add() 线程被阻塞
```

`add()` 中 `bufferSize.incrementAndGet() >= 100` 触发 flush()。如果用 synchronized，add() 线程会在 flush() 上阻塞——高并发下几百个 add() 线程排队等 flush() 完成，写入延迟从微秒级变成毫秒级。

**tryLock 语义**：「如果有人在刷，我就不刷了——反正数据还在 Buffer 里，下次定时/满量触发再刷」。这类似于乐观并发——不阻塞生产者，依赖定时兜底。

**`@Contended` 注解**：标注在类上，所有实例字段（`buffer`、`bufferSize`、`flushLock` 及 `counterMapper`、`idGeneratorUtil`）各自占用独立 cache line，避免伪共享。

> **生产注意**：`@Contended` 是 JDK 内部注解（`jdk.internal.vm.annotation.Contended`），需要 JVM 启动参数 `--add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED` 才能生效。不加参数 Java 17+ 会静默忽略该注解，无编译错误但无实际 padding 效果。

---

## 7. 防死锁排序

```java
flushList.sort(Comparator.comparing(CounterFlushDTO::getTargetType)
        .thenComparing(CounterFlushDTO::getTargetId)
        .thenComparing(CounterFlushDTO::getCountType));
```

**死锁场景**：

```
事务 A: INSERT (targetType=1, targetId=2) → 锁行 (1,2)
         INSERT (targetType=1, targetId=1) → 等行 (1,1) 锁

事务 B: INSERT (targetType=1, targetId=1) → 锁行 (1,1)
         INSERT (targetType=1, targetId=2) → 等行 (1,2) 锁

→ 死锁！
```

排序后所有事务按同一顺序加锁 → 事务 A 锁 (1,1) 再锁 (1,2)，事务 B 也锁 (1,1) 再锁 (1,2) → 事务 B 等事务 A 释放 (1,1) → 不会交叉循环等待。

**为什么 `INSERT ON DUPLICATE KEY UPDATE` 不是幂等的？**

`batchUpsert` 的执行不是幂等的——每次执行都会 `count_value = count_value + delta`。如果同一条数据被刷两次（Buffer 没清除干净），计数会翻倍。这是 Buffer 需要「双 Buffer 交换 + 一次成功即丢弃」的原因。

---

## 8. 失败重试：递增退避 + 对账兜底

```java
private void retryFlush(List<CounterFlushDTO> batch) {
    for (int i = 1; i <= 3; i++) {
        try {
            Thread.sleep(100L * i);  // 100ms → 200ms → 300ms
            counterMapper.batchUpsert(batch);
            log.info("重试第 {} 次成功: {} 条", i, batch.size());
            return;  // 成功后立即返回
        } catch (Exception e) {
            log.error("重试第 {} 次失败: {} 条", i, batch.size(), e);
        }
    }
    // 3 次全部失败 → 记录日志 + 每条数据的 key+delta
    log.error("重试 3 次全部失败，等待对账修复。数据: {}",
        batch.stream()
            .map(dto -> dto.getTargetType() + ":" + dto.getTargetId() + ":" + dto.getCountType() + "=" + dto.getDelta())
            .collect(Collectors.joining(", ")));
}
```

**递增退避**：`100ms → 200ms → 300ms`，总共最多等待 600ms。如果 MySQL 只是短暂不可用（连接池满、网络抖动），递增退避给出恢复时间。

**为什么重试 3 次后丢弃？**
- 如果 MySQL 长时间不可用，重试 3 次和重试 100 次结果相同——都写不进去
- 与其无限重试占用线程，不如让对账修复（凌晨 3am）一次性处理
- 重试日志记录了每条数据的完整内容（key+delta），方便人工恢复

---

## 9. 优雅停机：@PreDestroy + GracefulShutdownHook

```java
@PreDestroy
public void shutdown() {
    flushLock.lock();   // 阻塞等待（不是 tryLock！）
    try { doFlush(); } finally { flushLock.unlock(); }
}
```

**lock() vs tryLock()**：

| 方法 | 修辞 | 场景 |
|------|------|------|
| `flush()` | `tryLock()` 非阻塞 | 运行时刷盘——如果有人在刷就不等了 |
| `shutdown()` | `lock()` 阻塞 | 停机刷盘——必须等，因为之后没有下次机会了 |

`GracefulShutdownHook.onShutdown()` 由 `GracefulShutdownListener` 在 Nacos 注销后调用——保证「先摘除流量 → 再刷盘 → 再停机」的顺序。

---

## 10. 性能分析与调优空间

### 合并效果验证（实测）

| 场景 | 写入次数 | Buffer Key 数 | SQL 次数 | 压缩比 |
|------|:---:|:---:|:---:|:---:|
| 3 次同 Key INCR | 3 | 1 | 1（1 行） | 3:1 |
| 100 次随机 Key | 100 | ~100 | 1（100 行） | 100:1 |
| 100 次热 Key（80% 命中前 10） | 100 | ~28 | 1（28 行） | ~3.6:1 |

### 已知限制

| 限制 | 影响 | 改进方向 |
|------|------|------|
| Buffer 全在内存 | 进程重启丢失未刷盘数据 | 对账修复兜底，可考虑 Write-Ahead Log |
| bufferSize 是近似计数 | 满量触发不是精确 100 条 | 可接受（定时 5s 兜底） |
| 单实例 Buffer | 多实例各自刷盘，无跨实例协调 | 符合设计意图（每实例 Buffer 独立） |
| 重试日志可能很大 | 大量数据以字符串形式写入日志 | 可限制每批最多记录前 100 条 |

---

## 关联文档

- `01-counter-module.md` — §5 CounterBuffer 架构概述
- `02-counter-test.md` — §1.1 Buffer 行为验证 / §3.1 攒批 DB 写入
- `04-reconcile.md` — 对账修复（Buffer 失败的最终兜底）
- `07-data-consistency.md` — 三层一致性（Buffer 是第一层）
