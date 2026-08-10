# my-xhs-coupon 优惠券服务模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-coupon/` |
| 端口 | 19010 |
| 服务名 | `my-xhs-coupon`（Nacos） |
| 数据库 | `my_xhs_coupon`（MySQL 13307 Master / 13311 Slave） |
| Java 源文件 | 18 个（含 DTO/Consumer/Job/Validator/Config，含新增 CouponReconcileJob） |
| Lua 脚本 | 2 个（claim_coupon / return_coupon） |
| 启动类 | `CouponApplication.java` |

**职责边界**：管理优惠券模板（创建/上下线）、用户领券（Lua 原子）、用券（责任链校验）、退券（@Transactional + Lua 恢复）、查询（用户券列表/可用券）。被 order 服务和 home(BFF) 通过 Feign 调用。

**核心设计理念**：

```
写链路：claimCoupon → Lua 原子（库存-1, 领取次数+1）→ syncSend MQ → Consumer 落 MySQL
退券：returnCoupon → @Transactional MySQL（恢复状态+回退数量）→ Lua 回退 Redis

查询：模板走 Redis 缓存（Cache-Aside, 30min TTL + 空值缓存防穿透）
      用户券走 MySQL 直接查询

过期：XXL-Job 每小时分批 UPDATE LIMIT 1000
对账：CouponReconcileJob 每天凌晨 2 点 Redis↔MySQL（以 MySQL 为准）
```

---

## 1. 数据模型

### 1.1 数据库表 — `t_coupon_template`（优惠券模板）

```sql
id             BIGINT PRIMARY KEY         -- 雪花 ID（ASSIGN_ID）
name           VARCHAR(128) NOT NULL      -- 券名称
type           TINYINT NOT NULL           -- 1=满减 2=折扣 3=无门槛
discount_value DECIMAL(10,2) NOT NULL     -- 面额/折扣值
min_amount     DECIMAL(10,2) DEFAULT 0    -- 最低消费金额
total_count    INT NOT NULL               -- 发放总量
remain_count   INT NOT NULL               -- 剩余数量
per_user_limit INT NOT NULL               -- 每人限领
valid_start    DATETIME NOT NULL          -- 有效期开始
valid_end      DATETIME NOT NULL          -- 有效期结束
status         TINYINT DEFAULT 1          -- 0=禁用 1=启用
deleted        TINYINT DEFAULT 0          -- 逻辑删除（@TableLogic）
created_at     DATETIME
updated_at     DATETIME
INDEX idx_status (status)
```

### 1.2 数据库表 — `t_user_coupon`（用户优惠券）

```sql
id             BIGINT PRIMARY KEY         -- 雪花 ID
user_id        BIGINT NOT NULL            -- 用户 ID
coupon_id      BIGINT NOT NULL            -- 券模板 ID
claim_no       VARCHAR(64) NOT NULL       -- 幂等键（MQ msgId）
status         TINYINT DEFAULT 0          -- 0=未使用 1=已使用 2=已过期
used_order_id  BIGINT                     -- 使用的订单 ID
received_at    DATETIME                   -- 领取时间
used_at        DATETIME                   -- 使用时间
UNIQUE uk_claim_no (claim_no)
INDEX idx_user_id (user_id)
INDEX idx_coupon_id (coupon_id)
INDEX idx_user_coupon (user_id, coupon_id)
```

### 1.3 Redis Key 设计

```
# 券库存（hash tag {templateId} 保证与 claimed Key 同 slot）
coupon:{templateId}:stock                 → String, 值=剩余数量

# 用户领取次数
coupon:{templateId}:claimed:{userId}      → String, 值=已领次数

# 模板缓存（Cache-Aside, 30min TTL + 空值防穿透）
coupon:template:{templateId}              → JSON String 或 "NULL"
```

### 1.4 Entity

**CouponTemplate**（14 字段）：id, name, type(1/2/3), discountValue, minAmount, totalCount, remainCount, perUserLimit, validStart, validEnd, status, deleted(@TableLogic), createdAt, updatedAt

