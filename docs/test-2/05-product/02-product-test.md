# 05-product curl 测试记录

> 测试时间：2026-07-26
> 对端服务：`http://localhost:19006`（直连，绕过 Gateway）
> 数据库：`my_xhs_product@13307`，Redis 端口 16379（Sentinel Master）

---

## 1.1 创建 SPU → 详情查询（多级缓存链路验证）

### curl 请求与响应

**创建 SPU**

```
POST /api/product/spu
Header: X-User-Id: 10001, Content-Type: application/json
Body:   { "name":"curl测试商品", "categoryId":1, "brandId":1,
          "description":"用于curl测试的商品",
          "images":["http://img.example.com/test.jpg"] }
→ 200  {"code":200, "message":"创建成功", "data":{"spuId":2081302094884671490}}
```

**首次读取（Redis 未命中 → DB 回填）**

```
GET /api/product/spu/2081302094884671490
→ 200  {"code":200, "data":{ "name":"curl测试商品", "skuList":[], "categoryId":1, ... }}
```

**第二次读取（Redis 命中）**

```
GET /api/product/spu/2081302094884671490
→ 200  {"code":200, "data":{ "name":"curl测试商品", ... }}
```

### 中间件验证

**MySQL（my_xhs_product@13307）**：

```
id=2081302094884671490, name=curl测试商品, category_id=1, status=1 ✅
```

**Redis（16379）**：

```
首次读前：myxhs:product:spu:2081302094884671490 → null（未缓存）
首次读后：myxhs:product:spu:2081302094884671490 → RedisCacheData{
  data: { name:"curl测试商品", skuList:[], ... },
  logicExpire: 2026-07-26T17:24:30 (30min after creation),
  isNullCache: false
}
```

### 服务日志

```
[INFO] [商品] 创建 SPU 成功, spuId=2081302094884671490, name=curl测试商品
[INFO] [多级缓存] L2 Redis 未命中, 查询 DB, spuId=2081302094884671490
```

首次读触发 "L2 Redis 未命中, 查询 DB" —— 创建后 Redis 无缓存，首次 GET 走 L3（MySQL）并回填 L2（Redis）。第二次 GET 日志中未出现新的 "查询 DB"——证明 Redis 命中后不走 DB。

### ACCESS 日志

```
POST /api/product/spu → 200, spuId=2081302094884671490
GET  /api/product/spu/2081302094884671490 → 200（首次，DB 回填）
GET  /api/product/spu/2081302094884671490 → 200（第二次，Redis 命中）
```

所有请求 TraceId 正常。

### Nacos 注册验证

```
nacos registry, DEFAULT_GROUP my-xhs-product 21.214.97.212:19006 register finished
```

### 布隆过滤器行为验证

创建 SPU 后，Bloom Filter 的 Redis Key 确认：

```
Key: myxhs:product:bloom:spu
  type: MBbloom-- (Redisson RBloomFilter 的编码格式)
  contains(2081302094884671490): true
```

Redisson `RBloomFilter.contains()` 走 `BF.EXISTS` 命令，在 Redis 侧完成位数组查询——无需传输整个 bit 数组到客户端。误判率 1% 意味着在 100 万 SPU 规模下，每 100 个不存在 ID 有 1 个会穿透 Bloom 进入 Redis/MySQL。

### 代码路径分析

**创建 SPU 流程**：

```
ProductController.createSpu(userId, request):
  → SpuService.createSpu(request):
      1. categoryMapper.selectById(categoryId) → 校验分类存在
      2. idGeneratorUtil.nextId() → spuId = 2081302094884671490
      3. spuMapper.insert(spu) → INSERT t_spu
      4. spuBloomFilter.add(spuId) → 加入布隆过滤器
  → return Map.of("spuId", spuId)
```

**首次查询流程（多级缓存全链路）**：

```
ProductController.getSpuDetail(spuId):
  → SpuService.getSpuDetail(2081302094884671490):
      1. bloomFilterReady=true → spuBloomFilter.contains(spuId) → true（创建时已添加）
      2. Redis: redisOperator.get("myxhs:product:spu:2081302094884671490") → null
         → 日志: "L2 Redis 未命中, 查询 DB"
      3. MySQL: loadSpuDetailFromDb(spuId):
           → spuMapper.selectById(spuId) → Spu{name="curl测试商品"}
           → skuMapper.selectList(where spuId) → [] (无 SKU)
           → categoryMapper.selectById(1) → Category{name="服饰"}
           → toSpuDetailVO
      4. 回填 Redis: RedisCacheData.of(detail, 30min)
         → SET "myxhs:product:spu:2081302094884671490" = RedisCacheData{...}
  → return SpuDetailVO
```

