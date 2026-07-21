# 商品服务（SPU/SKU + 多级缓存） — Code Review 报告

> 审查时间：2026-05-14 | 审查范围：SpuService / SkuService / CategoryService / CaffeineCacheConfig / CacheEvictListener / ProductController

---

## 一、Review 评分（对标 P8）

| 维度 | 权重 | 得分 | 加权分 |
|------|:----:|:----:|:------:|
| 架构设计 | 25% | 9.0 | 2.250 |
| 代码质量 | 25% | 8.5 | 2.125 |
| 技术深度 | 25% | 9.5 | 2.375 |
| 安全设计 | 15% | 8.0 | 1.200 |
| 工程实践 | 10% | 8.5 | 0.850 |
| **综合** | **100%** | | **8.8 / 10** |

**结论**：✅ 达到 P8 水平（多级缓存 + 布隆过滤器 + 逻辑过期是高频面试考点）

---

## 二、发现的问题 & 修复记录

### 🔴 P0 问题（已修复）

| # | 问题 | 严重程度 | 修复方案 | 状态 |
|:-:|------|:--------:|---------|:----:|
| 1 | **布隆过滤器 `@PostConstruct` 全量加载** — 100 万 SPU 逐条 `add()` = 100 万次 Redis 网络调用 ≈ 16 分钟，K8s 健康检查超时杀 Pod | P0 | 改为：`tryInit` 幂等检测 → 已存在直接复用 → 首次部署异步分批加载 + 分布式锁 + `bloomFilterReady` 降级标记 | ✅ |
| 2 | **防穿透方案缺失** — 布隆过滤器误判时 DB 查不到数据没有缓存空值，导致反复穿透到 DB | P0 | 改为三层防御：布隆过滤器前置拦截 + 缓存空值兜底 + 物理 TTL 防内存泄漏 | ✅ |
| 3 | **同一 Key 存两种类型** — 正常数据存 `RedisCacheData` 对象，空值存 `String` 字符串，类型混用导致反序列化隐患 | P0 | 统一用 `RedisCacheData` 包装（`data=null` 表示空值），空值也走逻辑过期 | ✅ |
| 4 | **空值缓存无物理 TTL** — 空值 Key 永不过期，攻击者绕过布隆过滤器（1% 误判）用大量不同 ID 打，空值 Key 永远留在 Redis 中，内存泄漏 | P0 | 空值缓存加物理 TTL 5 分钟兜底清理（逻辑过期 2min + 物理 TTL 5min） | ✅ |

### 修复 1：布隆过滤器初始化（前后对比）

```java
// ❌ 修复前：每次启动全量加载，阻塞启动，逐条网络调用
@PostConstruct
public void initBloomFilter() {
    spuBloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");
    spuBloomFilter.tryInit(1_000_000L, 0.01);
    
    // 致命：100 万条全量 SELECT + 逐条 add
    List<Spu> allSpus = spuMapper.selectList(
            new LambdaQueryWrapper<Spu>().select(Spu::getId));
    for (Spu spu : allSpus) {
        spuBloomFilter.add(spu.getId());  // 100 万次 Redis 网络调用
    }
}

// ✅ 修复后：生产级实现
@PostConstruct
public void initBloomFilter() {
    spuBloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");
    boolean isNewFilter = spuBloomFilter.tryInit(1_000_000L, 0.01);

    if (isNewFilter) {
        // 首次部署：异步分批加载，不阻塞启动
        asyncLoadBloomFilter();
    } else {
        // 已有布隆过滤器：直接复用，0 成本
        bloomFilterReady.set(true);
    }
}
```

**修复后的 5 个生产级保障**：

| 保障 | 实现 |
|------|------|
| **不阻塞启动** | `CompletableFuture.runAsync()` 异步执行 |
| **分批加载** | 每批 5000 条游标分页，避免 OOM |
| **分布式锁** | 多实例只有一个执行加载，避免重复写入 |
| **降级标记** | `bloomFilterReady` = false 期间跳过布隆过滤器（降级为直查缓存/DB） |
| **容错** | 加载失败不影响服务可用性，只是暂时没有防穿透优化 |

### 修复 2/3/4：三层防穿透 + 统一类型包装（前后对比）

