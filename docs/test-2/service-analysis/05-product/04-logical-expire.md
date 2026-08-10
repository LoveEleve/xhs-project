# RedisCacheData 逻辑过期设计

> 源码：`RedisCacheData.java`（56 行）+ `SpuService.getSpuDetail() §4`
> 验证：`02-product-test.md` §1.1 / §1.2

---

## 1. 物理过期的缓存击穿问题

```
SETEX spu:12345 "SpuDetailVO" 3600  ← 1 小时物理过期

T=3600s: Redis 到期自动删除 Key
T=3600s: 1000 并发 GET 同时到达
  → Redis GET → null（全部 miss）→ 1000 次查 DB
  → MySQL 连接池满、CPU 飙升 → 缓存击穿！
```

物理过期的问题：Redis Key 自动删除的瞬间，缓存和 DB 之间出现真空——所有并发请求穿透到 DB。

**为什么商品详情特别容易击穿？** 热销商品（爆款）QPS 可达数千。物理过期的精准瞬间（一个 TTL 值），数千请求同时 miss → DB 瞬时峰值。

---

## 2. RedisCacheData：Key 永不物理过期

```java
public class RedisCacheData<T> implements Serializable {
    private T data;                       // 实际缓存数据（null = 空值缓存）
    private LocalDateTime logicExpire;    // 逻辑过期时间
}
```

**核心思路**：Key 永远不设物理 TTL，过期判断从 Redis 层上移到应用层。

**查询流程（SpuService.getSpuDetail）**：

```java
// SpuService.java:379-421
RedisCacheData<SpuDetailVO> cacheData = redisOperator.get(redisKey);

if (cacheData != null) {
    if (!cacheData.isExpired()) {
        // ① 未过期 → 直接返回（热数据路径 ~1.5ms）
        return cacheData.getData();
    }
    // ② 已过期
    if (cacheData.getData() == null) {
        // 空值缓存过期 → 穿透 DB 重新确认
    } else {
        // 正常数据过期 → 返回旧值 + 异步刷新（单线程）
        asyncRefreshCache(spuId, redisKey);
        return cacheData.getData();  // 不等待刷新
    }
}

// ③ Key 不存在 → 查 DB → 回填
```

**与物理过期的关键区别**：

| 场景 | 物理过期 | 逻辑过期 |
|------|:---:|:---:|
| 过期瞬间 | Key 被删除 | Key 仍在，应用层判断 |
| 并发请求 | 全部穿透 DB | 返回旧值 + 1 个线程异步刷新 |
| DB 负载 | 瞬时峰值 | 平稳（单线程刷新） |
| 数据新鲜度 | 实时 | 允许分钟级延迟 |

---

## 3. 异步刷新：`CompletableFuture` + 分布式锁

```java
// SpuService.java:466-497
private void asyncRefreshCache(Long spuId, String redisKey) {
    CompletableFuture.runAsync(() -> {
        RLock lock = redissonClient.getLock("myxhs:product:lock:spu:" + spuId);
        boolean locked = lock.tryLock(0, 10, TimeUnit.SECONDS);
        if (!locked) return;  // 其他线程在刷新

        SpuDetailVO detail = loadSpuDetailFromDb(spuId);      // 查 DB
        RedisCacheData<SpuDetailVO> newData = RedisCacheData.of(detail, LOGIC_EXPIRE_MINUTES);
        redisOperator.set(redisKey, newData);                 // 回写
        lock.unlock();
    }, SPU_ASYNC_EXECUTOR);
}
```

**时序分析（1000 并发看到过期）**：

```
T0: cacheData.isExpired() == true
  线程 A: tryLock 成功 → 查 DB → SET 新值 → unlock
  线程 B: tryLock 失败 → 返回旧值（不阻塞）
  线程 C: tryLock 失败 → 返回旧值
  ...
  线程 J: tryLock 失败 → 返回旧值

结果: 1 次 DB 查询，999 次返回旧值
```

**`tryLock(0, ...)` 的语义**：`waitTime=0`——不排队等待锁，拿不到直接走。其他 999 个请求不需要等刷新完成，直接返回旧值即可。

**为什么不用 `lock()` 阻塞等待？** 如果用阻塞锁，999 个请求排队等刷新 → 每个请求增加几百毫秒延迟 → P99 飙升 → 可用性下降。

**自定义线程池 `SPU_ASYNC_EXECUTOR`**：core=2, max=8, queue=100, daemon。独立线程池避免异步刷新阻塞公共的 `ForkJoinPool.commonPool()`。

