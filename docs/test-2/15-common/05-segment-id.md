# Common 号段 ID 生成器（双 Buffer）— 深度技术分析

> 关联源码：`SegmentIdGenerator.java` / `IdGeneratorUtil.java`

---

## 业务背景

微服务需要全局唯一 ID，方案对比：

| 方案 | 唯一性 | 趋势递增 | DB 依赖 | 性能 | 时钟依赖 |
|---|---|---|---|---|---|
| 数据库自增 | ✅ | ✅ | 每次 INSERT 都依赖 | 低 | 无 |
| 雪花算法 | ✅ | ✅ | 无 | 高 | 有时钟回拨风险 |
| **号段模式** | ✅ | ✅ | 批量获取（低频） | 高 | 无 |
| UUID | ✅ | ❌ | 无 | 高 | 无（但无序） |

**雪花算法的问题**：依赖机器时钟。时钟回拨（NTP 校准/运维手动改时间）会导致 ID 重复或乱序。号段模式不依赖时钟，ID 从 DB 号段中顺序分配。

---

## 号段原理

```
t_id_segment 表：
+---------+--------+------+---------+
| biz_tag | max_id | step | version |
+---------+--------+------+---------+
| order   | 10000  | 1000 | 5       |
+---------+--------+------+---------+

第一次取号段：
  SELECT max_id=10000, version=5
  UPDATE max_id=11000, version=6 WHERE version=5  ← 乐观锁
  内存中拿到区间 (10000, 11000]

业务取 ID：10001, 10002, ..., 10999, 11000
用完后再取下一个号段 (11000, 12000]
```

**特点**：
- DB 只在号段耗尽时被访问（每 1000 个 ID 一次 UPDATE）
- 乐观锁（version 字段）防止多实例并发取号段冲突
- 每实例缓存的号段互不重叠 → 全局唯一

---

## 双 Buffer 机制

### 为什么需要双 Buffer

单 Buffer 的问题：号段用完时，需要同步查 DB 取新号段。这段时间内业务取 ID 会被阻塞（DB 往返 5-10ms）。

双 Buffer 预加载：当前号段用到 70% 时，异步把下一个号段加载到 next Buffer。切换时无缝衔接。

### 结构

```
DoubleBuffer
├── current（当前号段）─→ 业务从这里取 ID
└── next（预加载号段）──→ 异步加载中

切换时：current ↔ next 交换引用（O(1)）
```

### 预加载触发

```java
// 当前 ID 达到阈值（号段剩余 30%）时触发
long threshold = current.maxId - (long) (current.step * 0.3);
if (id >= threshold && !next.loaded && !preloading) {
    triggerPreload(bizTag);  // 异步加载 next
}
```

### 切换逻辑

```java
// 号段用完 → 加锁 → 双重检查
if (current.currentId.get() > current.maxId) {
    if (next.loaded) {
        // next 已就绪，交换引用
        Segment temp = current;
        current = next;
        next = temp;
        next.reset();          // 清空旧的 current（现在成了 next），准备下次预加载
        preloading = false;
    } else {
        // next 未就绪（预加载失败/太慢）→ 降级同步加载
        loader.load(bizTag, current);
        preloading = false;
    }
}
```

**为什么双重检查**：多个线程可能同时发现号段用完。第一个线程加锁完成切换后，其他线程重入循环时 `current.currentId.get() <= current.maxId` 已成立（新号段），不会重复切换。

---

## 并发安全

| 机制 | 说明 |
|---|---|
| AtomicLong | 当前 ID 原子自增，无锁取号 |
| ReentrantLock | 号段切换/预加载触发时加锁 |
| volatile | current/next/preloading 引用可见性 |
| preloading 标志 | volatile + ReentrantLock 双重检查，防止重复提交预加载任务 |

---

## 降级路径

| 场景 | 行为 |
|---|---|
| 预加载异步失败 | `preloading=false`，下次触发时重试 |
| 号段用完时 next 未就绪 | 同步加载（业务线程等待一次 DB 往返） |
| 乐观锁冲突 | 重试 3 次 |
| 乐观锁 3 次都失败 | 抛异常（业务感知） |
| bizTag 未初始化 | 抛异常并提示 INSERT 语句 |

---

## 面试 Q&A

**Q: 号段模式和雪花算法怎么选？**
A: 雪花算法无 DB 依赖、性能最高，但有时钟回拨风险。号段模式牺牲少量 DB 依赖（每 step 个 ID 一次），换取无时钟风险 + 强趋势递增。需要严格趋势递增（如订单号、消息 ID 排序）时选号段。

**Q: 多实例部署时号段会重复吗？**
A: 不会。每次取号段都 UPDATE max_id（乐观锁），各实例拿到的区间不重叠。例如实例 A 拿 (10000,11000]，实例 B 拿 (11000,12000]。

**Q: 预加载失败会不会影响业务？**
A: 不会阻塞业务（异步失败只重置标记）。但当号段真正用完且 next 未就绪时，会降级为同步加载——业务线程等一次 DB 往返（约 5-10ms）。极端情况（DB 不可用）下取号失败抛异常。

**Q: 为什么要双 Buffer 而不是三 Buffer？**
A: 双 Buffer 已覆盖主路径：current 消耗时 next 在预加载。三 Buffer 没有额外收益——current 切换后，旧 current 立刻变成 next 被重新加载。

---

## 发散

### 号段步长动态调整

当前 step 固定 1000。高 QPS 业务（如消息 ID）可以调大 step（如 10000），减少 DB 访问频率；低 QPS 业务可以调小。步长过大的缺点是实例崩溃时浪费号段（未使用的 ID 丢弃）。

### 混合策略

雪花算法 + 号段结合：主 ID 用雪花（无 DB 依赖），订单号/消息序号用号段（严格趋势递增）。各取所长。
