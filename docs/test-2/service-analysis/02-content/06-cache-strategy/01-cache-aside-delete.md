# Cache Aside + 延迟双删 + 三级缓存一致性保障

> `my-xhs-common/src/main/java/com/myxhs/common/cache/CacheHelper.java`（383 行）
> 一个封装了读（Cache Aside + Singleflight 防击穿）、写（延迟双删 + MQ 兜底）、防穿透/雪崩的完整缓存体系。
> **前置阅读**：`04-transaction-aftercommit/` — `delayDoubleDelete` 在 `afterCommit` 回调中执行，需要理解事务生命周期。

---

## 1. 缓存是干什么的？

每次 `publishNote` 之后，`getUserNotes`、`getNoteDetail` 等读接口会频繁访问 `t_note` 表。如果每次都查 DB，DB 会被读流量打穿。

缓存的核心思想是：**把查过的数据暂存在 Redis 里，下次同样的请求不查 DB，直接从 Redis 读**。

```
用户请求 → 查 Redis → 有 → 直接返回（~0.5ms）
                    → 无 → 查 DB（~5ms）→ 写入 Redis → 返回
```

但缓存引入了一个新问题：**数据一致性**。如果 DB 里的数据变了，Redis 里还存着旧的值，用户看到的就是过期数据。

---

## 2. Cache Aside — 读路径

### 2.1 标准版 — `getWithCacheAside()`

```java
// CacheHelper.java:101
public <T> T getWithCacheAside(String key, Supplier<T> dbFallback, 
                                long timeout, TimeUnit unit) {
    // 1. 查缓存
    T cached = redisOperator.get(key);
    if (cached != null) {
        if (isNullPlaceholder(cached)) return null;   // 缓存了空值 → 防穿透
        return cached;                                  // 命中 → 返回
    }

    // 2. 未命中 → 查 DB（Lambda 传入）
    T dbResult = dbFallback.get();

    // 3. 回填缓存
    if (dbResult != null) {
        long offset = ThreadLocalRandom.current().nextLong(0, timeout/6 + 1);
        redisOperator.set(key, dbResult, timeout + offset);     // TTL + 随机偏移
    } else {
        redisOperator.set(key, NULL_PLACEHOLDER, 2, MINUTES);   // 空值缓存 2 分钟
    }

    return dbResult;
}
```

**用法**（`NoteService.java:332-344`）：

```java
private User getUserFromCache(Long userId) {
    return cacheHelper.getWithCacheAside(
        "myxhs:user:info:" + userId,
        () -> userMapper.selectOne(eq(User::getId, userId).select(/* 排除 password */)),
        30, TimeUnit.MINUTES
    );
}
```

`Supplier<T>` 的 Lambda 写法让调用方只需关心 "查 DB 的逻辑是什么"，缓存命中/未命中/回填的逻辑全封装在 `CacheHelper` 内。

### 2.2 标准版的行为

```
getWithCacheAside(key, dbFallback, 30min)
│
├─ Redis.GET(key) → 命中 → 返回（~0.5ms）
│
├─ Redis.GET(key) → 未命中
│     ├─ dbFallback.get() → 查 DB（~5ms）
│     ├─ DB 有结果 → Redis.SET(key, data, 30min + random(0~5min))
│     └─ DB 没结果 → Redis.SET(key, NULL_PLACEHOLDER, 2min)
│                      ← 防穿透：不存在的 ID 也被缓存，不反复查 DB
│
└─ Redis 不可用 → RedisUnavailableException
      └─ 降级：直接查 DB，不回填缓存 ← 避免加重 Redis 压力
```

### 2.3 Singleflight 防击穿 — `getWithCacheAsideLock()`

标准版有一个缺陷：缓存失效的瞬间，如果有 100 个并发请求同时 miss，**100 个请求都会去查 DB**——这叫**缓存击穿**。

