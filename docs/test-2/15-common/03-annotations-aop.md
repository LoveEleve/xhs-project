# Common 注解 AOP — @DistributedLock / @Idempotent / @RateLimit

> 关联源码：`DistributedLock.java` / `DistributedLockAspect.java` / `Idempotent.java` / `IdempotentAspect.java` / `RateLimit.java` / `RateLimitAspect.java`

---

## 业务背景

微服务中大量重复代码：加锁、判重、限流。每个业务自己写一遍 → 代码爆炸、容易遗漏。

```
// ❌ 业务代码里混入基础设施逻辑
boolean locked = redisLock.tryLock("order:" + orderId, 3, TimeUnit.SECONDS);
if (!locked) throw new BizException("订单处理中");
try {
    boolean isFirst = redisTemplate.opsForValue().setIfAbsent("idempotent:" + msgId, "1", 24, TimeUnit.HOURS);
    if (!isFirst) return;
    ...
} finally { lock.unlock(); }
```

注解 AOP 把基础设施收归 common，业务只关心业务。

---

## @DistributedLock

### 使用方式

```java
@DistributedLock(key = "'inventory:deduct:' + #skuId", lockType = FAIR, waitTime = 3)
public void deductStock(Long skuId, Integer quantity) { ... }
```

### 4 种锁类型

| 类型 | 实现 | 并发特性 |
|---|---|---|
| MUTEX | `RedissonClient.getLock()` | 互斥 |
| READ | `RedissonClient.getReadWriteLock().readLock()` | 共享 |
| WRITE | `RedissonClient.getReadWriteLock().writeLock()` | 排他 |
| FAIR | `RedissonClient.getFairLock()` | FIFO |

### 核心逻辑

```java
// 1. SpEL 解析 Key
String lockKey = prefix + ":" + SpEL.parse(key)

// 2. 获取锁（Watchdog 自动续期）
if (leaseTime == -1) {
    lock.tryLock(waitTime, timeUnit);  // Watchdog 默认 30s 锁，每 10s 续期
} else {
    lock.tryLock(waitTime, leaseTime, timeUnit);
}

// 3. 获取失败 → 抛 BizException
if (!locked) throw new BizException(message);

// 4. 执行业务 → finally 解锁
try { joinPoint.proceed(); }
finally { lock.unlock(); }
```

**Watchdog 原理**：Redisson 的锁默认 30 秒超时。当 leaseTime=-1 时启用 Watchdog：每 10 秒检查一次，如果锁还在持有中，自动续期 30 秒。防止业务执行时间超过锁超时时间导致锁提前释放。

### Redis 降级

```java
} catch (Exception e) {
    log.error("Redis 不可用，降级放行");
    return joinPoint.proceed();  // 无锁执行
}
```

Redis 挂了 → 降级放行，不加锁。有并发风险但比全站不可用好。降级日志触发 P1 告警。

---

## @Idempotent

### 使用方式

```java
@Idempotent(key = "'order:create:' + #userId + ':' + #request.orderNo", expireSeconds = 86400)
public R<Long> createOrder(@RequestHeader Long userId, @RequestBody CreateOrderRequest request) { ... }
```

### 核心逻辑

```java
// Redis SET NX EX
String key = "idempotent:" + SpEL.parse(key);
Boolean success = redisTemplate.opsForValue().setIfAbsent(key, "1", expireSeconds, TimeUnit.SECONDS);
if (Boolean.FALSE.equals(success)) {
    throw new BizException(ResultCode.DUPLICATE_OPERATION);
}
try {
    return joinPoint.proceed();
} catch (BizException | IllegalArgumentException e) {
    redisTemplate.delete(key); // 可重试异常 → 删除标记
    throw e;
} catch (Exception e) {
    // 其他异常保留标记（非重试异常）
    throw e;
}
```

**异常敏感性**：

| 异常类型 | 幂等标记 | 语义 |
|---|---|---|
| BizException | 删除 | 业务失败可重试 |
| IllegalArgumentException | 删除 | 参数错误可重试 |
| TimeoutException | 保留 | 不确定是否已执行，不删除 |
| 其他异常 | 保留 | 系统异常，保留标记 |

---

## @RateLimit

### 使用方式

```java
@RateLimit(windowSeconds = 60, maxRequests = 30, perUser = true, prefix = "social:like")
public R<Void> like(Long userId, Long noteId) { ... }
```

### 核心逻辑

```java
// Lua 脚本：固定窗口（INCR + EXPIRE）
// 注意：不是滑动窗口——窗口切换瞬间可能允许 2 倍流量通过
String key = prefix + ":" + (perUser ? userId : "global");
Long count = redisTemplate.execute(
    "local c = redis.call('INCR', KEYS[1]) " +
    "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
    "return c",
    List.of(key), windowSeconds);

if (count > maxRequests) {
    throw new BizException(ResultCode.TOO_MANY_REQUESTS);
}
```

---

## 执行顺序

```java
@Order(10)  RateLimit         ← 先限流
@Order(50)  DistributedLock   ← 再获取锁
@Order(100) Idempotent        ← 最后判断幂等
```

---

## 面试 Q&A

**Q: Watchdog 续期失败会怎样？**
A: Redisson 的 Watchdog 每 10 秒续期一次。如果 Redis 宕机，续期失败，锁在 30 秒后自动释放。可能有两个线程同时进入临界区——但 30 秒窗口比不设锁好（Redis 宕机时降级放行）。

**Q: @Idempotent 和 @DistributedLock 一起用时顺序？**
A: 先 @DistributedLock（Order=50）再 @Idempotent（Order=100）。原因：获取锁后再判断幂等，避免并发时两个请求同时通过幂等检查（SETNX 是原子操作，但两个请求可能同时检查到不存在）。

---

## 发散

### 注解 vs 编程式

注解 AOP 对业务代码侵入最小，但缺点是：
- 无法在循环中调用（注解只能加在方法上）
- 动态代理的限制（同类中方法调用不走 AOP）
- 调试时隐式逻辑

如果需要更灵活的控制，可以用编程式 API：

```java
idempotentHelper.execute("order:" + orderNo, 86400, () -> orderService.create(request));
```

### 分布式锁 vs 乐观锁

分布式锁适合短时间、高竞争场景（如扣库存）。长时间、低竞争场景可以用数据库乐观锁（version 字段），减少 Redis 调用。
