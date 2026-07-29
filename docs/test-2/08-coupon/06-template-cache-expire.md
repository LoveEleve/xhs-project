# 06 — 模板缓存与券过期

> **前置阅读**：[架构文档 §1.3 (Redis Key)](01-coupon-module.md) · §4.5 (过期+对账) · [03-claim_coupon.lua](03-claim-coupon-lua.md)
> **测试验证**：[测试 5 (模板查询)](02-coupon-test-record.md) — Cache-Aside 命中

## 模板缓存：Cache-Aside + 空值防穿透

源码：`CouponService.getTemplateWithCache()`

```java
private CouponTemplate getTemplateWithCache(Long templateId) {
    String cacheKey = TEMPLATE_KEY_PREFIX + templateId;

    // 1. Redis 缓存命中
    String cached = stringRedisTemplate.opsForValue().get(cacheKey);
    if (cached != null) {
        if ("NULL".equals(cached)) {
            return null;  // 缓存空值，防穿透
        }
        return objectMapper.readValue(cached, CouponTemplate.class);
    }

    // 2. 缓存未命中 → MySQL
    CouponTemplate template = templateMapper.selectById(templateId);

    // 3. 回写缓存
    if (template != null) {
        cacheTemplate(template);  // 30min TTL
    } else {
        // 空值缓存 60 秒，防穿透
        stringRedisTemplate.opsForValue().set(cacheKey, "NULL",
            Duration.ofSeconds(60));
    }

    return template;
}
```

### 为什么领券需要模板缓存？

每一次 `POST /api/coupon/claim` 都需要读取模板信息（类型、面额、有效期、限领数）。如果每次都查 MySQL——高并发领券（如秒杀限量券）时 MySQL 会成为瓶颈。模板信息是低频变更的（管理员操作），天然适合缓存。

`getTemplateWithCache` 的调用频率 = 领券的 QPS。百万级领券并发下，如果每次都查 MySQL，即使有连接池也会被打爆。

### 为什么存整个 CouponTemplate JSON 而非分字段？

```java
String json = objectMapper.writeValueAsString(template);
stringRedisTemplate.opsForValue().set(cacheKey, json, Duration.ofSeconds(1800));
```

分字段存储（如 Hash `HSET coupon:template:{id} name xxx type 1 ...`）需要 N 次 HSET，读取时需要 N 次 HGET 或一次 HGETALL。JSON 序列化-反序列化虽然比 Hash 慢（ObjectMapper 开销 vs Redis Hash 直接读写），但：

1. **模板字段固定且少**（14 个字段），JSON 序列化开销可控
2. **一次 SET/GET** 完成，比多次 HSET/HGET 更简单
3. **30 分钟 TTL** 意味着反序列化频率远低于写入频率
4. **Java 对象直接映射**——不需要手动组装字段

### 空值缓存的 60 秒 TTL

```java
stringRedisTemplate.opsForValue().set(cacheKey, "NULL", Duration.ofSeconds(60));
```

**为什么要有空值缓存？** 防止缓存穿透——恶意请求故意查询不存在的 templateId，绕过缓存直接打到 MySQL。

| 场景 | 没有空值缓存 | 有空值缓存 |
|------|------|------|
| 查询 templateId=999999（不存在） | 每次查 MySQL | 第一次查 MySQL，后续 60s 从 Redis 返回 null |
| 攻击者循环 1000 个不存在的 ID | 1000 次 MySQL SELECT | 1 次 MySQL SELECT，999 次 Redis GET |

**为什么 60 秒而不是更久？** 如果管理员创建了新的模板（templateId 变成存在），60 秒内查询仍返回 null——这个窗口是可以接受的。createTemplate 后的 `cacheTemplate` 会覆盖之前的 "NULL" 缓存。

### 缓存的失效时机

缓存不是被动过期的——有两个主动失效点：

| 操作 | 失效方式 | 原因 |
|------|------|------|
| 创建模板 | `cacheTemplate` 覆盖 | 新模板需要立即进入缓存 |
| 修改状态 | `evictTemplateCache` 删除 | 上下线后的模板需要在下次查询时重新加载 |
| TTL 过期 | 30min 自动删除 | 保证模板变更后最长 30min 内生效 |