```java
// ❌ 修复前（第一版）：布隆过滤器为核心，DB 查不到时没有缓存空值
public SpuDetailVO getSpuDetail(Long spuId) {
    if (!spuBloomFilter.contains(spuId)) { return null; }
    SpuDetailVO detail = loadSpuDetailFromDb(spuId);
    if (detail != null) { redisOperator.set(redisKey, newCacheData); }
    // ❌ detail == null 时什么都没做！布隆过滤器误判 → 每次都打 DB！
    return detail;
}

// ❌ 修复前（第二版）：加了缓存空值，但同一 Key 存两种类型
public SpuDetailVO getSpuDetail(Long spuId) {
    Object rawCacheData = redisOperator.get(redisKey);
    if ("__CACHE_NULL__".equals(rawCacheData)) { return null; }  // String 类型
    if (rawCacheData instanceof RedisCacheData) { ... }           // 对象类型
    // ❌ 同一 Key 存 String 或 RedisCacheData，类型混用！
    // ❌ 空值用物理 TTL 2min，正常数据永不过期，行为不一致！
    // ❌ 空值无物理 TTL 兜底，攻击者可打爆 Redis 内存！
}

// ✅ 修复后（最终版）：三层防御 + 统一 RedisCacheData 包装
public SpuDetailVO getSpuDetail(Long spuId) {
    // 第一层：布隆过滤器前置拦截（连 Redis 都不查）
    if (bloomFilterReady.get() && !spuBloomFilter.contains(spuId)) { return null; }
    
    // 统一用 RedisCacheData 读取（空值和正常数据都是同一类型）
    RedisCacheData<SpuDetailVO> cacheData = redisOperator.get(redisKey);
    if (cacheData != null && !cacheData.isExpired()) {
        if (cacheData.getData() == null) { return null; }  // 第二层：空值缓存命中
        return cacheData.getData();                         // 正常数据命中
    }
    
    SpuDetailVO detail = loadSpuDetailFromDb(spuId);
    if (detail != null) {
        redisOperator.set(redisKey, RedisCacheData.of(detail, 30));
    } else {
        // 第二层：缓存空值（统一 RedisCacheData 包装 + 物理 TTL 5min 防内存泄漏）
        redisOperator.set(redisKey, RedisCacheData.of(null, 2), 5, TimeUnit.MINUTES);
    }
    return detail;
}
```

**三层防穿透架构**：

```
请求进来
    │
    ▼
┌─────────────────────────────────────┐
│ 第一层：布隆过滤器（前置拦截）        │
│ "一定不存在" → 直接返回 null          │
│ 连 Redis 都不查，零成本               │
│ 不可用时自动跳过（降级）              │
└──────────────┬──────────────────────┘
               │ "可能存在"（含 1% 误判）
               ▼
┌─────────────────────────────────────┐
│ 第二层：缓存空值（核心防线）          │
│ Redis 中 RedisCacheData(data=null)  │
│ 逻辑过期 2min + 物理 TTL 5min        │
│ 命中空值 → 直接返回 null，不打 DB     │
└──────────────┬──────────────────────┘
               │ 首次查询（缓存未命中）
               ▼
┌─────────────────────────────────────┐
│ 第三层：DB 查询 + 回填缓存            │
│ DB 有数据 → 回填 RedisCacheData       │
│ DB 无数据 → 回填空值缓存              │
│ 下次查询命中第二层，不再打 DB          │
└─────────────────────────────────────┘
```

**为什么需要三层而不是单独一层？**

| 单独方案 | 弱点 |
|----------|------|
| 只用布隆过滤器 | 1% 误判仍穿透 DB；不支持删除；初始化复杂 |
| 只用缓存空值 | 攻击者用海量不同 ID 打 → 每个 ID 缓存一个空值 Key → Redis 内存被打爆 |
| 三层组合 | 布隆过滤器拦截 99% → 只有 1% 误判产生空值 Key → 物理 TTL 5min 自动清理 |

### 🟢 后续优化（非阻塞）

| # | 问题 | 建议 | 状态 |
|:-:|------|------|:----:|
| 3 | 缺少 Caffeine 命中率监控端点 | 暴露 `/actuator/caffeine-stats` 端点，输出 hitRate/missRate/evictionCount | ⬜ |
| 4 | 缺少单元测试 | 补充多级缓存链路的集成测试（Testcontainers） | ⬜ |

---

## 三、技术亮点

