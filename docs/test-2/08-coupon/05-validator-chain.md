# 05 — 责任链用券校验深度分析

> **前置阅读**：[架构文档 §4.4 (责任链)](01-coupon-module.md) · §2.3 (use) · §2.4 (return) · [04-MQ+幂等+对账](04-mq-idempotent-reconcile.md)
> **测试验证**：[测试 3 (用券)](02-coupon-test-record.md) — 责任链校验通过 + 乐观锁标记
> [测试 4 (退券)](02-coupon-test-record.md) — @Transactional 恢复 + Redis 回退

## 责任链模式：为什么不用 if-else？

源码：`CouponService.useCoupon()`

```java
public void useCoupon(Long userId, UseCouponRequest request) {
    // 1. 查询 UserCoupon，校验所有权和状态
    // 2. 查询模板（走缓存）
    // 3. 责任链校验
    for (CouponValidator validator : validators) {
        validator.validate(template, request.getOrderAmount());
    }
    // 4. 乐观锁标记已使用
    userCouponMapper.markUsed(id, orderId);
}
```

`validators` 由 Spring 自动注入，`List<CouponValidator>` 按 `@Order` 排序：

```java
@Component @Order(1)  class AmountValidator   implements CouponValidator { ... }
@Component @Order(2)  class ExpireValidator   implements CouponValidator { ... }
@Component @Order(3)  class StatusValidator   implements CouponValidator { ... }
```

### 为什么不用 if-else？

这是典型的**开闭原则**应用场景。如果用 if-else：

```java
// ❌ 不推荐
if (orderAmount < template.getMinAmount()) { throw ... }
if (now.isBefore(template.getValidStart())) { throw ... }
if (template.getStatus() != 1) { throw ... }
```

问题：
1. **扩展困难**：新增校验规则需要修改 `useCoupon` 方法，违反开闭原则
2. **测试困难**：所有校验逻辑耦合在一起，单元测试必须覆盖整个方法
3. **复用困难**：其他服务（如 order 自己）可能需要相同的校验逻辑但组合方式不同

责任链的优势：
1. **新增校验只需加一个 `@Component`**，不需要改 `useCoupon` 方法
2. **每个 Validator 独立可测**，不需要构造完整的 `CouponService` 上下文
3. **顺序可调**：`@Order` 的值决定了执行顺序

---

## 三个 Validator 的完整分析

### AmountValidator — 满减门槛

```java
@Component
@Order(1)
public class AmountValidator implements CouponValidator {
    @Override
    public void validate(CouponTemplate template, BigDecimal orderAmount) {
        // 校验：最小金额已设置 + > 0 + 订单金额不满足
        if (template.getMinAmount() != null
                && template.getMinAmount().compareTo(BigDecimal.ZERO) > 0
                && orderAmount.compareTo(template.getMinAmount()) < 0) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE,
                    String.format("订单金额%.2f未达使用门槛%.2f",
                            orderAmount, template.getMinAmount()));
        }
    }
}
```

**为什么 @Order(1)？** 金额校验是最先应该做的——如果金额不够，没必要检查有效期和状态。早期失败（fail-fast）减少后续 Validator 的无效执行。

**校验逻辑**：不判断 `type`，而是判断 `minAmount`。`minAmount != null && minAmount > 0` 的三个条件同时覆盖了满减券（type=1, minAmount>0）、折扣券（type=2, minAmount>0）、无门槛券（type=3, minAmount=0 自然跳过）。这是一个**数据驱动**而非类型驱动的校验设计——如果未来新增券类型（如 type=4 阶梯满减），只要 `minAmount` 设置正确，校验逻辑无需修改。

### ExpireValidator — 有效期

```java
@Component
@Order(2)
public class ExpireValidator implements CouponValidator {
    @Override
    public void validate(CouponTemplate template, BigDecimal orderAmount) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(template.getValidStart())) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券尚未生效");
        }
        if (now.isAfter(template.getValidEnd())) {
            throw new BizException(ResultCode.COUPON_EXPIRED);
        }
    }
}
```

**双重保障**：`ExpireValidator` 提供实时校验，`CouponExpireJob` 提供离线标记。即使过期标记延迟（Job 每小时才跑一次），实时校验也能拦截过期券。

**为什么 @Order(2)？** 在金额校验通过后、状态校验之前——如果券已过期，不需要检查状态。这是"先业务规则（金额/有效期），再管理操作（状态）"的逻辑。

### StatusValidator — 模板状态