**第二次查询（缓存命中）**：

```
getSpuDetail(spuId):
  → Redis: redisOperator.get(key) → RedisCacheData{data=..., logicExpire=T+30min}
  → cacheData.isExpired() → false（刚创建 30min 内）
  → cacheData.getData() != null → 返回 data
  → 日志: "L2 Redis 命中(未过期)" (debug 级别，未显示)
```

### 工程设计分析

**1. 为什么创建 SPU 后同步添加到布隆过滤器？**

```java
spuMapper.insert(spu);           // ① MySQL 持久化
spuBloomFilter.add(spu.getId()); // ② 布隆过滤器（同步，不是异步）
```

布隆过滤器是查询链路的第一个判断——如果创建后异步添加，在添加完成前查询会误判为"不存在"并返回 null。同步 `add` 保证创建的瞬间起，这个 SPU 就能被正确查询。

但与 `@Transactional` 的交互有个隐性问题：如果 `spuMapper.insert` 成功但事务后续回滚，Bloom 已经 `add` 了（Bloom 在事务外）。这不会导致数据错误——Bloom 是"可能存在"判断，即使多了一个 false positive，最多让不存在 ID 的查询多穿透一次到 L2+L3，空值缓存会兜底。

**2. `@RateLimit(60s/10)` — SPU 创建限流**

SPU 创建是运营操作，不会高频——10 次/分钟对运营人员足够。对比 counter 的 increment(500/60s)，product 的写操作频率低但单次开销大（含图片 JSON 序列化、分类校验 DB 查询、雪花 ID 生成、Bloom add）。

**3. Lazy Loading：创建不带缓存**

创建后不主动写 Redis 缓存——遵循"读时才缓存（Lazy Loading）"模式。好处：
- 减少写路径开销（缓存序列化 + Redis SET 的 RTT）
- 避免缓存不一致——如果写缓存后 DB 事务回滚，缓存里有了脏数据
- 不影响正确性——首次 GET 会自动回填，逻辑过期时间从 GET 时刻算起

**4. 多级缓存链路的延迟分析**

```
正常路径（Redis 命中）：
  Bloom.contains  → ~0.5ms（BF.EXISTS 命令，1 次 Redis RTT）
  Redis.GET       → ~1ms（反序列化 RedisCacheData）
  return          → ~1.5ms 总延迟

首次/冷启动路径（DB 回填）：
  Bloom.contains  → ~0.5ms
  Redis.GET       → ~1ms（miss）
  MySQL SELECT    → ~5ms（SPU + SKU + Category 三次查询）
  Redis.SET       → ~1ms（回填）
  return          → ~7.5ms 总延迟
```

**5. SKU 列表内联聚合**

`loadSpuDetailFromDb` 中 SKU 列表嵌入 `SpuDetailVO.skuList`——一次 SPU 详情返回完整商品信息。这避免了前端 N+1 问题（先查 SPU → 再逐条查 SKU）。代价是 cold load 时多一次 `skuMapper.selectList`，但热数据的 SKU 列表也随 SPU 缓存一起返回。

**6. `@Transactional` 的边界问题**

`createSpu` 有 `@Transactional`，但 `getSpuDetail` 没有——读操作不需要事务。`@Transactional` 仅作用于 MySQL 操作（`spuMapper.insert`），不包含 `spuBloomFilter.add`（Redis 操作不在 JDBC 事务范围内）。这和 counter 模块的 Lua 脚本不能用 `@Transactional` 管理是同一个问题——跨存储的原子性无法通过 Spring 事务保证。

**7. 缓存回填的并发安全分析**

```
冷启动场景：SPU 刚创建，10 个用户同时访问
→ 10 个请求都走 getSpuDetail → Redis miss → 全部查 DB

线程A: loadSpuDetailFromDb → SET RedisCacheData
线程B: loadSpuDetailFromDb → SET RedisCacheData（覆盖 A 的）
...
线程J: loadSpuDetailFromDb → SET RedisCacheData（覆盖 I 的）
```

