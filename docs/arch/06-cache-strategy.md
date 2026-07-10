# 06 — 缓存体系

> **目标读者**：P7+ 工程师，需要理解多级缓存架构、一致性保障、防穿透/雪崩/击穿策略。
> **回答三个问题**：为什么这样分层？一致性怎么保障？代价是什么？

---

## 一、多级缓存架构

### 1.1 为什么需要多级？

```
单级 Redis 缓存的问题：
  - Redis 网络 RTT ~1ms
  - 热点 Key（如爆款商品详情）QPS 10 万 → 10 万次 Redis 调用
  - Redis 单实例 QPS 上限 ~10 万 → 所有请求都压在同一实例

多级缓存：
  - Caffeine 本地缓存命中率 80% → 10 万 QPS 中 8 万次命中本地（微秒级）
  - Redis 承担剩余 2 万 QPS → 远低于上限
```

### 1.2 三级缓存体系

```
请求 → 布隆过滤器 → Caffeine L1 → Redis L2 → MySQL L3
        99% 拦截      80% 命中      19% 命中      1% 兜底
        微秒级         微秒级        毫秒级        毫秒-秒级
```

| 层级 | 技术 | 容量 | TTL | 延迟 |
|------|------|------|-----|------|
| L0 | Redisson 布隆过滤器 | 100 万元素 | 永久 | < 1ms |
| L1 | Caffeine 本地缓存 | 5000 条 | 5min | < 1μs |
| L2 | Redis 分布式缓存 | 按内存 | 30min 逻辑过期 | ~1ms |
| L3 | MySQL | 无限 | — | ~10ms |

### 1.3 商品服务的具体实现

**Caffeine 配置**（`CaffeineCacheConfig.java`）：

```java
// SPU 详情缓存
Caffeine.newBuilder()
    .maximumSize(5000)
    .expireAfterWrite(5, TimeUnit.MINUTES)
    .recordStats()
    .build();

// 分类树缓存（1 条数据，全量缓存）
Caffeine.newBuilder()
    .maximumSize(1)
    .expireAfterWrite(1, TimeUnit.HOURS)
    .build();
```

**为什么 SPU 用 5000 条容量？**
- 活跃商品数量预估 < 3000
- 5000 提供 66% 的容量冗余
- Caffeine 基于 W-TinyLFU 算法，自动淘汰低频访问的冷数据

---

## 二、防穿透：三层拦截

### 2.1 第一层：布隆过滤器

```java
// 初始化（SpuService.java:146-162）
RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");
bloomFilter.tryInit(1_000_000L, 0.01);  // 100万元素，1% 误判率
```

| 参数 | 值 | 含义 |
|------|-----|------|
| expectedInsertions | 1,000,000 | 预计 100 万个 SPU |
| falseProbability | 0.01 | 1% 误判率 |
| 实际内存占用 | ~1.2MB | 可忽略 |

**异步加载机制**：
```
应用启动 → @PostConstruct → 异步加载布隆过滤器
  │
  ├── 获取分布式锁（防多实例重复加载）
  ├── 分批查询 DB（每批 5000 条，游标分页）
  ├── 逐批 bloomFilter.add(spuId)
  └── 加载失败不影响服务可用性
```

**1% 误判率的影响**：100 万个不存在 ID 中有 1 万个会穿透到 L2/L3。对于商品服务，这个量级可接受。

### 2.2 第二层：缓存空值

```java
// SpuService.java:432-434
// DB 查不到 → 缓存空值
RedisCacheData<SpuDetailVO> nullData = RedisCacheData.of(null, 2);  // 逻辑过期 2min
redisOperator.set(key, nullData, 5, TimeUnit.MINUTES);  // 物理过期 5min
```

| 设计点 | 值 | 理由 |
|--------|-----|------|
| 逻辑过期 | 2min | 快速恢复，避免新增商品后 2min 内不可见 |
| 物理过期 | 5min | Redis 兜底清理，防止僵尸 Key |
| 空值体积 | < 50 bytes | 可忽略 |

### 2.3 第三层：ID 格式校验

