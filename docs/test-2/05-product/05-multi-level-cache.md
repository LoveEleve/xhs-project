# 多级缓存全链路分析

> 综合：`BloomFilter(03)` + `RedisCacheData(04)` + `SpuService.getSpuDetail()`
> 验证：`02-product-test.md` §1.1

---

## 1. 三层架构总览

```
GET /spu/2081302094884671490
  │
  ├─ L1: 布隆过滤器      ──  ~0.5ms（Redisson GETBIT，7 次）
  │     contains(id) == false → 直接返回 null
  │     contains(id) == true  → 继续
  │
  ├─ L2: Redis 逻辑过期   ──  ~1ms（GET + 反序列化）
  │     未过期 → 返回 data
  │     过期 + data!=null → 返回旧值 + 异步刷新
  │     data==null → 空值缓存过期 → 继续
  │     null → Key 不存在 → 继续
  │
  └─ L3: MySQL            ──  ~5ms（SPU + SKU + Category）
       有数据 → 回填 L2
       无数据 → 回填空值缓存
```

---

## 2. 逐层防御职责

| 层 | 防什么 | 机制 | 失败表现 |
|:--:|------|------|------|
| L1 | 缓存穿透（海量不存在 ID） | Bloom 拦截 99% | 降级跳过（bloomFilterReady=false） |
| L2 | 缓存击穿（热点 Key 物理过期） | 逻辑过期 + 单线程异步刷新 | 返回旧值 |
| L2+空值 | 缓存穿透兜底（Bloom 漏掉的 1%） | null 缓存 + 物理 TTL 5min | 穿透 DB（1 次/5min） |
| L3 | 兜底一切 | 永远可查 | —（最终防线） |

**Redis 完全不可用时的退化**：三层架构退化为两层——Bloom(L1) + MySQL(L3)。Bloom 仍可拦截不存在 ID（bit 数组在 Redis 中，如果 Redis 不可用 Bloom 也不可用）。`RedisOperator.get()` 抛出 `RedisUnavailableException` → `GlobalExceptionHandler` 返回 500。这是 fail-fast 设计——因为 Redis 不可用时如果降级到 DB，瞬时的 QPS 会压垮 MySQL。宁可返回错误，也不让 DB 承压。

---

## 3. 三种请求链路时序

### 3.1 热数据（缓存命中）— 99% 请求

```
T0:  请求到达 GetMapping("/spu/{spuId}")
T0:  bloomFilterReady=true → contains(id)=true                       (0.5ms)
T1:  redisOperator.get(key) → RedisCacheData{...}                     (1ms)
T1:  cacheData.isExpired()=false → return cacheData.getData()         (0ms)
总延迟: ~1.5ms, DB 查询: 0 次
```

### 3.2 冷数据（缓存逻辑过期）

```
T0:  Bloom.contains(id)=true                                          (0.5ms)
T1:  Redis GET → RedisCacheData{...} → isExpired()=true                (1ms)
T1:  data != null → asyncRefreshCache(spuId)                           (异步)
T1:  return cacheData.getData()（旧值，不等待刷新）                     (0ms)
总延迟: ~1.5ms（与热数据相同！）, DB 查询: 1 次（异步，不阻塞请求）
```

### 3.3 冷启动（缓存完全缺失）

```
T0:  Bloom.contains(id)=true                                          (0.5ms)
T1:  Redis GET → null                                                  (1ms)
T2:  spuMapper.selectById(spuId)                                      (~3ms)
T2:  skuMapper.selectList(where spuId）                               (~1ms)
T2:  categoryMapper.selectById(categoryId)                            (~1ms)
T3:  redisOperator.set(key, RedisCacheData{...})                       (1ms)
T3:  return SpuDetailVO
总延迟: ~7.5ms, DB 查询: 3 次（SPU + SKU + Category）
```

---

## 4. 缓存雪崩防护

雪崩：大量 Key 同时过期 → 海量请求穿透 DB。

**当前方案如何防止**：

1. **逻辑过期 ≠ 物理过期**：Key 不会被 Redis 同时删除。每个 SPU 的缓存是在**第一次被访问时**回填的——各自的 `logicExpire` 天然分散，不存在"所有 Key 同一秒过期"的情况。
2. **单线程异步刷新**：即使多个 Key 同时被判定为逻辑过期，每个 Key 的刷新受 RLock 保护——最多为每个过期 Key 执行 1 次 DB 查询，而不是每个并发请求都查一次 DB。
3. **旧值始终可用**：逻辑过期后不删除缓存——返回旧值，用户仍能看到数据。物理过期是"缓存不存在→穿透"，逻辑过期是"缓存过期→异步更新→旧值先顶着"。

**仍然存在风险**：系统重启后首次部署——所有 Key 都不存在，首批请求全部走 L3。但启动阶段的 QPS 远低于稳态——首页商品列表通常就十几条——DB 不会被打穿。

---

## 5. Bloom + 空值缓存的组合防护

```
攻击流量: 10000 个随机雪花 ID
  ↓
L1 Bloom:  contains=false  ← 拦截 9900 个（99%）
            contains=true   ← 100 个误判穿透到 L2
  ↓
L2 Redis:  95 个 miss → 查 DB → 都不存在 → 设置空值缓存（TTL 5min）
            5 个 命中（之前已缓存空值）
  ↓
结果: DB 只被查询了 95 次（0.95%），不是 10000 次。
      后续 5min 内：同样 ID 的查询全部命中空值缓存，0 次 DB 查询。
```

---

## 6. 发散：与 counter 模块缓存对比

| 维度 | product | counter |
|------|------|------|
| Redis 角色 | 缓存（读加速） | 数据源（权威值） |
| 缓存未命中的含义 | 正常（冷数据） | 异常（Key 应始终存在） |
| 防穿透 | Bloom + 空值缓存 | 不需要（不存在的 Key 值=0） |
| 防击穿 | 逻辑过期 + RLock | 不需要（值不会"过期"） |
| 防雪崩 | 逻辑过期天然分散 | Buffer 攒批合并写入 |
| 缓存失效 | 写操作删除 | 永不被删（INCR/DECR 更新） |

product 和 counter 的 Redis 虽然都叫"缓存"，但语义完全不同。product 的 Redis 丢失后可以从 MySQL 重建——counter 的 Redis 丢失后只能用 MySQL 恢复（对账修复）。对账修复是 counter 独有的补偿机制，product 不需要——因为 product 的缓存从来不是权威数据源。

---

## 7. 发散：单 Redis Sentinel vs Redis Cluster

| 维度 | Redis Sentinel（当前） | Redis Cluster（分片） |
|------|------|------|
| 架构 | 一主多从 + 哨兵自动切换 | 多主多从 + 16384 槽分配 |
| 容量 | 单节点内存（~64GB） | 横向扩展（N × ~64GB） |
| 故障切换 | Sentinel 自动 | 选举新主 |
| mget/pipeline | 无限制 | 仅限同槽 Key |
| 运维复杂度 | 低 | 高（槽迁移、resharding） |
| 适用场景 | 数据 < 10GB | 数据 > 50GB |

当前方案单 Redis Sentinel 足够——100 万 SPU 详情缓存约 5GB。Cluster 的跨槽 pipeline 限制和维护成本在现阶段不值得。

---

## 关联文档

- `01-product-module.md` — §3 多级缓存架构
- `03-bloom-filter.md` — L1 布隆过滤器
- `04-logical-expire.md` — L2 逻辑过期