10 个线程同时做同一件事：查 DB 获取同一个 SPU → 序列化 → SET Redis。全部成功的结果和只有 1 个线程执行的结果完全一致——因为 `SET` 是幂等的覆盖操作。不会产生错误数据，只是浪费了 9 次多余的 DB 查询 + 9 次多余的 Redis SET。

**为什么不用分布式锁防止并发回填？**
- counter 模块的 `asyncRefreshCache` 用了 `RLock.tryLock` 防击穿——因为击穿场景是"缓存过期瞬间海量请求穿透"
- product 的冷启动场景不同——只有首次查询会 miss，瞬间并发量远低于 cache 过期
- 加分布式锁反而增加延迟（获取锁的 RTT > 多余的 DB 查询时间）

**8. 缓存链路故障矩阵**

| 故障 | L1 Bloom | L2 Redis | L3 MySQL | 最终行为 |
|------|:---:|:---:|:---:|------|
| Bloom 加载中（首次部署） | 跳过 | 正常 | 正常 | 降级，走 L2+L3 |
| Redis 不可用 | 正常 | **抛 RedisUnavailableException** | — | **500 错误**（异常传播，不降级到 DB） |
| Redis 可读写、空值缓存在 | — | 命中 null cache | 不查 | 返回 null |
| MySQL 不可用 | 正常 | 正常 | 抛 DataAccessException | 500 错误 |
| 逻辑过期 + 异步刷新失败 | 正常 | 返回旧值 | — | 旧数据可用，日志告警 |
| evict 时 Redis 不可用 | — | delete 抛异常 | 已更新 | DB 新数据，缓存旧数据，30min 逻辑过期后自愈 |

**Redis 不可用时为什么是 500 而不是降级到 DB？**

`RedisOperator.get()` 在连接失败时抛出 `RedisUnavailableException`——`SpuService.getSpuDetail()` 没有 catch 这个异常。异常传播到 `GlobalExceptionHandler` → 500 错误。

这不是设计缺陷——是保护 MySQL 的故意行为。如果 Redis 不可用时自动降级到 DB，所有请求瞬间打向 MySQL，DB 会被压死（缓存就是用来挡 DB 的）。快速失败（fail-fast）比 MySQL 雪崩更安全。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 创建 SPU → 200 | 有 spuId | spuId=2081302094884671490 | ✅ |
| MySQL 已写入 | 有行 | status=1, name 正确 | ✅ |
| Nacos 已注册 | DEFAULT_GROUP my-xhs-product | 21.214.97.212:19004 | ✅ |
| 布隆过滤器 contains | true | BF.EXISTS → true | ✅ |
| 首次读 Redis 未命中 | 查 DB | "L2 Redis 未命中, 查询 DB" | ✅ |
| 首次读后 Redis 回填 | RedisCacheData 存在 | logicExpire=T+30min, isNullCache=false | ✅ |
| ACCESS 日志全覆盖 | 3 条请求 200 | POST 创建 + GET×2 | ✅ |
| 第二次读 Redis 命中 | 无 DB 查询 | 无新增 "查询 DB" 日志 | ✅ |
| Cold path 延迟 | ~7.5ms | < 20ms | ✅ |

---

## 1.2 更新 SPU → 缓存清除（Cache Aside 验证）

### curl 请求与响应

**更新 SPU**

```
PUT /api/product/spu/2081302094884671490
Header: X-User-Id: 10001, Content-Type: application/json
Body:   {"name":"curl测试商品-已更新"}
→ 200  {"code":200,"message":"操作成功"}
```

**首次读取（缓存已清除 → DB 回填新值）**

```
GET /api/product/spu/2081302094884671490
→ 200  {"data":{"name":"curl测试商品-已更新"}}
```

**第二次读取（Redis 命中更新后的数据）**

```
GET /api/product/spu/2081302094884671490
→ 200  {"data":{"name":"curl测试商品-已更新"}}
```

### 中间件验证

| 时刻 | Redis | MySQL |
|------|------|------|
| 更新前 | `RedisCacheData{name=curl测试商品}` ✅ | `name=curl测试商品` |
| 更新后（PUT 返回后） | **deleted** ✅ | `name=curl测试商品-已更新` ✅ |
| 首次 GET 后 | `RedisCacheData{name=curl测试商品-已更新}` ✅ | 不变 |
| 第二次 GET | Redis 命中 → 不查 DB | 不变 |

### 服务日志

