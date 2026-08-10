# Spring @Transactional + afterCommit 深度解析

> 以 `NoteService.publishNote()`（`NoteService.java:80-150`）为解剖对象。
> 
> **核心问题**：发布笔记需要在同一个操作中完成"数据库写入 + MQ 发送 + 缓存清除"。但 DB 事务和 MQ 不是原子操作——@Transactional 不管理 MQ。Spring 的 `TransactionSynchronization` 回调机制（afterCommit）和本地消息表模式，就是用来解决这个非原子性问题的。
>
> 从 Spring 源码层面解释：事务如何开始、两笔 INSERT 如何共享连接、afterCommit 何时触发、性能陷阱在哪里。

---

## 1. @Transactional 的幕后：代理 → 拦截器 → 事务管理器

### 1.1 你不是在调自己的方法

```java
// NoteService.java:80
@Transactional(rollbackFor = Exception.class)
public Long publishNote(Long userId, NotePublishRequest request) {
    checkSensitiveWords(...);
    // ...
}
```

当 `NoteController` 调用 `noteService.publishNote()` 时，Spring 不是直接调你的方法——而是调的一个**代理对象**。

```
Controller
  │
  ├─ noteService → 实际上拿到的是 CGLIB 代理对象
  │                  │
  │                  ├─ TransactionInterceptor.invoke()
  │                  │     │
  │                  │     ├─ 1. TransactionManager.getTransaction()
  │                  │     │     ├─ 检查 ThreadLocal（当前线程是否已有事务？）
  │                  │     │     ├─ 没有 → doBegin() → 从 HikariCP 拿连接
  │                  │     │     │         → connection.setAutoCommit(false)
  │                  │     │     │         → 绑定到 ThreadLocal
  │                  │     │     └─ 有（嵌套调用）→ 复用已有事务
  │                  │     │
  │                  │     ├─ 2. proceed() → 你的 publishNote() 方法
  │                  │     │     │
  │                  │     │     ├─ DFA 检测（纯内存）
  │                  │     │     ├─ noteMapper.insert() → 通过 ThreadLocal 拿到同一个连接
  │                  │     │     ├─ localMessageMapper.insert() → 同上
  │                  │     │     └─ registerSynchronization(afterCommit{...})
  │                  │     │
  │                  │     ├─ 3. commitTransactionAfterReturning()
  │                  │     │     ├─ triggerBeforeCommit()
  │                  │     │     ├─ connection.commit()       ← DB 提交
  │                  │     │     ├─ triggerAfterCommit()      ← 🎯 你的 afterCommit 回调
  │                  │     │     │     ├─ delayDoubleDelete()
  │                  │     │     │     └─ asyncSend MQ
  │                  │     │     └─ triggerAfterCompletion()
  │                  │     │
  │                  │     └─ 4. cleanTransactionInfo()
  │                  │           └─ connection.setAutoCommit(true)
  │                  │           └─ connection 归还 HikariCP
  │                  │           └─ 清除 ThreadLocal
  │                  │
  │                  └─ 如果抛异常 → rollbackOn()
  │                         ├─ 检查 RollbackRule（默认 RuntimeException 回滚）
  │                         └─ doRollback() → connection.rollback()
```

### 1.2 TransactionSynchronizationManager 的三层 ThreadLocal

Spring 的事务管理器不是全局单例，它的状态是**每线程独立**的，通过 3 个 `ThreadLocal` 实现：

```
TransactionSynchronizationManager
  ├─ resources: ThreadLocal<Map<DataSource, ConnectionHolder>>
  │     └─ 当前线程持有的数据库连接（key = DataSource, value = 连接）
  │
  ├─ synchronizations: ThreadLocal<Set<TransactionSynchronization>>
  │     └─ 你注册的回调对象（afterCommit、beforeCommit 等）
  │
  └─ currentTransactionName: ThreadLocal<String>
        └─ 当前事务名称（调试用）
```

这就是为什么同一个事务里的 `noteMapper.insert()` 和 `localMessageMapper.insert()` 能拿到**同一个连接**——它们都通过 `TransactionSynchronizationManager.getResource(datasource)` 获取，返回的是同一个 `ConnectionHolder`。

### 1.3 默认配置值

| 配置项 | 默认值 | 本模块的实际值 |
|--------|:-----:|:-----:|
| 传播级别 | `REQUIRED` | `REQUIRED`（未显式设置） |
| 隔离级别 | `DEFAULT`（= DB 默认） | MySQL InnoDB `REPEATABLE_READ` |
| 只读 | `false` | `false`（未设置 `readOnly`） |
| 超时 | -1（无限） | -1（**没有超时保护**） |
| 回滚策略 | RuntimeException + Error | `rollbackFor = Exception.class`（包括受检异常） |

---

## 2. publishNote 的事务设计分析

