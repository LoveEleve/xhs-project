# 布隆过滤器：懒加载 + 持久化 + 容错降级

> 源码：`SpuService.initBloomFilter()` + `asyncLoadBloomFilter()`
> 验证：`02-product-test.md` §1.1

---

## 1. 为什么商品系统需要布隆过滤器？

用户访问 `GET /spu/2081302094884671490`，这是已存在的商品。

但如果攻击者用随机 ID 遍历：`GET /spu/99999999999`, `GET /spu/99999999998`, ...——每个都不存在。缓存穿透链：

```
Redis GET → null → 查 DB → 也不存在 → 返回 null
问题：每个不存在的 ID 都打到了 DB
```

**布隆过滤器的职责**：在 Redis 之前拦截"一定不存在"的 ID。

```
请求 → Bloom.contains(id) == false → 直接返回 null（不到 Redis/DB）
请求 → Bloom.contains(id) == true → 继续走 L2+L3（误判率 1% 的请求会穿透）
```

---

## 2. 布隆过滤器原理

### 数据结构

```
新增 SPU 2081302094884671490:
  hash1(id) % m = 42  → 位数组 bit[42] = 1
  hash2(id) % m = 71  → 位数组 bit[71] = 1
  hash3(id) % m = 99  → 位数组 bit[99] = 1

查询 SPU 2081302094884671490:
  hash1(id) % m = 42  → bit[42] = 1 ✓
  hash2(id) % m = 71  → bit[71] = 1 ✓
  hash3(id) % m = 99  → bit[99] = 1 ✓
  → 可能存在（3 个位置都是 1）

查询不存在的 ID：
  hash1(id) % m = 42  → bit[42] = 1 ✓
  hash2(id) % m = 71  → bit[71] = 1 ✓
  hash3(id) % m = 88  → bit[88] = 0 ✗
  → 一定不存在（有一个位置是 0）
```

### 数学属性

| 参数 | 含义 | 本项目的值 |
|------|------|:--:|
| `n` | 预期元素数量 | 1,000,000 |
| `p` | 误判率 | 0.01 (1%) |
| `m` | 位数组大小 | `-n·ln(p) / (ln2)²` ≈ 9,585,059 bits ≈ 1.14 MB |
| `k` | 哈希函数数量 | `(m/n)·ln2` ≈ 7 |

**1% 误判率的含义**：每 100 个不存在 ID 中，有 1 个会被 Bloom "认为可能存在"而穿透到 Redis+MySQL。这 1 个穿透请求被 L2 空值缓存兜底——同一个 ID 只穿透一次 DB。

---

## 3. Redisson RBloomFilter 实现

```java
@PostConstruct
public void initBloomFilter() {
    spuBloomFilter = redissonClient.getBloomFilter("myxhs:product:bloom:spu");

    // tryInit 幂等：Redis 中已存在则复用（不重置），首次创建返回 true
    boolean isNewFilter = spuBloomFilter.tryInit(1_000_000L, 0.01);

    if (isNewFilter) {
        asyncLoadBloomFilter();  // 首批部署：异步加载历史数据
    } else {
        // 已有布隆过滤器，检查是否为空（上次加载可能中途失败）
        if (spuBloomFilter.count() == 0) {
            asyncLoadBloomFilter();  // 重新加载
        } else {
            bloomFilterReady.set(true);  // 立即可用
        }
    }
}
```

### 关键设计点

**`tryInit` 的幂等性**：`tryInit(expectedInsertions, falseProbability)` 在 Key 不存在时使用 SETBIT/GETBIT 创建位数组并返回 true，Key 已存在则返回 false（不重建）。这保证了重启后直接复用——只有首批部署才触发全量加载。

**`count() == 0` 二次检查**：即使 `tryInit` 返回 false（Key 存在），也可能因为上一次加载过程中 JVM 崩溃导致数据不完整。二次检查捕获这种边缘情况。

**`bloomFilterReady` (AtomicBoolean)**：加载完成前为 false。在此期间，`getSpuDetail` 跳过布隆过滤器，直接走 L2+L3——功能正确，只是没有防穿透优化。

---

## 4. 异步加载机制

```java
CompletableFuture.runAsync(() -> {
    // 1. 分布式锁：多实例只有一个执行
    RLock lock = redissonClient.getLock("myxhs:product:bloom:spu:init-lock");
    boolean locked = lock.tryLock(0, 300, TimeUnit.SECONDS);

    // 2. 二次检查：拿锁后再确认（可能其他实例已完成）
    if (spuBloomFilter.count() > 0) return;

    // 3. 游标分页：每批 5000 条，sleep 100ms 降低 DB/Redis 压力
    long lastId = 0;
    while (true) {
        List<Spu> batch = spuMapper.selectList(
            where id > lastId ORDER BY id LIMIT 5000
        );
        if (batch.isEmpty()) break;
        for (Spu spu : batch) { spuBloomFilter.add(spu.getId()); }
        lastId = batch.get(batch.size() - 1).getId();
        Thread.sleep(100);
    }

    // 4. 加载完成
    bloomFilterReady.set(true);
}, SPU_ASYNC_EXECUTOR);
```

**为什么不能每次启动都全量加载？**

| 方案 | 100 万 SPU 加载耗时 | K8s 健康检查 | 问题 |
|------|:---:|:---:|------|
| 每次启动全量加载 | ~16 分钟（100 万次 add × 1ms RTT） | 超时 | Pod 被杀 → 死循环 |
| 一次加载，持久化复用（当前） | 0（直接复用） | 立即通过 | 只有首批部署才加载 |

**为什么每批只加 sleep 100ms？**

