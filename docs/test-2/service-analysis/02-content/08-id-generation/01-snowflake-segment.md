# 分布式 ID 生成：雪花 + 号段 + Redis 自增

> `IdGeneratorUtil.java`（72 行）+ `SegmentIdGenerator.java`（270 行）
> 三种 ID 生成策略共存于一个项目，按业务场景选择。
> **前置阅读**：`04-transaction-aftercommit/` — ID 在事务内通过 `idGeneratorUtil.nextId()` 显式生成，而非依赖 MyBatis-Plus 的 `insert` 后回填。

---

## 1. 为什么不能用数据库自增 ID？

数据库自增（`AUTO_INCREMENT`）在单机 MySQL 上是简单方案，但分布式环境有四个致命问题：

**问题 1：分库分表时 ID 冲突**

```
实例 A: INSERT → AUTO_INCREMENT = 1001
实例 B: INSERT → AUTO_INCREMENT = 1001   ← 冲突！两个库各自自增到 1001
```

order 服务使用 ShardingSphere 4 库 × 4 表，总共 16 张分表。每张表独立自增 → 跨表 ID 必然冲突。

**问题 2：主从切换时 ID 跳号**

```
Master 宕机：AUTO_INCREMENT = 5000
Slave 升级为 Master：AUTO_INCREMENT = 5300（比 Master 多 300，因为复制的 binlog 包含了部分未执行的自增）
结果：501-5299 范围的 ID 没有被使用过，但也不能再用。
```

**问题 3：DB 单点写入瓶颈**

所有 INSERT 都依赖 DB 的 `LAST_INSERT_ID()` → 无论业务数据写到哪个分片，ID 生成都集中在一个 MySQL 实例上 → 成为全链路瓶颈。

**问题 4：无法提前获取 ID 做其他用途**

`publishNote` 需要在 `afterCommit` 的 MQ 消息里用到 `note.getId()`。如果 ID 在 `insert` 后才生成，MQ 消息就构建不了。

---

## 2. 雪花算法（Snowflake）— 笔记/评论/订单的主力

### 2.1 位结构

Twitter 2010 年提出的分布式 ID 方案。MyBatis-Plus 内置了实现。

```
Snowflake ID 是一个 64-bit Long:

┌─┬──────────────────────────┬──────────┬──────────┐
│0│     41-bit 毫秒时间戳     │10-bit Worker│12-bit 序号│
└─┴──────────────────────────┴──────────┴──────────┘
 1-bit                                         4096/ms

 41-bit = 69 年（从基准时间开始）
 10-bit = 1024 个 Worker（机器/进程）
 12-bit = 每毫秒 4096 个 ID
```

**为什么 1-bit 永远是 0？** Long 是带符号类型，最高位 1 表示负数。恒设 0 保证所有 ID 都是正数——方便存入 `BIGINT UNSIGNED` 列和 JSON 序列化。

### 2.2 实际生成的 ID 拆解

```
我们实际发布的笔记 ID: 2078408307372003329

二进制（64-bit）：
┌┬──────────────────────────┬──────────┬────────────┐
│0│ 000111011001... (41bit) │ 000110... │ 0000000001 │
│ │ 时间部分                 │ Worker   │ 序号       │
└┴──────────────────────────┴──────────┴────────────┘

十进制：2078408307372003329
```

### 2.3 MyBatis-Plus 的 IdWorker

```java
// IdGeneratorUtil.java:38
public long nextId() {
    return com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
}
```

`IdWorker.getId()` 做了三件事：

1. 从 `System.currentTimeMillis()` 获取当前时间戳，减去基准时间（`2020-11-04` 左右，Twitter 原始基准）
2. 自动检测 WorkerId（基于 MAC 地址 + JVM PID 的哈希，避免手动配置）
3. 同一毫秒内的序号自增（0~4095），超过 4095 则等待下一毫秒

**publishNote 是怎么用它的？**