### 2.1 事务内的操作分类

```java
@Transactional(rollbackFor = Exception.class)    // ← 连接从这里被获取
public Long publishNote(Long userId, NotePublishRequest request) {
    
    checkSensitiveWords(...);          // [1] 内存操作 — 不需要连接
    
    Note note = buildNote(...);        // [2] 内存操作 — 不需要连接
    
    noteMapper.insert(note);           // [3] DB 操作 — 需要连接
    businessMetrics.recordFeedPush();   // [4] Micrometer Counter — 不需要连接
    
    // 构建 LocalMessage
    LocalMessage localMsg = ...;       // [5] 内存操作
    localMessageMapper.insert(localMsg);// [6] DB 操作 — 需要连接
    
    registerSynchronization(...);      // [7] 注册回调 — 不需要连接
    
    return note.getId();               // [8] 返回 — 连接在这里被释放
}
```

8 个步骤中只有 **2 个需要 DB 连接**：[3] 和 [6]。其余 6 个是纯内存操作。但在当前设计下，连接从 `@Transactional` 入口就被获取了——也就是说：

- `checkSensitiveWords()` 运行时连接已被占用但空闲
- `buildNote()` 运行时连接已被占用但空闲
- `registerSynchronization()` 运行时连接已被占用但空闲

**浪费了多少？** 假设 DFA ~0.1ms + buildNote ~0.1ms + buildEvent ~0.1ms = ~0.3ms 的连接空占时间。单次不可见，但 QPS 100 时，连接池 15 个连接，每个连接的 "有效利用率" 只有 ~60%（10ms 有用 / 12ms 总持有时长）。

### 2.2 为什么不能把 DFA/buildNote 挪到事务外？

如果拆成两部分：

```java
// 方案 A：拆成两个方法
public Long publishNote(userId, request) {
    checkSensitiveWords(...);           // 无事务
    Note note = buildNote(...);         // 无事务
    return doPublish(userId, note);     // 有事务
}

@Transactional
private Long doPublish(userId, note) {
    noteMapper.insert(note);            // 事务内
    localMessageMapper.insert(localMsg);// 事务内
    registerSynchronization(...);
    return note.getId();
}
```

**问题是**：`checkSensitiveWords()` 失败（抛异常），不会影响 `doPublish`——因为还没进事务。但如果 DFA 检测和 buildNote 之间产生了数据竞争（另一个线程改了用户权限/黑名单），检测时觉得能发，但 insert 时发现不能发——这时候需要在 insert 之前再做一次检查（Double Check 模式）。

**这个项目没这样做**——因为它把 DFA 放在事务内是最简单的实现，连接空占的开销可以接受。

### 2.3 两笔 INSERT 同一事务 vs 两笔分开事务

```
同一事务（当前实现）：
  INSERT t_note ─┐
  INSERT t_local_message ─┘ → 一起 commit → 一次网络往返

分开事务（如果不用 @Transactional）：
  INSERT t_note → commit → 1 次网络往返
  INSERT t_local_message → commit → 1 次网络往返
  + 两次 commit 不保证原子性
```

**同一事务是正确方案**——不仅性能更好（少一次 commit 往返），更重要的是保证了两笔 INSERT 的原子性。

---

## 3. afterCommit 的线程模型

### 3.1 回调链执行顺序

Spring 的 `TransactionSynchronization` 接口定义了 4 个回调时机：

```java
public interface TransactionSynchronization {
    default void beforeCommit(boolean readOnly) {}   // commit() 之前
    default void afterCommit() {}                     // commit() 之后 ← publishNote 用这个
    default void beforeCompletion() {}                // 事务结束之前（无论提交/回滚）
    default void afterCompletion(int status) {}       // 事务结束之后（无论提交/回滚）
}
```

完整执行顺序：

```
connection.commit()
  │
  ├─ 1. triggerBeforeCommit(synchronizations)
  │
  ├─ 2. connection.commit() 的执行
  │
  ├─ 3. triggerAfterCommit(synchronizations)    ← 🎯 你的代码在这里
  │     ├─ for each this.synchronizations:
  │     │       synch.afterCommit()
  │     │     ├─ publishNote 注册的:
  │     │     │     ├─ delayDoubleDelete(key)   ← 立即删 → delayScheduler.schedule(500ms后再次删)
  │     │     │     └─ asyncSend MQ             ← 异步，~1ms 返回
  │     │     └─ ...其他回调
  │
  └─ 4. triggerAfterCompletion(synchronizations, COMMITTED)
        └─ cleanTransactionInfo()
              ├─ connection.setAutoCommit(true)
              └─ connection 归还连接池
```

### 3.2 在哪个线程上执行？

`afterCommit` 与请求处理线程运行在**同一个线程**上。在标准 Tomcat + WebMVC 场景下，Controller 没有 `@Async`，所以整个调用链（Controller → Service → commit → afterCommit）都在同一个 Tomcat 工作线程上。