```java
// CacheHelper.java:158
public <T> T getWithCacheAsideLock(String key, Supplier<T> dbFallback, 
                                    long timeout, TimeUnit unit) {
    // 1. 查缓存（同标准版）
    T cached = redisOperator.get(key);
    if (cached != null) return handleNullPlaceholder(cached);

    // 2. 缓存未命中 → 抢分布式锁
    String lockKey = "lock:cache:" + key;
    RLock lock = redissonClient.getLock(lockKey);
    boolean acquired = lock.tryLock(3, 10, SECONDS);

    if (acquired) {
        // 3. 抢到锁 → Double Check → 查 DB → 回填 → 解锁
        T doubleCheck = redisOperator.get(key);       // ← 可能别的线程已经回填了
        if (doubleCheck != null) return doubleCheck;
        T dbResult = dbFallback.get();
        redisOperator.set(key, dbResult, timeout + offset);
        return dbResult;
    } else {
        // 4. 没抢到锁 → 等 100ms → 重试读缓存 → 仍未命中则降级查 DB
        Thread.sleep(100);
        T retry = redisOperator.get(key);
        return retry != null ? retry : dbFallback.get();
    }
}
```

**100 个并发请求 miss 时的行为**：

```
请求 1: miss → 抢到锁 → 查 DB → 回填缓存 → 解锁
请求 2-100: miss → 抢锁失败 → sleep 100ms → 重试缓存 → 命中！→ 返回
```

只有 **1 个请求** 查了 DB，其他 99 个从缓存读到。这就是 Singleflight 模式——源于 Go 语言的 `golang.org/x/sync/singleflight`，本质是"同一时刻对同一 key 只执行一次 DB 查询"。

**`publishNote` 用哪个？** 用标准版 `getWithCacheAside`。因为笔记详情查询不是极端热点——QPS 不太可能一个并发 miss 打爆 DB。Singleflight 版是给秒杀/热门商品详情这种场景准备的。

---

## 3. 延迟双删 — 写路径

### 3.1 为什么直接删不行？

最简单的"写后删"：

```java
noteMapper.update(note);       // 更新 DB
redisOperator.delete(cacheKey); // 删缓存
```

但在并发环境下：

```
时间线：
  T1: 请求 A 更新 DB（status 2→3）
  T2: 请求 B 查缓存 → miss → 查 DB → 此时 A 还没删缓存！
  T3: 请求 A 删缓存
  T4: 请求 B 回填缓存 → 把 T2 查到的旧数据写入了缓存
  T5: 后续请求查到缓存中的旧数据
```

这就是**并发读回填**问题——删缓存和查 DB 之间的竞态窗口。正确执行顺序应该是：更新 DB → 删缓存 → 并发读 miss → 重新查 DB → 回填新数据。但如果并发读在"删缓存"之前就查了 DB，它读到的是旧数据。

### 3.2 双删如何解决

```java
// CacheHelper.java:313
public void delayDoubleDelete(String key) {
    // 第一次删除：立即清除当前缓存
    redisOperator.delete(key);

    // 第二次删除：等待 500ms 后再次删除
    delayScheduler.schedule(() -> {
        redisOperator.delete(key);
    }, doubleDeleteDelayMs, TimeUnit.MILLISECONDS);
}
```

回到上面的并发场景，加上双删后：

```
T1: 请求 A 更新 DB（数据变成 new）
T2: 请求 A 第一次删缓存（缓存清除）
T3: 请求 B 查缓存 → miss → 查 DB → 此时 DB 已经是 new
T4: 请求 B 回填缓存 → 把 new 写入了缓存 ← 不需要第二次删来纠正
---
另一种情况（主从延迟）：
T1: 请求 A 更新主库（数据变成 new）
T2: 请求 A 第一次删缓存
T3: 请求 B 查缓存 → miss → 查从库 → 此时从库还是 old！（主从延迟）
T4: 请求 B 回填缓存 → 把 old 写入了缓存 ← 脏数据！
T5: 请求 A 第二次删缓存（500ms 后）→ 清除 old ← 纠正
```

**第二次删除不是多余的**——它覆盖了并发读从从库读到旧数据的窗口。

### 3.3 为什么是 500ms？

