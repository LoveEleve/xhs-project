# SPU 缓存架构：BloomFilter + 逻辑过期 + afterCommit 一致性

> **源码**: SpuService(620行) + RedisCacheData(56行) + RedisOperator(490行)  
> **调用链**: `GET /api/product/spu/{id}` → BloomFilter → Redis(RedisCacheData) → MySQL → 回填  
> **关键修复**: B1-B4 + DC1 缓存Bug / D1-D5 虚假声明修正 / CC1 Feign→product

---

## 1. 整体架构

```
请求 GET /api/product/spu/{id}
        │
        ▽
        BloomFilter (Redisson RBloomFilter, Redis 持久化)
        ├─ contains=false → return null（拦截不存在的 ID）
        └─ contains=true / 未就绪 → 继续
                │
                ▽
        Redis L2 (RedisCacheData 逻辑过期)
        ├─ 命中且未过期 → 返回正常数据 / 空值缓存→return null
        ├─ 命中但已过期(正常值) → 返回旧值 + asyncRefreshCache()
        ├─ 命中但已过期(空值) → 穿透到 DB（数据可能已新增）
        ├─ Redis 异常 → 降级直查 MySQL
        └─ 未命中 → MySQL L3
                │
                ▽
        MySQL L3 (3 次查询: SPU + SKU + Category)
        ├─ 查到了 → 回填 RedisCacheData（逻辑30min+物理120min）×
        ├─ 查不到 → 回填空值缓存（逻辑2min+物理5min，防穿透）
        └─ 返回
```

**为什么只有两层缓存？** 注释声称 Caffeine 三层缓存但实际从未实现。核心原因：Redis GET 延迟 < 1ms，在商品详情场景完全够用。Caffeine 在多实例下的一致性成本（广播失效）高于收益，且 product 模块本来就零 MQ 代码。

---

## 2. BloomFilter 防穿透

### 2.1 初始化 (`SpuService.java:135-158`)

```java
@PostConstruct
public void initBloomFilter() {
    spuBloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");
    boolean isNewFilter = spuBloomFilter.tryInit(1_000_000L, 0.01);
    if (isNewFilter) {
        asyncLoadBloomFilter();  // 异步全量加载，不阻塞启动
    } else {
        if (spuBloomFilter.count() == 0) {
            asyncLoadBloomFilter();  // 空布隆重新加载
        } else {
            bloomFilterReady.set(true);  // 直接复用
        }
    }
}
```

**三个关键设计**：

1. **Redis 持久化**：`Redisson RBloomFilter` 存在 Redis 中，服务重启不丢失。`tryInit(1_000_000, 0.01)` 是幂等的——已存在直接复用，不会重置。
2. **异步加载不阻塞启动**：`asyncLoadBloomFilter()` 使用游标分页（每批 5000，`id > lastId LIMIT 5000`）+ 批间 `sleep(100ms)`，全程异步。`bloomFilterReady=false` 期间降级跳过布隆检查。
3. **为什么不用本地布隆？** 多实例部署时，本地布隆需要各自全量加载 — N个实例 × 100万SPU = N×100万次 Redis add — 浪费。Redis 共享布隆只需初始化一次。

### 2.2 运行期维护 (`SpuService.java:266-272`)

```java
// createSpu 事务提交后
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        spuBloomFilter.add(newSpuId);
    }
});
```

**为什么放在 afterCommit？** 修复前 bloomFilter.add 在 `@Transactional` 内部——事务回滚后 DB 无数据但布隆有 → 假阳性（误把不存在的 ID 放过）。afterCommit 保证只有成功提交的 SPU 才进布隆。

### 2.3 前置拦截 (`SpuService.java:391-394`)

```java
if (bloomFilterReady.get() && !spuBloomFilter.contains(spuId)) {
    return null;
}
```

布隆未就绪时跳过检查——服务仍可用，只是没有防穿透。

---

## 3. RedisCacheData 逻辑过期模式

### 3.1 数据结构 (`RedisCacheData.java:22-45`)

```java
public class RedisCacheData<T> implements Serializable {
    private T data;                    // 缓存的数据（正常值 或 null=空值缓存）
    private LocalDateTime logicExpire; // 逻辑过期时间

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(logicExpire);
    }

    public static <T> RedisCacheData<T> of(T data, long minutes) {
        return new RedisCacheData<>(data, LocalDateTime.now().plusMinutes(minutes));
    }
}
```

**关键设计**：空值也统一用 `RedisCacheData` 包装（`data=null, logicExpire=2min`），避免同一 Key 存两种类型（null vs RedisCacheData）的序列化陷阱。

### 3.2 序列化 (`RedisConfig.java`)

Jackson `GenericJackson2JsonRedisSerializer` 启用 `DefaultTyping.NON_FINAL`，写入 `@class` 类型信息。`PolymorphicTypeValidator` 白名单放行 `com.myxhs.*` / `java.util.*` / `java.time.*`。