**UserCoupon**（8 字段）：id, userId, couponId, claimNo（唯一索引幂等键）, status(0/1/2), usedOrderId, receivedAt, usedAt

---

## 2. 接口清单（8 个 REST 端点）

| 方法 | 路径 | 说明 | 可见性 | 限流 |
|:----:|------|------|:------:|------|
| POST | `/api/coupon/template` | 创建券模板 | 管理后台 | — |
| PUT | `/api/coupon/template/{id}/status` | 上下线模板 | 管理后台 | — |
| GET | `/api/coupon/template/{id}` | 模板详情 | 公开 | — |
| POST | `/api/coupon/claim` | 领券 | 用户（需 X-User-Id） | @RateLimit 5/60s/用户 |
| GET | `/api/coupon/user/list` | 我的优惠券列表 | 用户 | — |
| GET | `/api/coupon/user/available` | 可用优惠券 | 用户（下单时展示） | — |
| POST | `/api/coupon/use` | 使用优惠券 | 内部（order Feign） | — |
| POST | `/api/coupon/return` | 退回优惠券 | 内部（order Feign） | — |

### 2.1 POST /api/coupon/template — 创建模板

```json
// Request
{"name":"满100减20","type":1,"discountValue":20,"minAmount":100,
 "totalCount":1000,"perUserLimit":3,"validStart":"2026-08-01 00:00:00",
 "validEnd":"2026-08-31 23:59:59"}

// Response: CouponTemplateVO
```

内部逻辑：参数校验→INSERT MySQL→SETNX 初始化 Redis 库存→写模板缓存。

### 2.2 POST /api/coupon/claim — 领券

```json
// Headers: X-User-Id: 10001
// Request: {"templateId": 1}
```

**内部逻辑**（`CouponService.claimCoupon`）：

1. 从 Redis 缓存获取模板（Cache-Aside, 30min TTL, 空值防穿透）
2. Lua 原子领券（检查库存→检查限领→DECR stock→INCR claimed）
3. syncSend MQ `COUPON_CLAIM_TOPIC`（失败则立即回滚 Redis）

返回值：1 成功 / -1 已领完 / -2 已达限领 / -3 未初始化（-3 时尝试从 MySQL 初始化并重试一次）

### 2.3 POST /api/coupon/use — 用券

```json
// Headers: X-User-Id: 10001
// Request: {"userCouponId": 123, "orderId": 456, "orderAmount": 199}
```

**内部逻辑**：查询 UserCoupon→查模板（缓存）→责任链校验（AmountValidator→ExpireValidator→StatusValidator）→乐观锁 UPDATE status=1。

### 2.4 POST /api/coupon/return — 退券

```json
// Headers: X-User-Id: 10001
// Request: {"userCouponId": 123, "orderId": 456}
```

**内部逻辑**（`@Transactional`）：MySQL 恢复状态（乐观锁 WHERE status=1）→原子回退 remain_count（SQL increment）→ Redis 回退库存（Lua INCR stock + DECR claimed）。Redis 操作在 SQL 事务内执行但不参与事务回滚。

### 2.5-2.8 查询接口

GET `/api/coupon/template/{id}` — Redis 缓存→MySQL 兜底
GET `/api/coupon/user/list` — MySQL 直接查（按 status 过滤）
GET `/api/coupon/user/available` — MySQL 查 status=0 → 内存过滤已过期

---

## 3. Mapper — 4 个自定义 SQL