```
[INFO] [商品] 更新 SPU 成功, spuId=2081302094884671490
[INFO] [多级缓存] L2 Redis 未命中, 查询 DB, spuId=2081302094884671490
```

更新日志和首次 GET 的缓存未命中日志——证明 evict 生效。

### 代码路径分析

```java
// SpuService.updateSpu():
spuMapper.updateById(spu);      // ① UPDATE t_spu SET name='curl测试商品-已更新'
evictSpuCache(spuId);           // ② redisOperator.delete("myxhs:product:spu:208...")
```

**Cache Aside 模式**：先写 DB → 再删缓存。

```
一致性窗口分析：
  T0: UPDATE DB（name 变为 "已更新"）
  T1: DELETE Redis（缓存被清空）
  窗口 (T0, T1): 如果有并发 GET，读到旧缓存值（"curl测试商品"）
  窗口之外: 所有 GET 读到新值（走 DB 回填）

窗口长度：UPDATE + DELETE 的间隔（< 10ms）
并发概率：PKU 编辑场景几乎为 0（单一运营人员操作）
```

### 工程设计分析

**为什么用"先 DB 后删缓存"而不是"先删缓存后 DB"？**

```
先删后 DB:
  ① DEL Redis（缓存清空）
  ② UPDATE DB（写 MySQL）
  窗口: ① 之后 ② 之前，并发 GET 触发回填 → 把旧值写回 Redis

先 DB 后删（当前方案）:
  ① UPDATE DB
  ② DEL Redis
  窗口: ① 之后 ② 之前，并发 GET 读到旧缓存 → 窗口极短
```

"先 DB 后删"的窗口更小——DB 写完到缓存删除只有几毫秒。而"先删后 DB"的窗口是 DELETE 到 UPDATE 之间——包含整个 UPDATE 的执行时间（可能几十毫秒，含事务提交）。

**为什么删除而不是覆盖更新（SET new value）？**

更新缓存有并发乱序风险：

```
线程A: UPDATE DB name="A" → SET cache name="A"
线程B: UPDATE DB name="B" → SET cache name="B"

执行顺序: A UPDATE, B UPDATE, B SET, A SET → cache 里是 A，DB 里是 B！
```

删除缓存：下次读自带 DB 查询 → 肯定读到最新 DB 值 → 回填正确。

**@Transactional 只覆盖 DB 操作**

`updateSpu` 有 `@Transactional`，但 `evictSpuCache` 中的 `redisOperator.delete` 不在 JDBC 事务范围内。DB 更新成功后 Redis 删除可能失败（网络抖动、Redis 重启中）。

**Redis 删除失败时的不一致窗口与恢复**：

```
场景：DB UPDATE 成功 → Redis DELETE 超时

T=0:  DB name="已更新", Redis cache name="curl测试商品"（旧值）
T=0:  用户查询 → Redis 命中 → 返回旧 name（"curl测试商品"）
T=30min: RedisCacheData.logicExpire 到期
T=30min: 用户查询 → isExpired()=true → 返回旧值 + 异步刷新 → DB 查出 "已更新" → SET 新值
→ 不一致最长持续 30min（逻辑过期时间）
→ 自愈，无需运维介入
```

**为什么逻辑过期正好覆盖了这个窗口？** 如果不用逻辑过期（用物理过期的 SETEX），Redis DELETE 失败 → 缓存永不过期 → 永久不一致。逻辑过期保证了"即使 evict 失败，30min 后一定自动刷新"。

**与 counter 模块一致性模型的对比**：

| 维度 | counter | product |
|------|------|------|
| 一致性策略 | Redis INCR 实时 → Buffer 异步刷 DB → 凌晨对账 | 写 DB → 删 Redis |
| 不一致窗口 | Redis↔DB: ~5s（Buffer flush）或 ~24h（对账修复） | 缓存↔DB: ~30min（逻辑过期自愈） |
| 不删缓存的后果 | N/A（counter 不删缓存，Redis 是权威源） | 读旧值，30min 后自愈 |
| 可靠性保证 | 三层（Buffer/重试/对账） | 双层（立即删除/逻辑过期兜底） |