但关键在于：`afterCommit` 执行时，HTTP 响应**已经发送给用户了**。所以即使 `afterCommit` 中有 `delayDoubleDelete` 的第二次删除（通过 `ScheduledExecutorService` 异步调度），用户感知的延迟只到 `return note.getId()` 那一刻。

```
Tomcat 线程寿命：
  │
  ├─ 接收 HTTP 请求
  ├─ Controller → Service → @Transactional 方法
  ├─ commit
  ├─ afterCommit（响应已发送）
  │     ├─ delayDoubleDelete（立即删 + schedule 500ms 后删）   ← 线程立即释放
  │     └─ asyncSend MQ（~1ms）
  └─ 线程归还线程池
```

### 3.3 旧实现回顾：afterCommit 中 sleep 的问题（已修复）

**旧版代码**曾在 `afterCommit` 中直接 `Thread.sleep(100ms)`：

```
旧版 delayDoubleDelete:
  delete → sleep(100ms) → delete  ← 在 afterCommit 线程上阻塞
```

这导致每个请求的 Tomcat 线程在 afterCommit 中多存活 100ms，降低线程池利用率。

**当前实现**（`CacheHelper.java`）已改为 `ScheduledExecutorService.schedule()`：

```java
delayScheduler.schedule(() -> delete(key), 500ms, MILLIS);
```

afterCommit 线程立即释放——不再阻塞。详见 `06-cache-strategy/01-cache-aside-delete.md` §3.4。

---

## 4. 连接持有时间分析

### 4.1 单次请求的时间线

```
T=0ms    @Transactional 入口 → doBegin() → HikariCP 借出连接
T=0.1ms  checkSensitiveWords()       ← 连接空闲，线程在工作
T=0.2ms  buildNote()                 ← 连接空闲，线程在工作
T=5ms    noteMapper.insert()         ← 连接在工作（SQL 执行 + 网络）
T=5.1ms  recordFeedPush()            ← 连接空闲
T=5.2ms  buildEvent()                ← 连接空闲
T=10ms   localMessageMapper.insert() ← 连接在工作
T=10.1ms registerSynchronization()    ← 连接空闲
T=10.1ms return note.getId()         ← 🎯 用户拿到响应
T=15ms   commit()                    ← DB 提交，连接被释放
---
T=15ms+  afterCommit: delayDoubleDelete → 立即删 + schedule(500ms后再删)
T=115ms+ afterCommit: asyncSend → ~1ms 返回
T=115ms+ 连接归还 HikariCP
```

**用户感知延迟 = 10ms**（从入口到 return）。**连接持有时间 = 15ms**（到 commit 完成）。
`afterCommit` 立即返回（延迟删由 ScheduledExecutorService 异步处理），不再长时间占用请求线程。

### 4.2 不同 QPS 下的连接池压力

| QPS | 连接池大小 | 每连接占用 15ms | 所需连接数 | 剩余 |
|:---:|:---------:|:---------------:|:----------:|:---:|
| 50 | 15 | 50 × 0.015 = 0.75 | ~1 | 充足 |
| 100 | 15 | 100 × 0.015 = 1.5 | ~2 | 充足 |
| 500 | 15 | 500 × 0.015 = 7.5 | ~8 | 充足 |
| 1000 | 15 | 1000 × 0.015 = 15 | ~15 | 刚好 |
| 2000 | 15 | 2000 × 0.015 = 30 | ~30 | **不足** |

当前 HikariCP `max-active=15`，单个事务连接持有 ~15ms。2000 QPS 时理论上需要 30 个连接。实际中事务延迟有波动（GC、MySQL 负载），15 个连接在高 QPS 下会出现排队。

---

## 5. 隔离级别：REPEATABLE_READ vs READ_COMMITTED

### 5.1 当前行为

MySQL InnoDB 默认 `REPEATABLE_READ`。在这个隔离级别下：

- **可重复读**：事务内的两次 `SELECT` 看到同一数据快照（通过 MVCC）
- **间隙锁**：`INSERT` 时对插入位置前后的间隙加锁，防止幻读
- **副作用**：高并发 INSERT 到同一张表时，间隙锁可能导致死锁

`publishNote` 只做 INSERT 不做 SELECT，所以 MVCC 快照对它不产生实际影响。但 `INSERT` 时会加间隙锁——如果两个并发 INSERT 要插入到相邻位置，可能产生冲突。

### 5.2 为什么 READ_COMMITTED 更适合这个场景

```
REPEATABLE_READ:
  INSERT INTO t_note → 加间隙锁 (id范围) → 其他 INSERT 到相邻 ID → 等待

READ_COMMITTED:
  INSERT INTO t_note → 不加间隙锁 → 其他 INSERT 到相邻 ID → 并发执行
```