| Mapper | 方法 | SQL | 乐观锁条件 |
|------|------|-----|------|
| UserCouponMapper | `markUsed(id, orderId)` | `UPDATE SET status=1, used_order_id=?, used_at=NOW()` | `WHERE id=? AND status=0` |
| UserCouponMapper | `returnCoupon(id, orderId)` | `UPDATE SET status=0, used_order_id=NULL, used_at=NULL` | `WHERE id=? AND status=1 AND used_order_id=?` |
| UserCouponMapper | `batchExpire(limit)` | `UPDATE SET status=2 WHERE status=0` | `JOIN t_coupon_template WHERE valid_end < NOW()` |
| CouponTemplateMapper | `decrementRemainCount(id)` | `UPDATE SET remain_count = remain_count - 1` | `WHERE id=? AND remain_count > 0` |
| CouponTemplateMapper | `incrementRemainCount(id)` | `UPDATE SET remain_count = remain_count + 1` | `WHERE id=?` |

---

## 4. 核心流程

### 4.1 领券完整链路

```
用户 POST /api/coupon/claim
  │
  ├── @RateLimit(5/60s, perUser=true)
  │
  ├── getTemplateWithCache(templateId)
  │     └── Redis GET coupon:template:{id} → miss → MySQL SELECT → SET cache (30min TTL)
  │
  ├── 参数校验：status!=1 下线 / validEnd 已过
  │
  ├── claim_coupon.lua 原子执行
  │     KEYS=[coupon:{id}:stock, coupon:{id}:claimed:{userId}]
  │     ARGV=[perUserLimit]
  │
  │     Lua 内部: GET stock → >0? → GET claimed → <perUserLimit? → DECR stock → INCR claimed
  │
  ├── switch(result):
  │     -3: initStockFromDb(template) → retry claim_coupon.lua
  │     -1: BizException(COUPON_SOLD_OUT)
  │     -2: BizException(COUPON_ALREADY_RECEIVED)
  │      1: syncSend COUPON_CLAIM_TOPIC
  │           │
  │           ├── SendStatus.SEND_OK → 返回 200 给用户
  │           └── 失败 → rollbackRedisStock(INCR stock, DECR claimed) → BizException
  │
  └── Consumer: CouponClaimConsumer
        └── msgId 幂等 → INSERT t_user_coupon (claim_no=msgId, uk 防重)
            → decrementRemainCount (SQL UPDATE remain_count-1)
```

### 4.2 退券完整链路

```
order (取消/退款) → Feign → POST /api/coupon/return
  │
  ├── @Transactional 方法内
  │     ├── SELECT t_user_coupon WHERE id=?
  │     ├── UPDATE t_user_coupon SET status=0 (乐观锁: WHERE status=1 AND used_order_id=?)
  │     └── UPDATE t_coupon_template SET remain_count=remain_count+1 (SQL 原子)
  │
  └── 同方法内（不参与 SQL 事务回滚）
        └── return_coupon.lua
              KEYS=[coupon:{id}:stock, coupon:{id}:claimed:{userId}]
              INCR stock, DECR claimed
```

**为什么 Redis 不在 SQL 事务边界内？** returnCoupon 的 `@Transactional` 保证 MySQL 的原子性（恢复状态 + 回退数量）。Redis 的 Lua 操作在同一方法体内执行但 Spring 不管理 Redis 事务——如果 Redis 失败则 MySQL 已提交，如果 MySQL 回滚则 Redis 已操作。偏差由 `CouponReconcileJob` 凌晨修复。

### 4.3 claim_coupon.lua 完整逻辑

```lua
KEYS[1] = coupon:{templateId}:stock
KEYS[2] = coupon:{templateId}:claimed:{userId}
ARGV[1] = perUserLimit

1. GET stock → nil → return -3（未初始化）
2. stock <= 0 → return -1（已领完）
3. GET claimed → >= perUserLimit → return -2（已达限领）
4. DECR stock
5. INCR claimed
6. return 1
```

**为什么只用 2 个 Key？** 与 inventory 的 prededuct.lua（2+N 个 Key）不同，领券不需要分桶——每个券模板的库存是独立 Key，不存在单 Key 热点问题（不同券模板的领券请求天然分散到不同 Key）。也不需要桶间遍历——库存不足直接返回 -1。

### 4.4 责任链用券校验

```java
List<CouponValidator> validators;  // Spring 自动注入，@Order 排序

// 调用时
for (CouponValidator validator : validators) {
    validator.validate(template, orderAmount);
}
```

