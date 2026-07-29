# 缓存一致性：写后删除策略

> 源码：`SpuService.updateSpu()` + `evictSpuCache()` + `SkuService.createSku()`
> 验证：`02-product-test.md` §1.2

---

## 1. Cache Aside 模式

product 模块的缓存一致性采用 Cache Aside 模式：

```
读路径（getSpuDetail）：
  1. 查缓存 → 命中 → 返回
  2. 查 DB → 回填缓存 → 返回

写路径（updateSpu）：
  1. UPDATE t_spu
  2. redisOperator.delete(redisKey)
```

**核心原则**：写操作只删除缓存，不做更新。下次读请求自动触发回填——拿到的一定是最新 DB 值。

---

## 2. 为什么"先 DB 后删"而不是"先删后 DB"？

```
先删后 DB（有更大不一致窗口）:
  ① DEL Redis
  ② UPDATE t_spu + @Transactional COMMIT

  窗口 (①, ②): 并发 GET 看到 Redis miss → 查 DB → 读到旧值（UPDATE 未提交）→ 回填旧值

先 DB 后删（当前方案）:
  ① UPDATE t_spu（SQL 发送，未 COMMIT）
  ② DEL Redis（在方法内，COMMIT 前执行）
  ③ @Transactional COMMIT（方法返回时）

  关键问题：DELETE 发生在 COMMIT 之前！
  窗口 (②, ③): 并发 GET → Redis miss → 查 DB → 可能读到旧值（取决于事务隔离级别）
```

**时序修正**：`spuMapper.updateById(spu)` 在事务内执行 SQL，但 COMMIT 发生在方法返回时。`evictSpuCache(spuId)` 在 COMMIT 之前被调用。DELETE 时 DB 的更新尚未提交——另一个读事务可能看到旧值。但因为 MySQL 默认隔离级别 `READ_COMMITTED` 下 UPDATE 行锁会阻塞其他读，且 UPDATE 和 DELETE 之间的时间极短（< 1ms），这个窗口的实际风险极低。

**量化分析**：

| 方案 | 不一致窗口 | 不一致时表现 |
|------|:---:|------|
| 先删后 DB | DEL 到 COMMIT（可能数十 ms） | 缓存回填了旧值——DB 更新后仍读旧缓存 |
| 先 DB 后删 | UPDATE SQL 到 COMMIT（< 1ms，行锁保护） | Redis 命中返回旧值（窗口极短）；后续自动修复 |

"先 DB 后删"的窗口是两次 Redis 操作之间的间隔——实际只有一次 `redisOperator.delete()` 的执行时间（< 1ms）。而"先删后 DB"覆盖了整个 `spuMapper.updateById` + 事务提交流程。

---

## 3. 为什么删除缓存而不是更新缓存？

```java
// ❌ 更新缓存 — 并发不安全
线程A: UPDATE DB price=100   线程B: UPDATE DB price=200
线程A: SET cache price=100   线程B: SET cache price=200

// 执行顺序: A UPDATE, B UPDATE, B SET, A SET
// 结果: DB=200, cache=100 ← 旧值覆盖了新值

// ✅ 删除缓存 — 并发安全
线程A: UPDATE DB, DEL cache  线程B: UPDATE DB, DEL cache

// 执行顺序任意，最终 cache 都被删除
// 下次读一定走 DB → 拿到最新值
```

**根本原因**：缓存更新是不可交换操作（A SET then B SET ≠ B SET then A SET），但缓存删除是可交换操作（A DEL then B DEL = B DEL then A DEL）。在多线程并发下，可交换操作天然消除乱序风险。

---

## 4. SKU 创建对父 SPU 缓存的级联清除

```java
// SkuService.createSku():
skuMapper.insert(sku);                         // ① INSERT t_sku
spuService.evictSpuCache(request.getSpuId());  // ② 清除父 SPU 缓存
```