**进化风险**：若 `SpuDetailVO` 字段重命名，旧缓存中 `@class` 仍指向旧字段名 → 反序列化时字段为 null。需加 `@JsonAlias` 兼容旧字段，或全量清缓存。

### 3.3 过期处理四路径 (`SpuService.java:397-428`)

| 缓存状态 | 行为 | 原因 |
|------|------|------|
| 未过期 + 正常数据 | 直接返回 | 最快的路径 |
| 未过期 + 空值缓存 | 直接返回 null | 防穿透，不查 DB |
| 已过期 + 正常数据 | **返回旧值** + 异步刷新 | 秒级延迟换不击穿 |
| 已过期 + 空值缓存 | 穿透到 DB 重查 | 数据可能已新增 |

**"返回旧值 + 异步刷新"是核心**：类似 Redisson 的读写锁降级——优先保证可用性（旧值仍有效），一致性通过异步刷新最终达成。

### 3.4 双重 TTL (`SpuService.java:114,354`)

```java
private static final long LOGIC_EXPIRE_MINUTES = 30;   // 逻辑过期：决定刷新时机
private static final long PHYSICAL_TTL_MINUTES = 120;  // 物理 TTL：Key 最大存活时间
```

| TTL | 值 | 作用 |
|------|:--:|------|
| 逻辑过期 | 30min | < 30min：直接返回；> 30min：异步刷新 + 返回旧值 |
| 物理 TTL | 120min | Redis Key 的 `EXPIRE`——灾备兜底。正常会被刷新重置 |

**为什么需要双重 TTL？** 修复前只有逻辑过期无物理 TTL → Redis Key 永不过期 → 已删除 SPU 的过期缓存永远占用内存。120min 物理 TTL 保证即使刷新全部失败，Key 也会在 2h 后被清理。

---

## 4. 写操作一致性（afterCommit 重构）

### 4.1 修复前的问题

```java
// 修复前 — 缓存删除在事务提交前（SpuService.java 旧代码）
@Transactional
public void updateSpu(Long spuId, SpuUpdateRequest request) {
    spuMapper.updateById(spu);
    evictSpuCache(spuId);  // ← 事务提交前删了缓存！
}
```

**并发脏读窗口**：

```
T1: updateSpu 删 Redis 缓存                           T2: getSpuDetail(spuId)
T1: 事务未提交                                              BloomFilter.contains → true
T1: evictSpuCache 执行                                Redis 未命中 → 查 MySQL 从库(13311)
                                                      从库还没收到主库同步 → 读到旧值
                                                      回填 Redis = 旧值（脏数据）
T1: 事务提交
```

### 4.2 修复后：TransactionSynchronization.afterCommit

```java
@Transactional
public void updateSpu(Long spuId, SpuUpdateRequest request) {
    spuMapper.updateById(spu);
    // 注册 afterCommit 回调，提交后才删缓存
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            evictSpuCache(spuId);
        }
    });
}
```

**修复效果**：缓存删除发生在 DB 提交之后 → 并发读要么命中（刚从 DB 读的新值），要么未命中 → 从 DB 读到的也是提交后的新值。回填的永远是新值。

### 4.3 适用范围

`createSpu`（布隆 add）、`updateSpu`（缓存删）、`updateSpuStatus`（缓存删）、`createSku`（缓存删）全部改为 afterCommit。未改 afterCommit 之前，以上四个场景都有脏读窗口。

---

## 5. 异步刷新机制

### 5.1 分布式锁保护 (`SpuService.java:466-497`)

```java
private void asyncRefreshCache(Long spuId, String redisKey) {
    CompletableFuture.runAsync(() -> {
        RLock lock = redissonClient.getLock("myxhs:product:lock:spu:" + spuId);
        boolean locked = lock.tryLock(0, 10, TimeUnit.SECONDS);  // 非阻塞
        if (!locked) return;  // 其他线程已在刷新

        SpuDetailVO detail = loadSpuDetailFromDb(spuId);
        if (detail != null) {
            // 正常数据回填（逻辑过期 + 物理 TTL）
            RedisCacheData.of(detail, LOGIC_EXPIRE_MINUTES);
            redisOperator.set(redisKey, newCacheData, PHYSICAL_TTL_MINUTES, TimeUnit.MINUTES);
        } else {
            // 已删除商品 → 写空值缓存防止脏读（修复 B1）
            RedisCacheData<SpuDetailVO> nullCacheData = RedisCacheData.of(null, NULL_CACHE_EXPIRE_MINUTES);
            redisOperator.set(redisKey, nullCacheData, 5, TimeUnit.MINUTES);
        }
    }, SPU_ASYNC_EXECUTOR);
}
```

### 5.2 线程池设计 (`SpuService.java:71-78`)

```java
new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(100),
    r -> { Thread t = new Thread(r, "spu-async"); t.setDaemon(true); return t; },
    new ThreadPoolExecutor.CallerRunsPolicy());
```