| Validator | @Order | 校验逻辑 |
|------|:--:|------|
| AmountValidator | 1 | 满减券: orderAmount >= minAmount；无门槛券: 不校验 |
| ExpireValidator | 2 | LocalDateTime.now() 在 validStart~validEnd 之间 |
| StatusValidator | 3 | template.getStatus() == 1 |

### 4.5 券过期定时任务 + 对账任务

`@XxlJob("couponExpireJob")`，每小时执行。

```
while (true):
    UPDATE t_user_coupon u
    JOIN t_coupon_template t ON u.coupon_id = t.id
    SET u.status = 2
    WHERE u.status = 0 AND t.valid_end < NOW()
    LIMIT 1000
    ↓
    affected < 1000? → 退出循环
    sleep(100ms) → 继续下一批
```

**库存对账**（`@XxlJob("couponReconcileJob")`，每天凌晨 2 点）：

```
for each 启用+未过期+未删除的 template:
    GET Redis coupon:{templateId}:stock
    vs MySQL t_coupon_template.remain_count
    → 不一致（或 Redis nil）→ SET Redis = MySQL remain_count
```

---

## 5. 中间件交互

### 5.1 Redis — 2 个 Lua 脚本

| 脚本 | 路径 | 操作 Key 数 | 返回值 |
|------|------|:--:|------|
| `claim_coupon.lua` | `lua/claim_coupon.lua` | 2 (stock + claimed) | 1/-1/-2/-3 |
| `return_coupon.lua` | `lua/return_coupon.lua` | 2 (stock + claimed) | 1/0 |

### 5.2 RocketMQ

| 角色 | Topic | Group | 说明 |
|------|-------|------|------|
| Producer | COUPON_CLAIM_TOPIC | coupon-producer-group | syncSend, 失败回滚 Redis |
| Consumer | COUPON_CLAIM_TOPIC | coupon-claim-consumer-group | msgId 幂等 + uk_claim_no 防重 |

### 5.3 XXL-Job

| Job | Handler | 调度 | 说明 |
|-----|---------|------|------|
| `CouponExpireJob` | `@XxlJob("couponExpireJob")` | 每小时 | 分批标记过期券（LIMIT 1000） |
| `CouponReconcileJob` | `@XxlJob("couponReconcileJob")` | 每天凌晨 2 点 | Redis stock ↔ MySQL remain_count 对账，以 MySQL 为准修复 |

---

## 6. 配置

```yaml
server.port: 19010
spring.application.name: my-xhs-coupon
# MySQL: Master 13307 / Slave 13311, my_xhs_coupon
# Redis: 16379(Sentinel), 16380(Cache), 16381(Business)
# Nacos: 21.130.247.89:18848, namespace=my-xhs
# RocketMQ: 21.130.247.89:9876
# XXL-Job Admin: http://21.130.247.89:18080, executor port 9995
```

---

## 7. 异常处理

| 场景 | 处理方式 | 最终一致性 |
|------|---------|------|
| 券已领完 | BizException(COUPON_SOLD_OUT) | — |
| 已达限领 | BizException(COUPON_ALREADY_RECEIVED) | — |
| Redis 未初始化 | 从 MySQL 初始化并重试一次 | — |
| MQ 发送失败 | 回滚 Redis（INCR stock, DECR claimed） | 回滚失败则凌晨对账修复 |
| MQ 消费失败（DupKey） | uk_claim_no 拦截，不报错 | — |
| 退券 Redis 失败 | MySQL 已提交，Redis 未更新 | 凌晨对账以 MySQL 为准修复 |
| 券过期标记 | 分批 UPDATE LIMIT 1000，不锁表 | — |

---

## 8. 安全