SKU 创建不仅清除自己的缓存（SKU 本身没有独立缓存），还清除父 SPU 的缓存——因为 SPU 详情内联了 SKU 列表，新增 SKU 意味着旧缓存中的 `skuList` 已过时。

**为什么不主动回填 SPU 缓存？** 和创建 SPU 一样走 Lazy Loading——写操作不应承担缓存回填的职责，这是读路径的事。如果写操作回填缓存，需要额外执行 `loadSpuDetailFromDb`（SPU + SKU + Category 三表 JOIN），写入延迟从 < 5ms 变为 ~10ms。

---

## 5. Redis 删除失败时的恢复

```
场景：DB UPDATE 成功 → redisOperator.delete 超时/异常

T=0:    DB name="新名称", Redis cache name="旧名称"
T=0:    用户 GET → Redis 命中 → 返回旧名称
T=30min: RedisCacheData.logicExpire 到期
T=30min: 用户 GET → isExpired()=true → asyncRefreshCache → 查 DB（新名称）→ SET 新值
→ 不一致最长持续 30min，逻辑过期后自动自愈
```

**为什么不需要重试 DELETE？** `redisOperator.delete()` 失败通常由瞬态网络问题引起（概率 < 0.01%）。如果加重试逻辑，写操作的延迟会增加（重试几次 Redis RTT），而 30min 的逻辑过期已经提供了自动修复。对于商品这种非金融级数据，不重试的性价比更高。

**与 counter 模块 DELETE 策略的对比**：counter 的 `evictSpuCache` 也在 Redis 事务外——`@Transactional` 不覆盖 Redis 操作。两个模块都接受 DELETE 失败的小概率风险，依靠各自的一致性兜底机制（product: 逻辑过期 / counter: 对账修复）。

---

## 6. 发散：四种缓存一致性策略

| 策略 | 读写路径 | 一致性 | 复杂度 |
|------|------|:---:|:---:|
| **Cache Aside**（当前） | 读：查缓存→DB→回填；写：更新 DB→删缓存 | 最终一致 | 低 |
| **Read/Write Through** | 缓存层透明处理 DB 交互 | 最终一致（读透：DB 更新后缓存可能滞后） | 高 |
| **Write Behind** | 异步批量写 DB | 最终一致 | 中 |
| **Refresh Ahead** | 过期前提前刷新 | 近似强一致（提前刷新消除窗口） | 高 |

**product 选 Cache Aside**：读多写少、缓存失效逻辑简单（一条 DEL）、允许分钟级延迟、不需要缓存层感知 DB schema。

**counter 选 Write Behind**：counter 的 Buffer 是典型的 Write Behind——写入缓冲后异步批量刷 DB。和 product 的 Cache Aside 形成了一个完整的一致性策略谱：product 管"缓存和 DB 的同步"，counter 管"缓存作为数据源和 DB 的同步"。

---

## 7. 发散：分布式缓存 vs 本地缓存取舍

product 只使用 Redis 分布式缓存——没有 Caffeine 本地缓存。这是有意取舍：

```
多实例 Caffeine 的失效一致性：

实例 A: Caffeine 缓存 SPU=100
实例 B: Caffeine 缓存 SPU=100

运营在实例 C 上编辑 SPU → price=200:
  1. UPDATE DB ✅
  2. DEL Redis ✅（所有实例共享）
  3. 需要通知 A、B 删除各自的 Caffeine
     → MQ 广播
     → A 收到广播 ✅
     → B 没收到（网络丢包/消费失败）❌
     → 实例 B 的 Caffeine 永远是 price=100
```

单层 Redis 避免了多层缓存之间的一致性问题。`~1ms` 的 Redis RTT 在商品详情场景中完全可接受。

---

## 关联文档

- `01-product-module.md` — §6 缓存一致性
- `02-product-test.md` — §1.2 缓存清除验证
- `04-logical-expire.md` — 逻辑过期（缓存删除失败的兜底）