```java
// NoteService.java:466
private Note buildNote(Long userId, NotePublishRequest request) {
    Note note = new Note();
    note.setId(idGeneratorUtil.nextId());   // ← 显式调用，不是等 insert 后回填
    note.setUserId(userId);
    // ...
}
```

**为什么不等 MyBatis-Plus insert 后自动生成？** 因为 `afterCommit` 的 MQ 消息（`NotePublishEvent`）需要 `note.getId()`。如果等 `insert` 后才回填，MQ 消息里没有 noteId。

### 2.4 优缺点

| 优点 | 缺点 |
|------|------|
| 本地生成，不依赖 DB/Redis，极高吞吐 | ID 是 19 位 Long，URL 中较长 |
| 趋势递增，对 MySQL 聚簇索引友好 | 依赖系统时钟——时钟回拨会出问题 |
| 无需额外组件 | WorkerId 的自动检测在容器环境下可能重复 |

**时钟回拨**：如果 NTP 同步把时钟往回拨了 1 秒，`IdWorker` 会在同一时间戳内把序号从 0 重新开始 → 可能产生和之前重复的 ID。

MyBatis-Plus 的做法是：如果检测到时钟回拨，`Thread.sleep` 直到时钟追上之前的时间再生成。这在 NTP 小步调整（几十毫秒）时免伤，但大跨度回拨（管理员手动改时间）会直接抛异常。

---

## 3. 号段模式（Segment）— 短 ID 场景

### 3.1 原理

号段模式不每次生成 ID 都查 DB，而是**一次取一批（一个"号段"），缓存在内存里慢慢用**。用完了再去 DB 取下一批。

```
DB 中维护一张号段分配表：
  t_id_segment
  ├─ biz_tag = 'user'   → max_id=11000, step=1000, version=3
  └─ biz_tag = 'order'  → max_id=5000,  step=1000, version=5

应用启动后：
  1. SELECT max_id, step, version FROM t_id_segment WHERE biz_tag = 'user'
     → max_id=10000, step=1000, version=2

  2. UPDATE t_id_segment SET max_id=10000+1000=11000, version=3
     WHERE biz_tag='user' AND version=2              ← 乐观锁

  3. 号段缓存：currentId=10000, maxId=11000
     应用依次返回：10001, 10002, 10003... 11000

  4. 号段用到 70%（约 10700）→ 异步预加载下一个号段
     下一个号段：11001~12000（从 DB 拿到 max_id=11000→12000）
```

### 3.2 双 Buffer 设计

`SegmentIdGenerator` 使用**双号段交替切换**：

```java
// SegmentIdGenerator.java:183-201
private static class DoubleBuffer {
    volatile Segment current = new Segment();   // 当前使用的号段
    volatile Segment next = new Segment();       // 预加载的下一个号段
    volatile boolean preloading = false;          // CAS 标记，防重复提交
    // ...
}

long nextId(String bizTag) {
    while (true) {
        long id = current.currentId.incrementAndGet();   // AtomicLong 自增
        if (id <= current.maxId) {
            // 正常范围内 → 检查是否需要预加载
            if (id >= threshold) triggerPreload(bizTag);
            return id;
        }
        // 号段用完 → 锁 → 切换
        lock.lock();
        if (next.loaded) {
            Segment temp = current;
            current = next;     // current ↔ next 交换
            next = temp;
            next.reset();
        } else {
            // next 未就绪 → 降级为同步加载
            loader.load(bizTag, current);
        }
        lock.unlock();
    }
}
```

**为什么是 70% 预加载而不是 90%？** 如果设 90%，意味着只剩 10% 的号段时间去加载下一个号段。如果 DB 查询延迟抖动（比如需要 100ms），号段可能在预加载完成之前就用完了 → 线程阻塞等待。70% 给了 30% 的安全缓冲，在不浪费内存的前提下最大化预加载成功率。

**DB 更新使用乐观锁 `WHERE version = ?`**：两个应用实例同时申请下一个号段 → 只有一个能更新成功（version 匹配）→ 另一个重试（最多 3 次）。保证每个号段只分配给一个实例。

### 3.3 为什么 publishNote 不用号段？