在基于 Binlog 复制的 MySQL 架构下，`READ_COMMITTED` 和 `ROW` 格式的 binlog 配合使用是安全的，且性能更好（无间隙锁开销）。阿里巴巴的 MySQL 规范推荐业务场景用 `READ_COMMITTED`。

### 5.3 当前代码的隔离级别

没有显式设置——所以用的是 InnoDB 默认的 `REPEATABLE_READ`。**不需要改**，因为 `publishNote` 只有 INSERT 没有 SELECT + UPDATE 的条件竞争，但这是一个需要知道的"默认行为"。

---

## 6. 性能陷阱

### 6.1 delayDoubleDelete 第二次删除的异步调度

```java
afterCommit() {
    cacheHelper.delayDoubleDelete(key);  // 立即删 → delayScheduler.schedule(500ms后再次删)
    asyncSend MQ;                        // ~1ms
}
```

`delayDoubleDelete` 立即执行第一次删除，第二次删除通过 `ScheduledExecutorService` 延迟 500ms 异步调度——不阻塞 `afterCommit` 线程。改进前（`Thread.sleep(100)` 的实现）会阻塞线程 100ms，当前版本已修复。

但如果 100 个并发 publishNote 都会触发 `afterCommit`，虽然立即返回，ScheduledExecutorService 只有一个线程（`singleThread`），第二次删除会排队执行——不过 500ms 的延迟足够这些任务分散调度，不会构成瓶颈。

**改进方向**（已实现）：
- 第二次删除已使用 `ScheduledExecutorService` 异步调度 → 不阻塞 afterCommit 线程
- 或者设置 Redis Key 的 TTL 极小（如 200ms），让缓存自然过期代替第二次删除

### 6.2 t_local_message 表的读写竞争

- **写路径**：每篇笔记发布 → 1 条 INSERT
- **读路径**：`retryFailedMessages()` 每 30 秒扫描 `WHERE status=0 AND ...`
- **写路径**：`markSent()` / `incrementRetry()` 更新已有行

正常流量下，扫描只命中少数量（status=0 的通常是最近发送失败的消息）。但如果 MQ Broker 宕机，status=0 的行不断堆积，定时任务每次扫描 100 条进行同步重试——每个重试都是一次 `syncSend` + `UPDATE`，会显著增加 DB 负载。

### 6.3 事务超时保护缺失

```java
@Transactional(rollbackFor = Exception.class)  // 没有 timeout！
```

当前 `publishNote` 的事务永远不会因为超时而中断。如果某些原因导致 JDBC 连接卡住（MySQL 主节点故障等），Tomcat 线程可能永久阻塞在事务内部。

标准做法：
```java
@Transactional(rollbackFor = Exception.class, timeout = 30)  // 30秒超时，超时自动回滚
```

### 6.4 Tomcat 线程在 afterCommit 后是否及时释放

`delayDoubleDelete` 已改为 `ScheduledExecutorService` 异步调度——`afterCommit` 立即返回，不阻塞 Tomcat 线程。`asyncSend`（~1ms）的发送也在瞬间完成。Tomcat 线程可以快速接下一个请求。

---

## 7. 生产检查清单

| 检查项 | 当前状态 | 建议 |
|--------|:------:|------|
| 事务超时 | 无限制 | 加 `timeout = 30` |
| 连接池大小 | 15（默认） | QPS > 1000 时调至 25-30 |
| 隔离级别 | REPEATABLE_READ | 确认 binlog_format=ROW，可改 READ_COMMITTED |
| 间隙锁监控 | 无 | 开启 InnoDB 锁监控（`innodb_status_output_locks=ON`） |
| afterCommit 线程池 | 复用请求线程 | 考虑独立线程池执行 `delayDoubleDelete` |
| 事务内无外部调用 | ✅ | DFA/buildNote 是纯内存，没有 RPC/HTTP 调用 |
| 连接泄漏检测 | 无 | HikariCP 自带 `leakDetectionThreshold` |
| 死锁检测 | 无 | MySQL `innodb_print_all_deadlocks=ON` |

---

## 8. 总结

`publishNote` 的事务设计在**正确性上是合理的**：

- 两笔 INSERT 同一事务保证原子性
- 本地消息表与笔记入库不可分割
- afterCommit 的正确时序（commit 后才发 MQ）

在**可改进的方面**：

- DFA/buildNote 从内存角度看可以挪到事务外，减少连接空占比
- `delayDoubleDelete` **已改为 `ScheduledExecutorService` 异步调度**（不阻塞 afterCommit 线程）
- 缺少事务超时保护
- 隔离级别默认为 REPEATABLE_READ，可评估改为 READ_COMMITTED

但总体来说——**在 QPS < 500 的场景下，当前设计没有问题**。如果上生产，加上超时和连接池监控即可。