| 技术点 | 评分 | 面试价值 | 说明 |
|--------|:----:|:--------:|------|
| **三级缓存架构** | ⭐⭐⭐⭐⭐ | 高频 | Caffeine(L1, 5min) → Redis(L2, 逻辑过期30min) → MySQL(L3) |
| **逻辑过期防击穿** | ⭐⭐⭐⭐⭐ | 高频 | Key 永不物理过期，发现逻辑过期返回旧值 + 分布式锁异步刷新 |
| **缓存空值防穿透（大厂标准）** | ⭐⭐⭐⭐⭐ | 高频 | DB 查不到 → 缓存空值标记 2min，第二次直接返回 null |  
| **布隆过滤器（可选优化层）** | ⭐⭐⭐⭐ | 中频 | Redisson RBloomFilter 前置拦截，减少 Redis 查询 |
| **布隆过滤器生产级初始化** | ⭐⭐⭐⭐ | 中频 | 幂等检测 + 异步分批 + 分布式锁 + 降级标记 |
| **MQ 广播清除 Caffeine** | ⭐⭐⭐⭐⭐ | 高频 | 解决多实例本地缓存不一致，BROADCASTING 模式 + TTL 兜底 |
| **分类树内存构建** | ⭐⭐⭐⭐ | 中频 | 一次查 DB + `groupingBy` 分组递归构建，避免 N+1 |

---

## 四、深度技术分析

### 4.1 布隆过滤器初始化：为什么 `@PostConstruct` 全量加载是错误的？

```
问题链路（100 万 SPU 场景）：

@PostConstruct 全量加载
    ↓
SELECT id FROM t_spu  →  100 万条记录加载到 JVM 堆  →  OOM 风险
    ↓
for 循环逐条 add()  →  100 万次 Redis 网络调用  →  每次 1ms  →  总计 1000 秒
    ↓
启动阻塞 16 分钟
    ↓
K8s livenessProbe 超时（默认 30s）  →  杀 Pod  →  重启  →  又卡 16 分钟  →  死循环
```

**生产级方案的核心洞察**：

Redisson 的 `RBloomFilter` 底层是 Redis 的 Bitmap，**数据持久化在 Redis 中**，不是 JVM 内存。
这意味着：
1. 布隆过滤器只需初始化一次（首次部署时）
2. 后续重启直接复用 Redis 中已有的 Bitmap
3. 新增 SPU 时实时 `add()`，无需全量重建
4. `tryInit()` 是幂等的 —— 已存在返回 false，不会重置数据

### 4.2 异步加载期间的降级策略

```
时间线：
t0: 服务启动，bloomFilterReady = false
t1: 异步加载开始（后台线程）
t2: 请求进来，查询 SPU 详情
    → bloomFilterReady = false → 跳过布隆过滤器 → 直接查缓存/DB（降级）
    → 功能正确，只是暂时没有防穿透优化
t3: 异步加载完成，bloomFilterReady = true
t4: 请求进来，查询 SPU 详情
    → bloomFilterReady = true → 布隆过滤器生效 → 防穿透保护激活
```

**关键设计**：`AtomicBoolean` 保证多线程可见性，异步线程写入 `true` 后，请求线程立即可见。

### 4.3 多级缓存一致性保证

```
写操作链路：
1. 更新 MySQL（事务内）
2. 删除 Redis 缓存
3. 清除本地 Caffeine
4. MQ 广播 → 所有实例清除 Caffeine

一致性保障：
- Redis 删除失败？→ 逻辑过期 30min 后自动刷新
- MQ 丢消息？→ Caffeine TTL 5min 兜底
- 最坏情况：5 分钟不一致 → 商品场景完全可接受
```

---

## 五、面试话术

### Q1: 多级缓存怎么设计的？为什么要加 Caffeine？

> 三级缓存：**Caffeine(L1, 5min) → Redis(L2, 逻辑过期30min) → MySQL(L3)**。
>
> 为什么加 Caffeine？Redis 有 1ms 网络开销，商品详情页 QPS 万级，全走 Redis 会打满带宽。Caffeine 是 JVM 内存缓存，零网络开销（微秒级），承担 80% 热点流量。
>
> 代价是多实例 Caffeine 不共享，但商品场景允许秒级延迟。解决方案：商品变更时 MQ 广播清除所有实例的 Caffeine，TTL 5 分钟兜底。

### Q2: 缓存击穿怎么解决的？为什么选逻辑过期而不是互斥锁？