Controller/网关层拦截非法格式的 ID（负数、非数字、超长），避免无效请求穿透到 DB。

---

## 三、防击穿：两种策略

### 3.1 策略 A：分布式锁（CacheHelper.getWithCacheAsideLock）

```
线程 A: 查缓存 Miss → 获取锁 → 双重检查 → 查 DB 回填 → 释放锁
线程 B: 查缓存 Miss → 获取锁失败 → sleep 100ms → 重试查缓存 → 命中 → 返回
线程 C: 查缓存 Miss → 获取锁失败 → 重试仍 Miss → 降级直接查 DB
```

```java
// CacheHelper.java:158-236
RLock lock = redissonClient.getLock("lock:cache:" + key);
if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
    try {
        // 双重检查
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) return cached;
        // 查 DB 回填
        ...
    } finally {
        lock.unlock();
    }
} else {
    // 获取锁失败，sleep 100ms 重试
    Thread.sleep(100);
    // 仍 Miss 则降级直接查 DB
}
```

**代价**：
- 获取锁失败只重试 1 次 → 高并发场景可能仍然 Miss
- 100ms 睡眠在高并发下会造成线程堆积（→ 见 42-engineering-issues）

### 3.2 策略 B：逻辑过期 + 异步刷新（SpuService.asyncRefreshCache）

```
请求: 查 Redis → 数据存在但已逻辑过期 → 返回旧值
                                            │
                            异步线程: 获取锁 → 查 DB → 回填缓存 → 释放锁
```

```java
// SpuService.java:503-535
RLock lock = redissonClient.getLock("myxhs:product:lock:spu:" + spuId);
if (lock.tryLock(0, 10, TimeUnit.SECONDS)) {  // 非阻塞
    try {
        // 查 DB 回填
        SpuDetailVO vo = queryFromDB(spuId);
        redisOperator.set(key, RedisCacheData.of(vo, 30), ...);
        spuLocalCache.put(spuId, vo);
    } finally {
        lock.unlock();
    }
}
// 获取不到锁 → 直接返回，其他线程正在刷新
```

**与策略 A 的区别**：

| 维度 | 策略 A（分布式锁） | 策略 B（逻辑过期） |
|------|-------------------|-------------------|
| 第一个请求 | 阻塞等待 DB 回填 | 返回旧值，异步刷新 |
| 后续请求 | 从缓存读取 | 从缓存读取 |
| 用户体验 | 首次 Miss 有延迟 | 永远不等待 |
| 适用场景 | 缓存完全不存在 | 缓存存在但过期 |

---

## 四、防雪崩：TTL 随机偏移

```java
// CacheHelper.java:125-126
long randomOffset = ThreadLocalRandom.current().nextLong(0, timeoutSeconds / 6 + 1);
long actualTimeout = timeoutSeconds + randomOffset;
```

| 原始 TTL | 偏移范围 | 实际 TTL 范围 |
|---------|---------|-------------|
| 30min (1800s) | 0 ~ 300s | 1800s ~ 2100s |
| 5min (300s) | 0 ~ 50s | 300s ~ 350s |

**TTL/6 的偏移是否足够？**
分散度为 16.7%，即所有 Key 在 16.7% 的时间窗口内分散过期。建议扩大到 20-30%（→ 见 42-engineering-issues）。

---

## 五、缓存一致性：三重保障

### 5.1 L1：先更新 DB，再删缓存（deleteAfterUpdate）

```
UPDATE t_spu SET ... WHERE id = ?
  ↓
redis.del("product:spu:123")  ← 重试 3 次，每次间隔 50ms
  ↓ 失败
记录 ERROR 日志 → 等待 MQ 兜底
```

**为什么是"先更新 DB 再删缓存"而非"先删缓存再更新 DB"？**

| 方案 | 风险 | 概率 |
|------|------|------|
| 先删缓存再更新 DB | 删缓存后、更新 DB 前，另一个请求读到旧数据回填缓存 → 缓存脏数据 | 高 |
| 先更新 DB 再删缓存 | 更新 DB 后、删缓存前，另一个请求读到旧数据 → 短暂不一致 | 低 |