---

## 4. 空值缓存的特殊处理

```java
// SpuService.java — getSpuDetail 中的空值处理:

// 正常数据: 逻辑过期 30min，无物理 TTL
RedisCacheData<SpuDetailVO> cache = RedisCacheData.of(detail, LOGIC_EXPIRE_MINUTES);
redisOperator.set(redisKey, cache);  // Key 永不自动过期

// 空值: 逻辑过期 2min，物理 TTL 5min
RedisCacheData<SpuDetailVO> nullCache = RedisCacheData.of(null, NULL_CACHE_EXPIRE_MINUTES);
redisOperator.set(redisKey, nullCache, 5, TimeUnit.MINUTES);
```

**为什么空值缓存必须有物理 TTL？**

```
攻击场景: 用随机雪花 ID 遍历（2^41 个可能 ID）
  → 每个 ID 缓存空值 → 永不过期 → 无限增长 → Redis OOM
```

物理 TTL 5min 确保空值 Key 定期自动清理。Bloom 拦截 99% + 空值 TTL 五分钟后清除 = 组合防护。

**空值过期后为什么穿透 DB 而不是异步刷新？** 空值代表"上次 DB 中没有这个 SPU"。现在可能有了（新创建的）——应该重新查 DB 确认，而不是继续缓存空值。

---

## 5. 发散：逻辑过期 vs Caffeine refreshAfterWrite

Caffeine 本地缓存也支持类似"返回旧值+异步刷新"的语义：

| 维度 | RedisCacheData（当前） | Caffeine refreshAfterWrite |
|------|------|------|
| 存储位置 | Redis 分布式 | JVM 本地内存 |
| 多实例共享 | ✅ 一个 Key 一份数据 | ❌ 每个实例独立维护 |
| 刷新线程 | RLock 保护（分布式单线程） | Caffeine 内置 executor |
| 延迟 | ~1ms（Redis RTT） | < 0.01ms（本地内存） |
| 容量 | Redis 内存（可横向扩展） | 单实例堆内存（3 实例 = 3 倍） |
| 一致性 | 所有实例同一 Redis | 各节点独立过期 → 可能不一致 |

**为什么不加 Caffeine 本地缓存？** Caffeine 的 `refreshAfterWrite` 语义和逻辑过期完全相同——也是"返回旧值 + 异步刷新"。两者行为等价，区别仅在于存储位置。多实例部署时，Caffeine 需要 MQ 广播来同步失效——任一广播丢失导致节点数据永久不一致。Redis 单层已满足性能需求（~1ms 延迟），不值得引入多层缓存的一致性问题。

---

## 6. 发散：四种缓存过期策略对比

| 策略 | 过期判断 | 刷新方式 | 适用场景 |
|------|:---:|------|------|
| **物理过期**（SETEX） | Redis 自动删除 | 被动（下一次 miss 穿透） | 低频访问、允许击穿 |
| **逻辑过期**（当前方案） | 应用层 `logicExpire` | 主动异步刷新 | 高频读、不允许击穿 |
| **主动过期**（定时任务） | 应用层定时扫描 | 批量刷新 | 需要批量预热 |
| **永不过期** | 无过期 | 写操作更新缓存 | 数据极少变更、缓存=数据源 |

商品模块选逻辑过期：高频读（商品详情 QPS 高） + 不允许击穿（爆款商品缓存失效会打挂 DB） + 允许分钟级延迟（价格变更后用户等几十秒可以接受）。

---

## 7. 已知限制

| 限制 | 说明 | 影响 |
|------|------|------|
| `LocalDateTime.now()` 时钟依赖 | 服务器时钟回拨→逻辑过期判断错误 | 极低概率，NTP 自动同步 |
| 空值缓存 TTL 固定 5min | 新建 SPU 在 5min 内查询仍返回 null（如果之前误判过不存在） | 低概率（Bloom 拦截 99%，新建 SPU 通常创建后立即有缓存） |
| 异步刷新依赖线程池 | `SPU_ASYNC_EXECUTOR` 满了会拒绝刷新（CallerRunsPolicy） | 阻塞 HTTP 线程，P99 升高 |

---

## 关联文档

- `01-product-module.md` — §4 RedisCacheData
- `02-product-test.md` — §1.1 缓存回填 / §1.2 缓存清除
- `03-bloom-filter.md` — L1 布隆过滤器（逻辑过期的前一层）