```java
@Value("${myxhs.cache.double-delete-delay-ms:500}")
private long doubleDeleteDelayMs;
```

代码注释给出了公式：**主从同步延迟（~200ms）+ 业务读耗时（~100ms）+ 安全余量（~200ms）= 500ms**。

这 500ms 是估算值。更精确的做法是 Canal 监听 binlog → 删缓存（不受延迟窗口限制），但代价是引入 Canal 组件。延迟双删是一个工程上的折中——简单、够用、不需要额外组件。

### 3.4 改进了 sleep 100ms——用了 `ScheduledExecutorService`

之前 04 文档提到的 `Thread.sleep(100)` 问题，实际代码已经改了——用 `ScheduledExecutorService` 异步调度第二次删除：

```java
private final ScheduledExecutorService delayScheduler =
    Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cache-delay-delete");
        t.setDaemon(true);              // 守护线程，不阻止 JVM 退出
        return t;
    });

delayScheduler.schedule(() -> {
    redisOperator.delete(key);
}, doubleDeleteDelayMs, TimeUnit.MILLISECONDS);
```

**这个改进解决了什么？** 之前的 `Thread.sleep(100)` 会在 `afterCommit` 线程上阻塞 100ms。现在 sleep 移到独立线程池——`afterCommit` 的线程立刻释放，不阻塞事务提交线程。

---

## 4. 三级缓存一致性

```
L1: deleteAfterUpdate     → 立即删除，重试 3 次（间隔 50ms）
L2: delayDoubleDelete     → 500ms 后再次删除（异步 ScheduledThread）
L3: MQ CACHE_EVICT_TOPIC  → Redis 不可用或二次删除失败时，发 MQ 兜底
```

### 4.1 L1 — `deleteAfterUpdate()`

```java
public boolean deleteAfterUpdate(String... keys) {
    for (String key : keys) {
        for (int i = 0; i < 3; i++) {
            boolean success = redisOperator.delete(key);
            if (success) break;
            Thread.sleep(50);
        }
        // 3 次重试都失败 → 等 L3 MQ 兜底
    }
}
```

用于关键写操作（如 `updateUserInfo()`、`changePassword()`）的即时删除。

### 4.2 L3 — MQ 第二次删除失败兜底

```java
// 在 delayDoubleDelete 内：
try {
    redisOperator.delete(key);       // 第二次删除
} catch (Exception e) {
    rocketMQTemplate.syncSend("CACHE_EVICT_TOPIC", key, 3000);
}
```

如果第二次删除时 Redis 不可达，L2 失败 → L3 激活 → 发消息到 `CACHE_EVICT_TOPIC`。`CacheEvictConsumer`（user/content 模块各一个）拿到消息后在 Redis 恢复时重新删除。

---

## 5. 防穿透、防雪崩、防击穿——三重保护

### 5.1 防缓存穿透

**问题**：攻击者不断查询一个不存在的 ID（如 noteId=999999），每次都 miss → 每次都查 DB → DB 被打爆。

**解决**：`DB 返回 null 时缓存空值，TTL 2 分钟`。

```java
// CacheHelper.java:128
redisOperator.set(key, NULL_PLACEHOLDER, 2, TimeUnit.MINUTES);
```

下一次查同一个不存在的 ID 时，Redis 返回 `NULL_PLACEHOLDER` → `getWithCacheAside` 直接返回 null，不查 DB。2 分钟后过期 → 如果此时记录确实创建了，查 DB 能查到并覆盖缓存。

**`NULL_PLACEHOLDER` 设计**：

```java
private static final String NULL_PLACEHOLDER = "\u0000__CACHE_NULL__\u0000";
```

使用 Unicode NUL 字符前后包裹的不可能出现在业务数据中的字符串，避免和真实值为空字符串 `""` 或 `"null"` 的业务数据冲突。

### 5.2 防缓存雪崩

**问题**：100 个热点 Key 同时过期 → 100 个请求同时 miss → 同时查 DB → DB 瞬时压力飙升。