- 号段依赖 DB（每次取号段要查 t_id_segment + 乐观锁更新）
- 雪花算法**完全不依赖外部组件**——纯 JVM 内存生成
- 笔记/评论是高频创建操作，追求最高吞吐
- 19 位 ID 长但笔记场景不需要"短 ID"（用户不直接看 noteId）

号段模式适合**用户 ID**（需要短 ID 做 URL `user/10001`）和**需要严格递增**的场景。

---

## 4. Redis 自增 — 流水号

```java
// IdGeneratorUtil.java:62
public String nextSerialNo(String prefix) {
    String dateStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    String key = "id:serial:" + prefix + ":" + dateStr;
    Long seq = stringRedisTemplate.opsForValue().increment(key);
    if (seq == 1) {
        stringRedisTemplate.expire(key, 2, TimeUnit.DAYS);   // 首次创��设 TTL
    }
    return prefix + dateStr + String.format("%06d", seq);
}
```

生成的流水号格式：`PAY20260723000001`（前缀 + 日期 + 6 位序号）。

**适用场景**：支付流水号、退款单号——需要一个人类可读的、包含业务信息的标识。

**Redis INCR 的可靠性**：Redis 单线程命令，`INCR` 是原子的——不需要分布式锁。但 Redis 宕机会丢失当前计数器值（AOF 持久化有窗口）。

**expire 只在 seq==1 时设置**：避免每次调用都做一次多余的 `EXPIRE` 网络往返。Key 创建时设 2 天 TTL，够跨天结算��了。

---

## 5. content 模块实际用的哪一种？

```java
// NoteService.java:466  — buildNote()
note.setId(idGeneratorUtil.nextId());   // 雪花算法

// CommentService.java  — createComment()
comment.setId(idGeneratorUtil.nextId()); // 雪花算法

// LocalMessage.java:23  — Entity 定义
@TableId(type = IdType.ASSIGN_ID)        // 让 MyBatis-Plus 在 insert 时自动生成（也是雪花）
private Long id;
```

| 实体 | 生成方式 | 原因 |
|------|---------|------|
| Note | `IdGeneratorUtil.nextId()` 显式调用 | afterCommit MQ 需要提前拿到 ID |
| Comment | `IdGeneratorUtil.nextId()` 显式调用 | 同上 |
| LocalMessage | `@TableId(ASSIGN_ID)` MyBatis-Plus 自动 | 不需要提前拿到 ID，insert 时自动生成即可 |

---

## 6. 时钟回拨：三种 ID 谁受影响？

| 策略 | 时钟回拨影响 | 缓解措施 |
|------|:--:|------|
| 雪花（MyBatis-Plus） | **受影响**—同一时间戳可能生成重复 ID | IdWorker 内置 `Thread.sleep` 等时钟追上；大跨度回拨抛异常 |
| 号段模式 | **不受影响**—ID 是纯自增数字，不依赖时钟 | 无 |
| Redis 自增 | **不受影响**—依��� Redis INCR，不依赖本地时钟 | Redis 宕机丢计数器 |

**这个项目怎么处理？** 没有额外措施。MyBatis-Plus IdWorker 的默认时钟回拨处理是"等待 + 抛异常"。对于训练营项目，这足够——生产环境建议引入 CosId（基于 ClockSync 的优化雪花算法）或直接用号段模式规避时钟依赖。

---

## 7. 总结

| 策略 | 数据结构 | 依赖 | 适用 | content 用了吗 |
|------|---------|------|------|:--:|
| 雪花算法 | 64-bit Long | 系统时钟 + WorkerId | 笔记/评论/订单 | 是 |
| 号段模式 | DB t_id_segment + 乐观锁 | MySQL | 用户 ID（短 ID） | 否（user 模块实际也用雪花） |
| Redis 自增 | String + 日期前缀 | Redis INCR | 支付流水号 | 否 |

三种策略在同一套代码里共存，按场景选择——没有"最好的 ID 方案"，只有"最适合当前业务的方案"。