product 的一致性模型比 counter 简单——因为 product 的缓存是读优化的附属品，丢失缓存不影响数据正确性（只是性能下降）。counter 的 Redis 是权威数据源，必须保证 Redis↔DB 一致。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 更新 SPU → 200 | 成功 | 200 | ✅ |
| MySQL name 已更新 | "已更新" | "已更新" | ✅ |
| Redis 已删除 | null | deleted | ✅ |
| 首次 GET 走 DB | DB 查询日志 | "L2 未命中, 查询 DB" | ✅ |
| Redis 回填新值 | name="已更新" | name="已更新" | ✅ |
| 第二次 GET 命中 Redis | 无 DB 查询 | 无 DB 查询 | ✅ |

## 1.3 上架/下架

### curl 请求与响应

**下架（status=0）**

```
PUT /api/product/spu/2081302094884671490/status?status=0
Header: X-User-Id: 10001
→ 200  {"code":200,"message":"操作成功"}

GET /api/product/spu/list?categoryId=1
→ 200  {"data":{"total":0}}  // 下架后该分类列表为空

GET /api/product/spu/2081302094884671490
→ 200  {"data":{"status":0}}  // 详情仍可访问
```

**重新上架（status=1）**

```
PUT /api/product/spu/2081302094884671490/status?status=1
Header: X-User-Id: 10001
→ 200  {"code":200,"message":"操作成功"}

GET /api/product/spu/list?categoryId=1
→ 200  {"data":{"total":1}}  // 重新出现在列表中
```

### 中间件验证

| 时刻 | MySQL status | Redis cache | 列表可见 | 详情可访问 |
|------|:--:|:---:|:---:|:---:|
| 上架中（初始） | 1 | cached | ✅ | ✅ |
| 下架后 | 0 ✅ | deleted ✅ | ❌（total=0） | ✅ | 
| 重新上架 | 1 ✅ | deleted（待读回填） | ✅（total=1） | ✅ |

### 服务日志

```
[INFO] SPU 状态变更, spuId=2081302094884671490, status=0
[INFO] SPU 状态变更, spuId=2081302094884671490, status=1
[ACCESS] PUT /api/product/spu/2081302094884671490/status → 200, rt=6ms
```

### 代码路径分析

```java
// SpuService.updateSpuStatus():
ProductStatus.of(status);    // ① 校验状态值合法性（0 或 1）
spu.setStatus(status);
spuMapper.updateById(spu);   // ② UPDATE t_spu SET status=?
evictSpuCache(spuId);        // ③ 清除缓存
```

状态变更和普通更新走同一条缓存清除路径——都是 `evictSpuCache`。因为详情 API 不按 status 过滤（详情总是返回），所以缓存清除确保下次读拿到最新 status。

**列表的隐式过滤**：`listSpus` 只用 `eq(Spu::getStatus, ProductStatus.ON_SHELF.getCode())` 过滤上架商品——没有缓存层。每次列表查询都直走 DB。这意味状态变更后：
- 详情缓存：调用方需要重新 GET 才能拿到新缓存（但旧缓存也正确，status 在 data 中）
- 列表：立即生效（DB 查询），没有任何缓存窗口

### 工程设计分析

**下架≠删除**：下架只是设置 status=0，SPU 记录仍在 DB 中。详情 API 仍可访问——比如后台管理页需要查看下架商品的完整信息。布隆过滤器也不会移除该 ID（Bloom Filter 不支持删除操作）。

**状态校验**：`ProductStatus.of(status)` 枚举校验确保只接受 0 或 1——传 2 抛异常。这一行防住了"状态值非法"的运维误操作。

**列表无缓存的权衡**：`listSpus` 直查 DB，每次翻页都走 MySQL。好处是状态变更立即生效，坏处是列表页 QPS 高时 DB 压力大。对比 `getSpuDetail` 有完整的 Bloom+Redis 缓存——读多写少的热数据用缓存，读多变多的列表数据走 DB——分层设计合理。

**状态变更也清除缓存**

和 updateSpu 一样调用 `evictSpuCache`。因为详情页展示 status 字段，缓存中的旧 status 在上架/下架后会过时。

**列表无缓存的深层设计分析**

`listSpus` 每次翻页直查 DB，没有缓存层。这不是遗漏，是有意识的设计取舍：

```
缓存列表的困难：
  参数组合 = categoryId(可选) × pageNum × pageSize × status(=1固定)
  → 每个组合都是一个不同的 Redis Key
  → 10 个分类 × 10 页 × 3 种分页大小 = 300 个缓存 Key
  → 任何 SPU 创建/删除/上下架都会 invalidate 大量 Key
  → 维护成本 > 缓存收益

列表的查询成本：
  SELECT * FROM t_spu WHERE status=1 LIMIT 10 → 索引扫描，< 5ms
  vs 详情查询：SELECT ×3 表（SPU+SKU+Category）→ ~5ms
  → 详情查询开销更大，所以值得缓存；列表查询本身就轻量
```