| 参数 | 值 | 原因 |
|------|:--:|------|
| core/max | 2/8 | 刷新任务主要是 DB + Redis，IO 密集型 |
| 队列 | 100 | 缓冲突发流量 |
| RejectPolicy | CallerRunsPolicy | 队列满后由调用线程执行——不丢任务、但会降级性能 |

**风险**：CallerRunsPolicy 在高 QPS 下，web 线程被阻塞去做缓存刷新 → 请求延迟上升。当前 100 队列足够吸收 100 个并发刷新任务，超过才触发 CallerRuns。权衡：可用性（不丢任务）> 延迟。

### 5.3 锁超时风险

`tryLock(0, 10s)` 10 秒租约——若 DB 查询 + Redis set 超过 10 秒（极端慢查询），锁自动释放 → 另一个线程可能重复刷新。但 10 秒远大于正常操作耗时（~50ms），实际几乎不发生。

---

## 6. 降级策略

### 6.1 Redis 不可用 (`SpuService.java:395-400`)

```java
RedisCacheData<SpuDetailVO> cacheData = null;
try {
    cacheData = redisOperator.get(redisKey);
} catch (Exception e) {
    log.warn("Redis 不可用，降级直查 DB, spuId={}", spuId, e);
    // cacheData 保持 null → 直接落到 MySQL L3
}
```

修复前（B3）：Redis 异常 → `RedisUnavailableException(RuntimeException)` → 500。修复后：降级直查 DB，不返回 500。

### 6.2 布隆未就绪

`bloomFilterReady=false` 期间跳过布隆检查 → 直接 Redis → MySQL。服务仍可用。

### 6.3 分类缓存降级 (`CategoryService.java:48-51`)

同样模式：Redis 不可用 → 直接 buildCategoryTree() 查 DB。

---

## 7. 工程维度审查

### 7.1 防护策略

| 问题 | 方案 | 状态 |
|------|------|:--:|
| 缓存穿透 | BloomFilter(1%误判) + 空值缓存(2min) | ✅ |
| 缓存击穿 | 逻辑过期 + Redisson 分布式锁异步刷新 | ✅ |
| 缓存雪崩 | 逻辑过期本身避免集中失效 | ✅ |
| 缓存一致性 | afterCommit 删缓存 | ✅ |
| Redis 不可用 | 降级直查 DB | ✅ |
| 布隆不可用 | 跳过布隆，服务仍可用 | ✅ |

### 7.2 未覆盖的场景（设计取舍）

| 场景 | 现状 | 影响 |
|------|------|------|
| 分类改名 | SPU 缓存中 categoryName 不刷新 | 最长 30min 陈旧 |
| afterCommit 删缓存失败 | 无 MQ 兜底（与 content/user 不同） | Redis Key 保留旧值，最长 2h 物理 TTL 后清 |
| `@class` 反序列化失败 | Jackson 抛异常 | getSpuDetail 返回异常 |
| 线程池 CallerRunsPolicy 触发 | web 线程阻塞执行刷新 | 极值场景请求延迟 |

---

## 8. 面试 Q&A

### Q1: 为什么商品详情走逻辑过期而不是简单的 TTL？

**陷阱答案**：因为逻辑过期更高级。

**正确答案**：简单的 TTL + 删除策略下，热点商品 Key 过期瞬间 → 所有请求同时穿透到 DB → DB 被打挂（缓存击穿）。逻辑过期让 Key "永不物理过期"——到了 30min 时，第一个请求触发异步刷新，其他请求继续返回旧值。只有这一个请求查 DB。

### Q2: 布隆过滤器有 1% 误判率，误判的请求会怎么样？

被误判放过的不存在 ID → Redis 未命中 → DB 也查不到 → 空值缓存 2min。同一个误判 ID 只穿透一次 DB，后续 2min 内都返回 null。这比单独布隆（误判每次都穿 DB）和单独空值缓存（每个攻击 ID 都占一个 Key）都好——是 1+1>2 的组合。

### Q3: 为什么删除缓存放在 afterCommit 而不是直接在 @Transactional 方法末尾？

因为事务提交和缓存删除之间有间隙。`spuMapper.updateById` 执行后、事务提交前，主库已改但从库未同步。此时另一个请求读从库 → 读到旧值 → 回填 Redis = 旧值。afterCommit 保证删缓存时事务已提交，下一个请求读到的是提交后的值（主库或同步完成的从库）。

### Q4: `RedisCacheData` 为什么连空值也要包一层，而不是直接存 null？

Jackson 序列化时：存 null → 反序列化回来是 JSON null（不能用 `instanceof RedisCacheData` 判断）。存 `RedisCacheData(data=null)` → 反序列化回来是 `RedisCacheData` 实例，`isExpired()` 正常工作。统一包装避免了代码里到处判断 "cacheValue instanceof RedisCacheData || cacheValue == null" 的散弹枪模式。