**解决**：`TTL + 随机偏移（TTL/6 内）`。

```java
long randomOffset = ThreadLocalRandom.current().nextLong(0, timeoutSeconds / 6 + 1);
redisOperator.set(key, dbResult, timeoutSeconds + randomOffset, TimeUnit.SECONDS);
```

原本统一 30 分钟过期，现在变成 30~35 分钟随机分布。避免所有缓存同时过期。

### 5.3 防缓存击穿

**问题**：单个热点 Key 过期瞬间，100 个并发请求同时 miss → 100 个请求同时查 DB。

**解决**：`getWithCacheAsideLock()` 的 Singleflight 模式（见 2.3 节）。

---

## 6. TTL 层设计——为什么不同 Key 用不同 TTL？

| Redis Key | TTL | 原因 |
|-----------|:---:|------|
| `myxhs:note:detail:{noteId}` | 30min | 笔记详情变更频率低，30 分钟不过期可大幅降低 DB 查询 |
| `myxhs:comment:count:{noteId}` | 5min | 评论数变化频繁，短 TTL 减少不一致窗口，但不用实时 |
| `myxhs:user:info:{userId}` | 30min | 用户信息几乎不变，但改昵称/头像后需要能及时反映 |
| NULL_PLACEHOLDER | 2min | 防穿透的空值缓存，短 TTL 确保新数据能尽快覆盖空值 |

---

## 7. publishNote 的缓存行为

在 `NoteService.publishNote()` 的 `afterCommit` 中：

```java
afterCommit() {
    cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + userId);
    // 仅删除了用户笔记列表缓存！
    // 笔记详情缓存没有被删除。
}
```

**为什么不删笔记详情缓存？** 因为发布的是**新笔记**，不存在于任何已缓存的笔记详情中。`myxhs:note:detail:{newNoteId}` 从未被缓存，不需要删除。`myxhs:note:list:user:{userId}` 里有旧列表（不含新笔记），需要删除。

**什么时候笔记详情被更新？** 编辑已发布的笔记（`updateNote()`）或删除笔记（`deleteNote()`）。这两种情况下，既要删列表缓存，也要删详情缓存。

---

## 8. 缓存和 DB 的最终一致性窗口

即使有三级保障，仍然存在一个微小的不一致窗口：

```
T0: updateNote → DB 更新
T1: delayDoubleDelete 第一次删 → 缓存清除
T2: delayDoubleDelete 第二次删（500ms 后）→ 缓存最终清除
T3: 如果 L1+L2 都失败 → L3: MQ CACHE_EVICT_TOPIC
    → CacheEvictConsumer 消费 → 最终删除（秒级）
```

**最差情况**（Redis 持续不可用 + MQ Broker 宕机）：不一致窗口 = 直到运维介入。概率极低——需要 Redis 和 MQ 同时故障。

---

## 9. 已知局限

| 局限 | 说明 |
|------|------|
| `delayScheduler` 是 `singleThread` | 大量延迟删任务堆积时需排队，但 500ms 延迟足够分散，无监控 pending 数量 |
| 一致性窗口不可消除 | 最差情况（Redis + MQ 同时故障），不一致窗口 = 直到运维介入 |
| 无缓存命中率监控 | 当前无 Prometheus 指标区分 hit/miss 比例 |

## 总结

| 特性 | 实现 | 解决问题 |
|------|------|---------|
| Cache Aside | `getWithCacheAside` | 标准读缓存模式 |
| Singleflight | `getWithCacheAsideLock` | 热点 Key 击穿 |
| 延迟双删 | `delayDoubleDelete` + ScheduledThread | 并发读回填旧值 |
| 三级保障 | L1 立即删 → L2 延迟删 → L3 MQ 兜底 | 缓存删除失败的重试 |
| 防穿透 | NULL_PLACEHOLDER + 2min TTL | 空 ID 反复查 DB |
| 防雪崩 | TTL + random(0~TTL/6) | Key 同时过期 |
| 防击穿 | Singleflight 分布式锁 | 热点 Key miss |