**Cache Aside 模式选择后者**，不一致窗口 = 删缓存耗时（< 1ms）。

### 5.2 L2：延迟双删（delayDoubleDelete）

```
UPDATE t_spu → redis.del(key)  ← 第一次删
                 │
                 ├── 等待 500ms ──→ redis.del(key)  ← 第二次删
                 │                      │
                 │                      └── 失败 → 发送 CACHE_EVICT_TOPIC
                 │
                 └── 为什么 500ms？
                      主从同步延迟 ~200ms + 业务读耗时 ~100ms + 安全余量 ~200ms
```

**500ms 硬编码的问题**（→ 见 40-mysql-engineering-issues）：
- 主从延迟超过 500ms 时第二次删除无效
- 已改为 `@Value` 可配置，但默认值仍是 500ms

### 5.3 L3：MQ 兜底

```
deleteAfterUpdate 3 次重试失败
  │
  或 delayDoubleDelete 第二次删除失败
  │
  └── 发送 CacheEvictMessage 到 CACHE_EVICT_TOPIC
        │
        ├── user-service tag → CacheEvictConsumer
        │     └── redisOperator.delete(key)
        │
        └── product-service → CacheEvictListener（BROADCASTING 广播）
              └── spuService.evictLocalCache(spuId)  ← 清除所有实例的 Caffeine
```

### 5.4 特殊方案：库存的 Canal Binlog 同步

库存模块不使用延迟双删，改用 Canal 监听 MySQL Binlog：

```
UPDATE t_inventory SET available_stock = 95 WHERE sku_id = 100
  ↓ MySQL Binlog
Canal 解析 → INVENTORY_CACHE_TOPIC → InventoryCacheEvictConsumer
  ↓
版本号防乱序（Lua 原子比较）
  ↓
删除 Redis 缓存：inventory:{skuId}:total + inventory:{skuId}:bucket:*
```

**为什么库存不用延迟双删？**
- 延迟双删有 500ms 不一致窗口
- 库存不一致 → 可能超卖
- Canal 方案的延迟取决于 Binlog 同步速度，通常 < 100ms

**版本号防乱序**（`InventoryCacheEvictConsumer.java:186-210`）：
```lua
-- 原子比较版本号
local current = redis.call('GET', KEYS[1])
if not current or tonumber(ARGV[1]) > tonumber(current) then
    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
    return 1
else
    return 0  -- 旧版本，忽略
end
```

---

## 六、缓存预热

```java
// CacheWarmupRunner.java（ApplicationRunner，启动完成后执行）

预热项 1: 热搜 Top 50      → search:hot:global         (TTL 1h)
预热项 2: 热门笔记 Top 100   → recommend:hot:global       (TTL 1h)
预热项 3: 分类树            → product:category:tree       (TTL 1h)
```

**设计要点**：
- 每个预热项独立 try-catch，失败不影响其他项
- 预热前检查 Key 是否已存在，存在则跳过（幂等）
- 异步分批加载，不阻塞启动

---

## 七、代价与局限

| 局限 | 影响 | 改进方向 |
|------|------|---------|
| **布隆过滤器 1% 误判率** | 100 万不存在 ID 中 1 万穿透 | 定期重建 + 提高精度（代价是更多内存） |
| **布隆过滤器不支持删除** | 下架 SPU 无法移除，误判率持续上升 | 定期全量重建（→ 见 42） |
| **TTL 随机偏移仅 16.7%** | 雪崩防护不够充分 | 扩大到 20-30% |
| **getWithCacheAsideLock 只重试 1 次** | 高并发下可能仍然击穿 | 指数退避 3-5 次 |
| **Caffeine 多实例不一致** | 一个实例更新后，其他实例 Caffeine 仍是旧值 | MQ 广播清除（已实现），TTL 5min 兜底 |
| **延迟双删 500ms 硬编码** | 主从延迟超 500ms 时无效 | 已改为可配置，需按环境调整 |

---

> **下一篇**：`07-chaos-engineering.md` — 混沌工程：自研故障注入框架、chaos-drill.sh 7 个演练场景、与 ChaosBlade 的对比