```java
@Component
@Order(3)
public class StatusValidator implements CouponValidator {
    @Override
    public void validate(CouponTemplate template, BigDecimal orderAmount) {
        if (template.getStatus() == null || template.getStatus() != 1) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券已下线");
        }
    }
}
```

**为什么 @Order(3)？** 管理员操作（下线上线）是最后一道防线——如果金额和有效期都通过了，才检查是否被管理员禁用了。只有 status=1（启用）的模板的券才能使用。

---

## 用券的完整校验链

```
order (下单) → Feign → POST /api/coupon/use
  │
  ├── 查询 UserCoupon (MySQL)
  │     ├── userCoupon == null → COUPON_NOT_FOUND
  │     ├── userId 不匹配 → COUPON_NOT_FOUND
  │     └── status != 0 → COUPON_NOT_AVAILABLE
  │
  ├── 查询模板 (Redis 缓存 → MySQL)
  │     └── template == null → COUPON_NOT_FOUND
  │
  ├── AmountValidator: orderAmount >= minAmount?
  │     └── 不满足 → COUPON_NOT_AVAILABLE
  ├── ExpireValidator: now between validStart~validEnd?
  │     └── 不在 → COUPON_NOT_AVAILABLE / COUPON_EXPIRED
  ├── StatusValidator: template.status == 1?
  │     └── 不是 → COUPON_NOT_AVAILABLE
  │
  └── MySQL 乐观锁: UPDATE SET status=1 WHERE status=0
        └── affected=0 → COUPON_NOT_AVAILABLE ("已被使用")
```

**为什么责任链之后还有一层乐观锁？** 并发用券——两个订单同时用同一张券，责任链校验都通过但只有一个能成功 UPDATE。乐观锁是最后的数据层保证。

---

## returnCoupon 的 @Transactional 深度分析

源码：`CouponService.returnCoupon()`

```java
@Transactional(rollbackFor = Exception.class)
public void returnCoupon(Long userId, ReturnCouponRequest request) {
    // Step 1: MySQL 恢复状态（乐观锁）
    int affected = userCouponMapper.returnCoupon(id, orderId);
    if (affected == 0) { return; }  // 幂等

    // Step 2: MySQL 回退模板数量（同一事务内）
    templateMapper.incrementRemainCount(couponId);

    // Step 3: Redis 回退库存 (不参与 SQL 事务)
    stringRedisTemplate.execute(returnCouponScript, List.of(stockKey, claimedKey));
}
```

### 执行时序分析

```
Spring 事务边界：
  ├── 事务开始
  │
  ├── returnCoupon(UPDATE status=0) ─── 排队，等 commit 时执行
  ├── incrementRemainCount(UPDATE +1) ─ 排队，等 commit 时执行
  ├── returnCoupon.lua(INCR stock, DECR claimed) ─ 立即执行（非 SQL 操作）
  │
  ├── 方法返回 → Spring 提交事务
  │     └── MySQL 两条 UPDATE 真正执行
  │
  └── 如果 MySQL commit 失败？→ 已执行的 Redis Lua 不会回滚
```

**关键点**：Redis Lua 在事务内执行，但不在 SQL 事务边界内。Spring 管理 `@Transactional` 只覆盖 JDBC 操作（UPDATE/INSERT），Redis 操作不受 Spring 事务管理器管理。

### 失败场景分析

| 场景 | MySQL 结果 | Redis 结果 | 最终影响 |
|------|------|------|------|
| 全部成功 | commit ✓ | INCR+DECR ✓ | 正常 |
| MySQL commit 失败 | rollback，数据不变 | INCR+DECR 已执行 | **Redis stock+1, MySQL remain_count 不变** |
| Redis 失败 | commit ✓ | Lua 抛异常 | **MySQL remain_count+1, Redis stock 不变** |
| 方法中段崩溃 | rollback | 未执行（崩溃在 Redis 之前） | 一致性保持 ✓ |

场景 2（MySQL 失败但 Redis 成功）和场景 3（Redis 失败但 MySQL 成功）都会导致 Redis↔MySQL 不一致。凌晨 `CouponReconcileJob` 以 MySQL 为准修复。

---

## 面试 Q&A

### Q1：责任链校验和乐观锁都是做"校验"，有什么区别？

**答案**：责任链是**业务逻辑层**校验（金额、有效期、状态），乐观锁是**数据层**校验（并发安全）。