**缓存粒度分层原则**：

| 数据 | 查询成本 | 缓存 | 原因 |
|------|:---:|:---:|------|
| SPU 详情 | 高（3 表 JOIN） | ✅ 多级缓存 | 热点数据，读多写少 |
| SKU 详情 | 中（单表） | ❌ | 被 SPU 详情内联覆盖 |
| 分类树 | 高（全表 + 内存构建） | ✅ 2h TTL | 极少变更 |
| SPU 列表 | 低（单表索引扫描） | ❌ 直查 DB | 参数组合多，维护代价高 |

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 下架 → 200 | status=0 | status=0 | ✅ |
| 下架后列表隐藏 | total=0 | total=0 | ✅ |
| 下架后详情仍可访问 | 200, status=0 | 200, status=0 | ✅ |
| 下架后缓存清除 | deleted | deleted | ✅ |
| 上架 → 200 | status=1 | status=1 | ✅ |
| 上架后列表恢复 | total=1 | total=1 | ✅ |

---

## 2.1 创建 SKU → 父 SPU 缓存清除

### curl 请求与响应

**创建 SKU**

```
POST /api/product/sku
Header: X-User-Id: 10001
Body:   {"spuId":2081302094884671490,"name":"测试SKU-黑色-L",
         "price":99.00,"originalPrice":129.00,"stock":100,
         "specs":"{\"颜色\":\"黑色\",\"尺寸\":\"L\"}"}
→ 200  {"code":200,"message":"创建成功","data":{"skuId":2081544572120371202}}
```

**验证 SKU 出现在 SPU 详情中**

```
GET /api/product/spu/2081302094884671490
→ 200  skuList: [{ id:2081544572120371202, name:"测试SKU-黑色-L", price:99.0 }]
```

### 中间件验证

| 时刻 | MySQL t_sku | Redis SPU cache |
|------|------|:---:|
| SKU 创建前 | 0 rows for this SPU | cached |
| SKU 创建后 | 1 row, price=99, stock=100 ✅ | **deleted** ✅ |
| SPU GET 后 | 不变 | backfilled（含新 SKU） |

### 服务日志

```
[INFO] [商品] 创建 SKU 成功, skuId=2081544572120371202, spuId=2081302094884671490
```

### 代码路径分析

```java
// SkuService.createSku():
spuMapper.selectById(spuId);         // ① 校验父 SPU 存在
skuMapper.insert(sku);               // ② INSERT t_sku
spuService.evictSpuCache(spuId);     // ③ 清除父 SPU 缓存
```

SKU 创建不主动回填缓存——和 SPU 创建一样走 Lazy Loading。但 SKU 创建会清除父 SPU 的缓存——因为 SPU 详情内联了 SKU 列表，SKU 变化意味着 SPU 详情缓存已过时。

### 工程设计分析

**为什么 SKU 没有独立的缓存层？**

SKU 详情通过两个路径访问：
1. 独立查询：`GET /sku/{id}` — 直接查 DB，无缓存
2. 内联查询：`GET /spu/{spuId}` → SKU 列表内嵌在 SPU 详情缓存中

第二条路径覆盖了大部分 SKU 查询需求（用户看商品详情时自然会看到所有 SKU），独立 SKU 查询（如购物车批量查）走 `batchGetSkuDetails`——用 `selectBatchIds` 一次 SQL 解决，不需要缓存。

**`createSku` 缺少 `@RateLimit`**：对比 `createSpu` 有 `product:create 10/60s`，SKU 创建端点没有限流保护。SKU 创建频率通常比 SPU 高（一个 SPU 可能有多个 SKU 变体），缺少限流在高频批量化创建时有风险。

**`stock` 字段是冗余字段**：源码 Javadoc 明确标注 "实际库存由库存服务管理"。product 模块的 stock 是快照值——下单扣库存时 inventory 模块是权威源，product 的 stock 仅用于商品详情页的展示。