`BF.ADD` 是 O(k) 操作（k=7 次哈希 + 7 次 SETBIT），每批 5000 个 Key × 7 次 SETBIT = 35000 次 Redis 操作。100ms 的 sleep 将 Redis 负载从"连续 35000 次/批"摊平为"35000 次/批，批次间隔 100ms"——避免 Redis 被一个操作独占。

---

## 5. 容错降级链

```
bloomFilterReady == false（加载中/加载失败）
  → getSpuDetail 跳过 bloomFilter.contains()
  → 直接走 Redis → MySQL
  → 功能正确，只是没有防穿透优化

bloomFilterReady == true（加载完成）
  → bloomFilter.contains(id) == false → 返回 null（拦截不存在 ID）
  → bloomFilter.contains(id) == true  → 继续 L2+L3（含 1% 误判穿透）
```

**Redis 中布隆过滤器被误删**：`tryInit` 幂等——下一次 `initBloomFilter` 检测到 `isNewFilter=true`，触发 `asyncLoadBloomFilter`。加载期间降级到无布隆过滤器模式，加载完成后恢复。

---

## 6. 发散：布隆过滤器 vs 布谷鸟过滤器

布隆过滤器有一个根本局限：**不支持删除**。下架的 SPU 不能从布隆过滤器中移除。

布谷鸟过滤器（Cuckoo Filter）解决了这个问题：

| 维度 | 布隆过滤器 | 布谷鸟过滤器 |
|------|:---:|:---:|
| 支持删除 | ❌ | ✅ |
| 空间效率（相同误判率） | ~9.6 bits/元素 | ~8 bits/元素 |
| 查询性能 | O(k) = O(7) 哈希 | O(1)（两个候选桶之一） |
| 插入性能 | O(k) | 最坏 O(∞)（可能需要重排） |
| Redis 支持 | ✅ `BF.RESERVE` / `BF.ADD` / `BF.EXISTS`（内置） | ❌ 需自行实现 |
| 成熟度 | 50 年（1970 Burton Bloom） | 10 年（2014 Fan et al.） |

**为什么不选布谷鸟过滤器？**

1. **商品 SPU 只增不删**：下架 ≠ 删除，SPU 记录永存。布隆过滤器的"不支持删除"不是问题——恰好匹配业务语义。
2. **Redis 原生支持**：`BF.*` 命令在 Redis Stack 中内置，而布谷鸟过滤器需要 Lua 脚本或客户端实现，运维复杂度更高。
3. **容量已知**：100 万 SPU，布隆过滤器 bit 数组仅 ~1.14 MB，Redis 内存无压力。

**什么时候该用布谷鸟过滤器？**

- 元素会频繁增删的场景（如短链接服务、爬虫 URL 去重、动态 IP 黑名单）
- 需要极高空间效率（相同误判率下布谷鸟省 ~20% 空间）

---

## 7. 发散：计数布隆过滤器

另一个布隆过滤器的局限：不能扩容。如果 SPU 从 100 万增长到 500 万，1% 误判率的容量需要重新计算并重建。

**计数布隆过滤器（Counting Bloom Filter）**：每个位从 1 bit 变成 N bit 计数器。

```
Bloom:         bit[i] ∈ {0,1}        → 只支持 add
CountingBloom: counter[i] ∈ {0..255}  → 支持 add + delete
```

但商品系统不需要这个——SPU ID 不删除，不需要计数器。如需扩容，可以先 `DELETE` Key 再重新 `tryInit` 触发全量重建。

---

## 8. 发散：可扩展布隆过滤器

标准布隆过滤器的容量在创建时固定——如果实际数据量超过预期的 100 万，误判率会高于预设的 1%。

**Scalable Bloom Filter**（可扩展布隆过滤器）解决了这个问题：

```
初始化: Bloom-1（容量 1000，误判率 0.01）
数据量达到 1000 → 创建 Bloom-2（容量 2000，误判率 0.005 = 0.01 × 0.5）
数据量达到 2000 → 创建 Bloom-3（容量 4000，误判率 0.0025 = 0.01 × 0.25）
...
查询: 检查 Bloom-1, Bloom-2, ..., Bloom-N → 任意一个命中 = 可能存在
```

**关键属性**：
- 每个新 Bloom 容量翻倍、误判率等比缩减（如 ×0.5）
- 误判率之和收敛于一个上界（等比数列：0.01 + 0.005 + 0.0025 + ... < 0.02）
- 最坏查询时间 = O(B)（B 为 Bloom 堆叠层数）——通常 B < 10

| 维度 | 标准 Bloom | Scalable Bloom |
|------|:---:|:---:|
| 容量 | 固定 | 自动扩展 |
| 误判率 | 超出容量后上升 | 始终 < 上界 |
| 查询性能 | O(k) | O(k·B) |
| 实现复杂度 | 低 | 中 |

当前产品模块为什么不选 Scalable Bloom？100 万 SPU 的容量预估值是合理的——达到这个量级需要数年。且 `tryInit` + 重建的开销（全量加载一次）低于 Scalable Bloom 的维护成本（每次查询多 B-1 个 Bloom 的 `BF.EXISTS`）。

---

## 9. 已知限制

| 限制 | 说明 | 影响 |
|------|------|------|
| 不支持删除 | SPU 下架后 ID 仍在 Bloom 中 | 对商品场景无影响（下架 ≠ 删除） |
| 误判率 1% | 100 万 SPU 时，1 万个不存在 ID 可能穿透 | 被 L2 空值缓存兜底 |
| 容量不可扩容 | 超过 100 万 SPU 后误判率上升 | 需重建（`tryInit(2M)` + `asyncLoad`） |
| 加载期间无保护 | `bloomFilterReady=false` 时降级 | 功能正常，只是暂时无防穿透 |

---

## 关联文档

- `01-product-module.md` — §5 布隆过滤器
- `02-product-test.md` — §1.1 布隆过滤器行为验证