责任链的错误是可以预测的——"金额不够"、"券已过期"——这些是业务规则违反，返回具体的错误码。乐观锁的错误是不可预测的——"券已经被用了"——这是并发竞争的结果，没有明确的错误码（返回通用 COUPON_NOT_AVAILABLE）。

**追问**：如果把乐观锁 `WHERE status=0` 也做成一个 Validator 放在责任链里呢？

→ 无法做到。Validator 在执行时只能读取当前快照——`userCoupon.getStatus()` 返回 0，但 Validator 和 UPDATE 之间可能有其他请求已经改了 status。责任链校验是"读时刻"的判断，乐观锁是"写时刻"的判断——两者的时间窗口不同，不能合并。

### Q2：为什么退券的 @Transactional 不管理 Redis 操作？

**答案**：Spring 的 `@Transactional` 只管理 JDBC 事务（DataSourceTransactionManager），Redis 不是 JDBC 资源。要让 Redis 也参与事务需要分布式事务协调器（如 Seata），但 my-xhs 没有引入 Seata——而是选择了"容忍短暂不一致 + 凌晨对账修复"的方案。

**追问**：如果要让 Redis 也回滚，需要什么？

→ 需要两步：①把 Redis 操作放在事务提交后的回调 `TransactionSynchronization.afterCommit()` 中执行，这样 MySQL 失败时 Redis 还没执行。②引入消息队列或 Job 做补偿（类似于 inventory 的 L3 reconciliation）。当前 coupon 模块只做了第②步（凌晨对账），没有做第①步（afterCommit）。

### Q3：@Order 的排序策略有问题吗？如果管理员在券的有效期内下架了券，用户还能用吗？

**答案**：不能。StatusValidator 在 @Order(3) 最后执行——即使金额和有效期都通过，status=0 的券会被拦截。

**追问**：如果把 StatusValidator 提到 @Order(1) 会有什么影响？

→ 性能上没有影响（三个 Validator 都是内存计算和简单比较）。但语义上不合理——如果券已下架，用户应该先知道自己"金额不够"或"券已过期"，而不是只会看到"券已下架"。管理员操作是管理层面的控制，应该放在业务规则之后——让用户先看到更具体的拒绝原因。

---

## 发散：责任链 vs 策略模式 vs AOP

| 模式 | coupon 的使用 | 适用场景 | 为什么不用其他 |
|------|------|------|------|
| 责任链 | Validator @Order 排序 | 多个校验器按顺序执行，任一失败则中断 | 每个 Validator 职责单一，顺序可配 |
| 策略模式 | — | 根据类型选择不同算法（如满减/折扣的计算方式） | 用券校验不涉及算法选择 |
| AOP | — | 横切关注点（日志、权限） | Validator 有明确的业务语义和顺序依赖，AOP 的切面顺序不够直观 |
| 装饰器 | — | 动态增加行为，不改变接口 | 每个 Validator 都是独立的校验逻辑，不需要"包装"其他 Validator |

**为什么没用 AOP 实现？** AOP 可以用 `@Around` 对 `useCoupon` 做切面，在切面中执行校验。但 Validator 需要访问 `template`（从模板缓存获取）和 `orderAmount`（从请求参数）——这些信息在切面中需要额外传递。责任链则直接接收这些参数，代码更清晰。

---

## 生产故障实验

### 实验：验证 ExpireValidator 的实时拦截

```bash
# 1. 创建一张即将过期的券（有效期到未来 1 分钟）
curl -X POST http://localhost:19010/api/coupon/template \
  -d '{"name":"快过期券","type":3,"discountValue":5,"minAmount":0,"totalCount":10,"perUserLimit":1,"validStart":"2025-01-01 00:00:00","validEnd":"2026-07-28 16:00:00"}'

# 2. 过了 validEnd 后尝试使用
# ExpireValidator 会实时校验并拒绝——即使 CouponExpireJob 还没运行
# → {"code":30015,"message":"优惠券已过期"}
```

**说明**：这个实验验证了 ExpireValidator 的"双重保障"——即使离线标记（CouponExpireJob 每小时一次）还没更新 status，实时校验也能拦截已过期的券。用户不会因为定时任务的延迟而成功使用一张已过期的券。

### 实验：验证乐观锁防并发用券

```bash
# 1. 领取一张券
curl -X POST /api/coupon/claim -H "X-User-Id: 10001" -d '{"templateId":...}'

# 2. 并发两个"使用同一张券"的请求
# 用同一 userCouponId，只有第一个请求的 UPDATE WHERE status=0 成功
# 第二个请求 affected=0 → COUPON_NOT_AVAILABLE
```