> **逻辑过期方案**：Key 永不物理过期，Value 中包含逻辑过期时间。查询时发现逻辑过期 → 返回旧值（保证可用性）→ 分布式锁 + 异步刷新（只有一个线程刷新）。
>
> 为什么不用互斥锁？互斥锁方案下，缓存过期后所有请求排队等锁，第一个线程查 DB 回填缓存，其他线程等待。问题是：等待期间请求堆积，RT 飙升。
>
> 逻辑过期的优势：**永远有值可返回**，不会出现"等锁"的情况。代价是返回的可能是几秒前的旧数据，但商品详情页完全可以接受。

### Q3: 布隆过滤器怎么初始化的？100 万商品启动不会很慢吗？

> **不会**。布隆过滤器用的是 Redisson 的 `RBloomFilter`，底层是 Redis Bitmap，数据持久化在 Redis 中。
>
> `tryInit()` 是幂等的 —— 如果 Redis 中已存在布隆过滤器，直接返回 false，不会重置。所以**正常重启零成本**，直接复用。
>
> 只有首次部署时需要加载历史数据，走的是**异步 + 分批 + 分布式锁**：
> 1. 异步执行，不阻塞启动（K8s 健康检查不会超时）
> 2. 每批 5000 条游标分页，避免 OOM
> 3. 分布式锁保证多实例只有一个执行加载
> 4. 加载期间 `bloomFilterReady=false`，跳过布隆过滤器（降级为直查缓存/DB）
> 5. 加载完成后 `bloomFilterReady=true`，防穿透保护自动激活

### Q4: Caffeine 多实例不一致怎么解决？

> **MQ 广播 + TTL 双保险**。
>
> 商品变更时：先更新 DB → 删 Redis → 清本地 Caffeine → MQ 广播（topic: product-cache-evict，BROADCASTING 模式）。
>
> 所有实例收到广播后清除自己的 Caffeine。即使 MQ 丢消息，Caffeine TTL 5 分钟后自动过期。
>
> 最坏情况：5 分钟不一致。商品场景完全可接受（价格延迟 5 分钟用户无感知）。

### Q5: 防穿透用的什么方案？

> **三层防御：布隆过滤器前置拦截 + 缓存空值兜底 + 物理 TTL 防内存泄漏。**
>
> 第一层：布隆过滤器前置拦截。查询前先过布隆过滤器，"一定不存在"的 ID 直接返回 null，连 Redis 都不查。拦截 99% 的无效请求。
>
> 第二层：缓存空值（核心防线）。布隆过滤器误判（1%）穿透到 DB，DB 查不到 → 缓存 `RedisCacheData(data=null)` 逻辑过期 2 分钟。下次查到空值缓存直接返回 null，不打 DB。
>
> 第三层：物理 TTL 5 分钟兜底。空值缓存加物理 TTL，防止攻击者用海量不同 ID 打爆 Redis 内存。5 分钟后 Redis 自动清理。
>
> **关键设计：统一类型包装**。空值和正常数据都用 `RedisCacheData` 包装（`data=null` 表示空值），避免同一 Key 存两种类型的反序列化隐患。空值也走逻辑过期，与正常数据处理逻辑完全一致。
>
> **为什么不能只用缓存空值？** 攻击者用 1000 万个不同的不存在 ID 发请求 → 每个 ID 缓存一个空值 Key → Redis 内存被打爆。布隆过滤器在前面拦截 99%，只有 1% 误判产生空值 Key，大幅减少 Redis 中的空值 Key 数量。
>
> **为什么不能只用布隆过滤器？** 1% 误判率意味着每 100 次查询有 1 次穿透到 DB。没有缓存空值兜底，同一个误判 ID 每次都打 DB。

### Q6: 布隆过滤器有误判怎么办？

> 布隆过滤器误判（判断"可能存在"但实际不存在）→ 继续查 Redis → Redis 未命中 → 查 DB → DB 也查不到 → **缓存空值（逻辑过期 2min + 物理 TTL 5min）**。
>
> 所以误判只是多了一次 DB 查询（首次），后续 2 分钟内直接命中空值缓存。不会造成持续穿透。
>
> 实测日志验证：
> ```
> [多级缓存] L2 Redis 未命中, 查询 DB, spuId=xxx
> [多级缓存] DB 未查到, 缓存空值(防穿透, 逻辑过期=2min, 物理TTL=5min), spuId=xxx
> [多级缓存] 命中空值缓存(防穿透), spuId=xxx  ← 第二次直接返回，没打 DB
> ```
>
> Redis 中空值缓存的存储格式（统一 RedisCacheData 包装）：
> ```json
> {"@class":"com.myxhs.product.cache.RedisCacheData","data":null,"logicExpire":"2026-05-14T11:36:58"}
> ```