`evictTemplateCache` 的实现：

```java
private void evictTemplateCache(Long templateId) {
    stringRedisTemplate.delete(TEMPLATE_KEY_PREFIX + templateId);
}
```

注意：只 DELETE 了缓存 Key，不操作 stock Key（`coupon:{templateId}:stock`）。上下线不改变库存——上线时 Redis stock 可能不存在（没初始化过），下线时 Redis stock 应该保留（券还可以被使用）。

---

## CouponExpireJob：分批标记过期的工程设计

源码：`CouponExpireJob.java`

```java
@XxlJob("couponExpireJob")
public void expireCoupons() {
    int totalAffected = 0;
    while (true) {
        int affected = userCouponMapper.batchExpire(BATCH_SIZE);
        totalAffected += affected;
        if (affected < BATCH_SIZE) break;
        Thread.sleep(100);  // 每批间隔 100ms
    }
}
```

SQL：

```sql
UPDATE t_user_coupon uc
INNER JOIN t_coupon_template ct ON uc.coupon_id = ct.id
SET uc.status = 2
WHERE uc.status = 0 AND ct.valid_end < NOW()
LIMIT #{batchSize}
```

### 为什么分批（LIMIT 1000）？

一次性 UPDATE 所有过期券会导致：

1. **长事务**：100 万行 UPDATE 可能持续数秒
2. **锁表**：InnoDB 行锁升级为表锁的风险（锁升级阈值）
3. **阻塞其他操作**：用券、退券的 UPDATE 被阻塞
4. **主从延迟**：大批量 UPDATE 的 binlog 导致从库追不上

分批（每次 1000 条，间隔 100ms）将一个大事务拆成 N 个小事务，每次只持有短暂的行锁。100ms 的间隔给其他事务让出锁资源。

### 为什么每小时执行一次？

券过期不是"实时"需求——用户不会因为券在过期后 59 分钟才被标记为"已过期"而受影响。`ExpireValidator` 在执行用券时提供了实时校验——即使 status 还没更新，过期的券在责任链校验时也会被拒绝。

`CouponExpireJob` 的作用是**状态的准确展示**——用户查看"我的优惠券"列表时，过期券应该被标记为 `status=2` 而非保持 `status=0`。一小时一次的频率平衡了准确性和性能。

### 为什么 JOIN 查询而不是先查模板再 UPDATE？

```sql
-- ✅ 当前实现：一次 JOIN 查询
UPDATE uc JOIN ct ON uc.coupon_id = ct.id
SET uc.status = 2
WHERE uc.status = 0 AND ct.valid_end < NOW()
LIMIT 1000
```

vs.

```sql
-- ❌ 替代方案：两次查询
SELECT id FROM t_coupon_template WHERE valid_end < NOW()
UPDATE t_user_coupon SET status=2 WHERE coupon_id IN (...) AND status=0
```

JOIN 一次往返完成。替代方案需要两次 MySQL 往返 + 应用层组装 IN 列表——IN 列表过长时性能急剧下降（MySQL 优化器对大型 IN 列表不友好）。

---

## 面试 Q&A

### Q1：getTemplateWithCache 的回写是先写 Redis 还是先查 MySQL？如果并发两个请求同时发现缓存失效怎么办？

**答案**：读请求发现缓存 miss → 查 MySQL → 回写 Redis。多个并发请求同时 miss 缓存，**都会**查 MySQL 并回写 Redis。这不是 bug——是 Cache-Aside 的设计特征。

两个请求读同一 templateId 返回相同数据，写 Redis 的 SET 是覆盖写——先写的被后写的覆盖，但值相同。唯一的代价是多一次 MySQL 查询——但"多一次"在高并发下可能放大。可以用分布式锁（Redisson tryLock）在缓存 miss 时串行化 MySQL 查询，但 coupon 模块选择了更简单的实现：容忍并发 miss 的多余查询。

**追问**：如果模板被删除了（逻辑删除 deleted=1），缓存里还有 JSON，下次查询会返回什么？

→ `getTemplateWithCache` 只调用了 `templateMapper.selectById(templateId)`——MyBatis-Plus 的 `@TableLogic` 会自动追加 `AND deleted=0`。所以 deleted=1 的模板查不到 → 返回 null → 设置 "NULL" 空值缓存。下次查询 60s 内直接返回 null。空值缓存不会永久存在——60s 后过期，再次查询仍返回 null。