| 措施 | 实现 |
|------|------|
| 领券限流 | `@RateLimit(5/60s, perUser=true)` 按用户维度限流 |
| SETNX 幂等 | 模板创建时 `setIfAbsent` 防并发重复初始化 |
| 乐观锁 | 用券 `WHERE status=0` / 退券 `WHERE status=1` |
| 唯一索引防重 | `uk_claim_no(claim_no)` 防止领券重复写入 |
| 空值缓存防穿透 | `getTemplateWithCache` 对不存在的模板缓存 "NULL" 60s |

---

## 9. 跨模块对比

### 9.1 vs inventory 的 Lua 脚本

| 维度 | coupon | inventory |
|------|---------|-----------|
| 脚本数量 | 2 个 | 3 个 |
| Key 操作 | stock + claimed（2 个） | total + prededuct + N buckets（2+N） |
| 热点处理 | 无需分桶（不同券模板天然分散） | 分桶（2→8）+ ZSet 滑动窗口 |
| 幂等 | Lua 不做幂等（由 MQ msgId + DB uk 兜底） | Lua HGET 检查 prededuct Hash |
| MQ 发送 | syncSend + 失败回滚 Redis | syncSend + 失败回滚 Redis（同模式） |

### 9.2 vs inventory 的退券/释放对比

| 维度 | coupon returnCoupon | inventory releaseStock |
|------|------|------|
| 事务 | `@Transactional`（MySQL 原子）→ Lua（Redis） | 非事务（Lua → MQ fire-and-forget） |
| 回退目标 | stock+1, claimed-1 | bucket+total INCRBY |
| 一致性 | MySQL 是权威，Redis 可能落后 | Redis 是权威，MySQL 可能落后 |

---

## 10. 已知问题

### 10.1 退券时 Redis 与 MySQL 不在同一事务边界（已通过 CouponReconcileJob 缓解）

**源码位置**：`CouponService.java:307-315`

```java
@Transactional(rollbackFor = Exception.class)
public void returnCoupon(Long userId, ReturnCouponRequest request) {
    // ... MySQL: UPDATE t_user_coupon + UPDATE t_coupon_template
    // Redis Lua 在 @Transactional 方法内执行，但不在 SQL 事务边界内
    stringRedisTemplate.execute(returnCouponScript, List.of(stockKey, claimedKey));
}
```

Redis Lua 操作在 `@Transactional` 方法体内，先于 Spring 提交事务执行。如果 Redis 成功但 MySQL 提交失败——MySQL 会回滚但 Redis 已 INCR/DECR 不会回滚。反之亦然。设计选择：MySQL 是权威数据源。与 inventory 的 L3 对账类似，coupon 通过 `CouponReconcileJob`（每天凌晨 2 点）将 Redis stock 与 MySQL remain_count 对齐。

### 10.2 领券重试（-3 分支）中 initStockFromDb 的冗余

**源码位置**：`CouponService.java:208-227`

当 Lua 返回 -3（未初始化）时，`initStockFromDb(template)` 从 MySQL 读 `remain_count` 后用 `setIfAbsent` 写 Redis。`setIfAbsent` 是原子操作——如果另一并发请求已经初始化了 Redis 库存，本次 `setIfAbsent` 返回 false（不覆盖）——数据不会被污染，但 MySQL 查询是冗余的。

**实际行为**：setIfAbsent 失败后调用方仍会重试 claim_coupon.lua（因为此时 Redis 已有值，第二次 Lua 直接成功）。整个流程正确，只是多了一次无效的 MySQL SELECT。

---

## 11. 总结

| 维度 | 描述 |
|------|------|
| 扣减模式 | Lua 原子（DECR stock + INCR claimed）→ MQ 异步写 MySQL |
| 并发控制 | Redis Lua 原子 + MySQL 乐观锁 |
| 查询模式 | 模板：Cache-Aside（30min TTL + 空值防穿透）；用户券：MySQL 直查 |
| 跨服务交互 | 被 order/home Feign 调用，自身无 @FeignClient |
| 定时任务 | XXL-Job 每小时分批过期（LIMIT 1000）+ 每天凌晨 2 点对账 |
| 源码行数 | CouponService 400+ 行（核心） |