**SKU 创建的 @Transactional 边界**：`createSku` 的 `@Transactional` 覆盖 `spuMapper.selectById` + `skuMapper.insert`，但不覆盖 `spuService.evictSpuCache`（Redis 操作在事务外）。如果 insert 成功但 evict 失败 → DB 已有新 SKU，但 SPU 缓存中 SKU 列表是旧的 → 下次 SPU GET 读到旧列表。逻辑过期（30min）后自愈。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 创建 SKU → 200 | 有 skuId | skuId=2081544572120371202 | ✅ |
| MySQL t_sku 已写入 | 1 row | price=99.00, stock=100 | ✅ |
| SPU 缓存已清除 | deleted | deleted | ✅ |
| SPU 详情含新 SKU | skuCount=1 | skuCount=1 | ✅ |
| SKU 详情可独立访问 | 200, name+price | name+price 正确 | ✅ |

---

## 2.2 SKU 详情 + 批量查询 + SKU 列表

### curl 请求与响应

**SKU 详情**

```
GET /api/product/sku/2081544572120371202
→ 200  {"data":{"id":2081544572120371202,"name":"测试SKU-黑色-L","price":99.0,...}}
```

**批量查询（购物车用）**

```
GET /api/product/sku/batch?skuIds=2081544572120371202
→ 200  {"data":[{"id":2081544572120371202,"name":"测试SKU-黑色-L","price":99.0}]}
```

**按 SPU 查 SKU 列表**

```
GET /api/product/sku/list/2081302094884671490
→ 200  {"data":[...]} // skuCount=1, 只返回上架 SKU
```

### 代码路径分析

```java
// 批量查询 (SkuService.batchGetSkuDetails):
skuMapper.selectBatchIds(skuIds);  // WHERE id IN (...) → 1 次 SQL

// SKU 列表 (SkuService.listSkusBySpuId):
skuMapper.selectList(where spuId AND status=ON_SHELF) → 1 次 SQL
```

批量查询使用 MyBatis-Plus 的 `selectBatchIds`——底层生成 `WHERE id IN (...)`，一次 SQL 替代 N 次循环单查。SKU 列表只返回上架 SKU（`status=1`），天然过滤下架规格。

### 中间件验证

**MySQL**：3 条查询均走 DB（SKU 无缓存层）✅。`selectBatchIds` + `selectList` 均生成标准 SQL。

**Redis**：SKU 没有独立的 Redis Key——验证 SKU 详情不做缓存：

```
myxhs:product:sku:2081544572120371202 → null（不存在任何 SKU 缓存 Key）
```

只有 `myxhs:product:spu:*` 格式的 SPU 缓存 Key。

### 服务日志

```
[ACCESS] GET /api/product/sku/2081544572120371202 → 200
[ACCESS] GET /api/product/sku/batch?skuIds=2081544572120371202 → 200  
[ACCESS] GET /api/product/sku/list/2081302094884671490 → 200
```

所有请求正常 200，无异常日志。SKU 详情、批量查询、列表三个端点均无缓存日志——验证了"SKU 无独立缓存层"的设计。

### 工程设计分析

**批量查询为什么不存在死锁风险？**

counter 模块的 `batchUpsert` 需要排序防死锁——因为 `INSERT ON DUPLICATE KEY UPDATE` 会加行锁，多个事务交叉加锁可能死锁。SKU 批量查询是纯 `SELECT`——不涉及行锁，不需要排序。

**空列表保护**：`batchGetSkuDetails` 有 `if (skuIds == null || skuIds.isEmpty()) return List.of()`——防护了空参数导致的无效 SQL。

**三种查询路径的分工**：

| 路径 | 是否缓存 | 适用场景 | SQL 方式 |
|------|:---:|------|------|
| `GET /sku/{id}` | ❌ | 详情页（罕见，通常走 SPU 内联） | `selectById` |
| `GET /sku/batch` | ❌ | 购物车列表（高频） | `selectBatchIds`（WHERE IN） |
| `GET /sku/list/{spuId}` | ❌（但随 SPU 缓存间接返回） | 商品详情页内联 | `selectList(where spuId)` |

购物车批量查询是 SKU 的唯一高频独立查询——用 `selectBatchIds` 的 `WHERE IN` 效率足够，不需要额外缓存层。

**SKU 列表的 status 过滤**