### Q2：空值缓存的 TTL 是 60 秒——如果管理员在 60 秒内创建了同 ID 的模板，会出现问题吗？

**答案**：不会。`createTemplate` 方法在 INSERT 成功后执行 `cacheTemplate`——`SET coupon:template:{id} {json}`。这个 SET 会覆盖之前的 "NULL" 空值缓存，新模板立即生效。ID 是雪花算法生成的，不存在 ID 复用。

**追问**：如果是 UPDATE 一个存在的模板（如修改 minAmount），缓存怎么更新？

→ `updateTemplateStatus` 只修改 status，修改后执行 `evictTemplateCache` 删除缓存。对于 UPDATE 其他字段（如 minAmount），当前代码没有对应的 update API。如果需要，应该同样调用 `evictTemplateCache` 使缓存失效，下次查询重新加载。

### Q3：CouponExpireJob 的 LIMIT 1000——如果模板有 10 万张过期券，分批更新需要多久？

**答案**：10 万 ÷ 1000 = 100 批。每批 ~10ms（UPDATE）+ 100ms（sleep）= 110ms。总耗时 ≈ 100 × 110ms = 11 秒。

**追问**：100ms 的 sleep 是固定的——如果实际 UPDATE 很快（1ms），sleep 占了 99% 的时间，太浪费了。为什么不动态调整？

→ 延迟不是问题——这个 Job 每小时跑一次，11 秒 vs 1 秒的差距对"每小时"来说没有意义。100ms 固定间隔比动态调整更简单、更可预测。如果需要更快（如从每小时改为每 10 分钟），可以减少 sleep 到 10ms 或去掉 sleep。

---

## 发散：Cache-Aside vs Read-Through vs Write-Through

| 模式 | coupon 的使用 | 适用场景 | 为什么不用其他 |
|------|------|------|------|
| Cache-Aside | `getTemplateWithCache` | 读多写少，允许短暂不一致 | 模板信息是读多写少，管理员操作 | 
| Read-Through | — | 缓存层全权管理数据加载 | 需要额外的缓存抽象层（如 Caffeine），增加复杂度 |
| Write-Through | — | 写入时同步更新缓存 | 模板创建时已经在 `cacheTemplate` 做了，但不是框架自动的 |
| Write-Behind | — | 异步批量写缓存 | 模板创建后立即需要缓存（领券需要），不能异步 |

coupon 实现的是**手动 Cache-Aside**：读的时候自己查 MySQL + 回写 Redis，写的时候自己 `evictTemplateCache`。没有用 Spring Cache 抽象（`@Cacheable`/`@CacheEvict`），因为需要精确控制 TTL（30min/60s 两种）+ 空值缓存逻辑（把 "NULL" 作为有效缓存值，非缓存 miss）。

---

## 生产故障实验

### 实验：验证空值缓存防穿透

```bash
# 1. 查询一个不存在的模板
curl -s "http://localhost:19010/api/coupon/template/999999"
# → {"code":30012,"message":"优惠券不存在"}

# 2. 检查 Redis 空值缓存
python3 -c "
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)
print(r.get('coupon:template:999999'))
# → NULL
print(r.ttl('coupon:template:999999'))
# → ~60 (60 秒 TTL)
"

# 3. 60 秒内再次查询同一 ID——直接从 Redis 返回 null，不查 MySQL
curl -s "http://localhost:19010/api/coupon/template/999999"
# 仍然 {"code":30012}
```

### 实验：验证状态变更后缓存失效

```bash
# 1. 查询模板（缓存写入）
curl -s "http://localhost:19010/api/coupon/template/2082035277620097026"

# 2. 下线模板
curl -s -X PUT ".../status?status=0"

# 3. 检查缓存是否已删除
python3 -c "import redis; r=redis.Redis(...); print(r.get('coupon:template:2082035277620097026'))"
# → None（已被 evictTemplateCache 删除）

# 4. 再次查询——走 MySQL 加载最新 status=0
curl -s "..."
# → {"status":0}  # 正确反映下线状态
```