`listSkusBySpuId` 只返回 `status=ON_SHELF` 的 SKU——和 `listSpus` 过滤上架 SPU 一致。这是商品展示侧的过滤，不是库存侧的——下架 SKU 在购物车/订单中仍以已选中的 SKU ID 引用，不会被过滤影响下单。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| SKU 详情 → 200 | name+price | name+price 正确 | ✅ |
| 批量查询 → 含 SKU | 1 result | batchCount=1 | ✅ |
| SKU 列表 → 按 SPU 过滤 | 1 result | skuCount=1 | ✅ |
| 批量查询 SQL 为 1 次 | WHERE IN | selectBatchIds | ✅ |
| Redis SKU 无独立缓存 | null | null | ✅ |
| 空列表保护 | no null args | `skuIds.isEmpty() → List.of()` | ✅ |

---

## 3.1 分类树查询（一次全查 + 内存构建 + Redis 2h 缓存）

### curl 请求与响应

```
GET /api/product/category/tree
→ 200  {
  "data": [
    { "name":"服饰", "children": [ { "name":"女装", "children": [...] }, ... ] },
    { "name":"电子产品", ... },
    { "name":"家居生活", ... },
    { "name":"食品饮料", ... }
  ]
}
// 4 个一级分类，6 个二级分类，树形嵌套
```

### 中间件验证

| 时刻 | Redis | 
|------|------|
| 首次 GET 前 | null（未缓存） |
| 首次 GET 后 | TTL=7200s (2.0h)，Jackson `@class` 序列化完整树结构 |
| 第二次 GET | 命中 Redis（无 DB 查询日志） |

**缓存结构**：Redis 存储的是 Jackson 序列化的完整 `List<CategoryTreeVO>`——含 `@class` 类型信息，树结构完整嵌入，不依赖 DB。

### 服务日志

```
[INFO] [分类] 缓存未命中, 查询 DB 构建分类树
```

首次查询触发 "查询 DB 构建分类树"。第二次查询无日志（Redis 命中，debug 级不输出）。

### 代码路径分析

```java
// CategoryService.getCategoryTree():
// 1. L2: Redis
List<CategoryTreeVO> cached = redisOperator.get("myxhs:product:category:tree");
if (cached != null) return cached;  // Redis 命中

// 2. L3: MySQL → 内存构建
List<CategoryTreeVO> tree = buildCategoryTree();
//   → categoryMapper.selectList(where status=1, order by sort)
//   → all.stream().collect(Collectors.groupingBy(Category::getParentId))
//   → buildChildren(parentMap, 0L) 递归构建

// 3. 回填 L2
redisOperator.set(CATEGORY_TREE_REDIS_KEY, tree, 2, TimeUnit.HOURS);
return tree;
```

### 工程设计分析

**为什么一次全查而不是逐层查询？**

分类表通常 < 1000 行。逐层查（查根 → 查子 → 查孙）产生 N+1 问题。一次全查后内存分组构建，时间复杂度 O(n)（扫描一遍 + HashMap 分组），远小于 N+1 的 DB 往返。

**为什么 TTL 是 2h 而不是 30min？**

分类是商品系统中变更最慢的数据——新增分类是运营低频操作，按天/月为单位。2h TTL 是"几乎不过期"的保守选择。如果需要立即生效，运维可以手动 DEL Redis Key 强制重建。

**Jackson `@class` 序列化**：`RedisOperator.set()` 使用 `RedisTemplate<String, Object>`——Jackson 默认开启 `DefaultTyping`，序列化时会在 JSON 中嵌入 `@class` 字段和 `java.util.ArrayList` 等容器类型信息。好处是反序列化时无需指定目标类型（`get()` 自动返回正确类型），代价是缓存体积更大（每个节点多 ~100 字节类型信息）。

**分类树没有布隆过滤器**：分类树是单个 Key 的全量数据——不是按 ID 查询的路由，不需要布隆过滤器做前置拦截。如果分类表为空，返回空列表——不是 null，调用方无需额外的 null 判断。

### 期望 vs 实际

| 验证项 | 期望 | 实际 | 结果 |
|--------|------|------|:--:|
| 分类树返回 4 个一级分类 | 4 | 4 | ✅ |
| 二级分类总数 | 6 | 6 | ✅ |
| Redis 缓存 TTL | 7200s (2h) | 7200s | ✅ |
| 首次查走 DB | 缓存未命中日志 | INFO "查询 DB 构建分类树" | ✅ |
| 第二次命中 Redis | 无 DB 查询 | 无新增 "查询 DB" 日志 | ✅ |
| 清除后重建 | TTL 重置 | 7200s | ✅ |

---

_更多用例待补充_

_更多用例待补充_
