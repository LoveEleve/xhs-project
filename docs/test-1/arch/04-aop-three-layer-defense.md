# 04 — 三层 AOP 防护

> **目标读者**：P7+ 工程师，需要理解 @RateLimit → @DistributedLock → @Idempotent 的顺序设计、Lua 脚本实现、降级策略。
> **回答三个问题**：为什么是这个顺序？Redis 不可用怎么办？SpEL 动态 Key 怎么解析？

---

## 一、为什么需要三层防护？

在高并发场景下，单靠某一种防护手段是不够的：

| 场景 | 仅限流 | 仅锁 | 仅幂等 |
|------|--------|------|--------|
| 秒杀 10000 QPS | 有效，但漏过的请求仍会并发写 | 部分有效 | 部分有效 |
| 用户连点提交按钮 | 无效（单个用户 QPS 低） | 无效（锁 Key 相同，后到的等锁然后执行） | 有效 |
| 定时任务重复调度 | 无效（低频） | 有效（互斥） | 有效（防重复执行） |

**三层组合覆盖所有场景**：限流控全局 → 分布式锁控并发 → 幂等控重复。

---

## 二、漏斗式执行链

```
请求到达
  │
  ▼
┌─────────────────────────────────────────────┐
│  @RateLimit  (Order=10)                     │
│  全局流量控制                                │
│  Redis ZSet 滑动窗口                        │
│  被拒绝 → 直接返回，不执行后续                │
└──────────────┬──────────────────────────────┘
               │ 通过
               ▼
┌─────────────────────────────────────────────┐
│  @DistributedLock  (Order=50)               │
│  并发控制                                    │
│  Redisson RLock + Watchdog                  │
│  获取失败 → 直接返回，不执行后续              │
└──────────────┬──────────────────────────────┘
               │ 获取到锁
               ▼
┌─────────────────────────────────────────────┐
│  @Idempotent  (Order=100)                   │
│  重复控制                                    │
│  Redis SET NX                               │
│  重复请求 → 直接返回                          │
└──────────────┬──────────────────────────────┘
               │ 通过
               ▼
         执行业务逻辑
```

### 2.1 为什么是这个顺序？

| 顺序 | 理由 |
|------|------|
| **限流最先** | 被限流的请求不需要获取锁（减少锁竞争），不需要判断幂等（减少 Redis 操作） |
| **锁在中间** | 获取锁后才能安全执行业务，防止并发修改同一资源 |
| **幂等最后** | 同一请求在持有锁的情况下只执行一次，防止锁内重复执行 |

**反例**：如果幂等在锁之前，请求 A 通过了幂等检查但还没拿到锁，请求 B 也通过了幂等检查（SETNX 还没过期），两个请求都进入了锁等待，最终都会执行。

---

## 三、@RateLimit：滑动窗口限流

### 3.1 Lua 脚本实现

```lua
-- 删除窗口外的记录
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])

-- 当前窗口内请求数
local count = redis.call('ZCARD', KEYS[1])

if count < tonumber(ARGV[2]) then
    -- 未超限，添加记录
    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[5])
    redis.call('EXPIRE', KEYS[1], ARGV[4])
    return 1
else
    -- 超限，拒绝
    return 0
end
```

**参数说明**：

| 参数 | 值示例 | 含义 |
|------|--------|------|
| `KEYS[1]` | `ratelimit:OrderController:createOrder:userId:123` | 限流 Key |
| `ARGV[1]` | `1700000000000` | 窗口起始时间戳（`now - windowSeconds * 1000`） |
| `ARGV[2]` | `100` | 窗口内最大请求数 |
| `ARGV[3]` | `1700000001000` | 当前时间戳（ZSet score） |
| `ARGV[4]` | `1` | 窗口大小（秒，Key 过期时间） |
| `ARGV[5]` | `1700000001000:a1b2c3d4` | 唯一标识（`timestamp:UUID`） |

### 3.2 为什么用滑动窗口而非固定窗口？

| 方案 | 实现 | 问题 |
|------|------|------|
| 固定窗口 | `INCR + EXPIRE`，简单 | 窗口边界突发：00:00-00:01 的 100 次 + 00:01-00:02 的 100 次 = 2 秒内 200 次 |
| 滑动窗口（当前） | `ZSet + Lua`，O(log N) | 精度更高，流量更平滑 |

### 3.3 Key 构建策略

```
前缀:类名:方法名[:userId|:ip:{ip}]

perUser=true  → ratelimit:OrderController:createOrder:userId:123
perUser=false → ratelimit:OrderController:createOrder:ip:192.168.1.1
```

**IP 来源安全**：使用 Gateway 注入的 `X-Real-IP` Header，不使用 `X-Forwarded-For`（可被客户端伪造）。

### 3.4 使用示例

```java
// 全局限流：每秒最多 100 个请求
@RateLimit(windowSeconds = 1, maxRequests = 100)
public Result<OrderDTO> createOrder(CreateOrderRequest request) { ... }

// 按用户限流：每用户每秒最多 5 次下单
@RateLimit(windowSeconds = 1, maxRequests = 5, perUser = true)
public Result<OrderDTO> createOrder(CreateOrderRequest request) { ... }
```

---

## 四、@DistributedLock：分布式锁

### 4.1 四种锁类型

| 类型 | 实现 | 适用场景 |
|------|------|---------|
| `MUTEX` | `redissonClient.getLock(key)` | 通用互斥，默认选择 |
| `READ` | `redissonClient.getReadWriteLock(key).readLock()` | 读多写少，共享读 |
| `WRITE` | `redissonClient.getReadWriteLock(key).writeLock()` | 读写互斥，排他写 |
| `FAIR` | `redissonClient.getFairLock(key)` | 需要公平排队 |

### 4.2 Watchdog 机制

```
leaseTime = -1 (默认) → 启用 Watchdog
leaseTime > 0          → 固定过期时间，不续期
```

**Watchdog 工作流程**：
```
获取锁 → 启动 Watchdog 定时任务
  │
  ├── 每 lockWatchdogTimeout/3 续期
  │   （默认 15s/3 = 5s 续期一次，将过期时间重置为 15s）
  │
  └── 业务执行完成 → finally 释放锁 → 取消 Watchdog
```

**配置**（`RedissonConfig`）：
```java
config.setLockWatchdogTimeout(15000);  // 15s，比 Redisson 默认 30s 短
```

缩短 Watchdog 超时的原因：Sentinel 主从切换时，从节点可能丢失未同步的数据，15s 比 30s 减少了一半的锁丢失风险窗口。

### 4.3 SpEL 动态 Key

```java
@DistributedLock(
    key = "'order:lock:' + #request.userId",
    lockType = LockType.MUTEX,
    waitTime = 3,
    leaseTime = -1  // Watchdog
)
public Result<OrderDTO> createOrder(CreateOrderRequest request) {
    // #request.userId → 123
    // Redis Key: lock:order:lock:123
}
```

### 4.4 使用示例

```java
// 互斥锁：同一用户同时只能创建一个订单
@DistributedLock(key = "'order:create:' + #request.userId", waitTime = 3)
public Result<OrderDTO> createOrder(CreateOrderRequest request) { ... }

// 读写锁：读操作共享，写操作互斥
@DistributedLock(key = "'product:detail:' + #productId", lockType = LockType.READ)
public ProductDTO getProduct(Long productId) { ... }

@DistributedLock(key = "'product:detail:' + #productId", lockType = LockType.WRITE)
public void updateProduct(Long productId, UpdateProductRequest request) { ... }
```

---

## 五、@Idempotent：幂等

### 5.1 实现原理

```
首次请求:
  SET key value NX EX 60 → OK → 执行业务

重复请求:
  SET key value NX EX 60 → nil（Key 已存在）→ 拒绝，返回"请勿重复操作"
```

### 5.2 异常分类策略

幂等切面最精妙的设计是**异常分类**：根据业务异常类型决定是否删除幂等标记。

| 异常类型 | 处理 | 理由 |
|---------|------|------|
| `BizException` | **删除幂等标记** | 业务校验失败（如库存不足），业务未实际执行 |
| `IllegalArgumentException` | **删除幂等标记** | 参数校验失败，业务未执行 |
| `IllegalStateException` | **删除幂等标记** | 前置条件不满足，业务未执行 |
| `TimeoutException` | **保留幂等标记** | 业务可能已在远端执行成功 |
| `SocketTimeoutException` | **保留幂等标记** | 网络超时，业务状态不确定 |
| 其他未知异常 | **保留幂等标记** | 保守策略，宁可拒绝重试也不重复执行 |

**为什么这个设计很重要？**

```
场景：用户下单，库存不足
  → 抛出 BizException("库存不足")
  → 删除幂等标记
  → 用户补货后重新下单 → 成功

如果保留幂等标记：
  → 用户补货后重新下单 → "请勿重复操作" → 永远无法下单
```

### 5.3 使用示例

```java
@Idempotent(key = "'order:submit:' + #request.orderIdempotentKey", expireSeconds = 300)
public Result<OrderDTO> submitOrder(SubmitOrderRequest request) { ... }
```

---

## 六、统一降级策略：Redis 不可用怎么办？

三个切面采用相同的降级哲学：**Redis 不可用时降级放行，保证业务可用**。

```
┌──────────────────────────────────────────────────┐
│                   Redis 可用                       │
│  三层防护全部生效                                   │
│  限流 ✓  锁 ✓  幂等 ✓                              │
└──────────────────────┬───────────────────────────┘
                       │ Redis 故障
                       ▼
┌──────────────────────────────────────────────────┐
│                   Redis 不可用                      │
│  三层防护全部降级放行                               │
│  限流 ✗  锁 ✗  幂等 ✗                              │
│                                                   │
│  最终防线：DB 乐观锁（WHERE version/status）        │
└──────────────────────────────────────────────────┘
```

**为什么选择放行而非拒绝？**

| 方案 | 后果 |
|------|------|
| 拒绝所有请求 | 全站不可用，损失更大 |
| 降级放行 | 失去防护，但业务可用；DB 乐观锁作为最终防线 |

**代价**：Redis 故障期间可能出现并发问题（锁失效）和重复提交（幂等失效）。对于电商系统，这比全站不可用更可接受。

---

## 七、SpEL 动态 Key 解析

### 7.1 解析流程

```java
// SpELParser.parse() 核心逻辑：

// 1. 从 JoinPoint 获取方法参数
MethodSignature signature = (MethodSignature) joinPoint.getSignature();
String[] paramNames = signature.getParameterNames();    // ["request"]
Object[] args = joinPoint.getArgs();                    // [CreateOrderRequest{userId=123}]

// 2. 注入到 SpEL 上下文
StandardEvaluationContext context = new StandardEvaluationContext();
for (int i = 0; i < paramNames.length; i++) {
    context.setVariable(paramNames[i], args[i]);
}

// 3. 解析表达式
// "'order:lock:' + #request.userId" → "order:lock:123"
Expression expression = PARSER.parseExpression(spelExpression);
Object value = expression.getValue(context);
```

### 7.2 失败即抛的设计理由

```java
// 如果解析失败，不返回默认值，而是抛出异常
catch (EvaluationException e) {
    throw new IllegalArgumentException(
        "SpEL expression parse failed: " + spelExpression, e);
}
```

**为什么？** 如果 `@Idempotent(key = "#request.userId")` 解析失败但静默返回空字符串，所有请求共享 Key `idempotent:`，第一个请求执行后所有后续请求都被误判为重复。**失败即抛能尽早暴露配置错误**。

---

## 八、代价与局限

| 局限 | 影响 | 改进方向 |
|------|------|------|
| **Redis 故障时三层防护全部失效** | 并发、重复提交无保护 | DB 乐观锁作为最终防线 |
| **限流依赖单一 Redis** | Redis 故障失去限流能力 | Sentinel 本地兜底规则（→ 见 42） |
| **分布式锁降级放行有并发风险** | 可能短暂出现超卖 | inventory 的乐观锁兜底（→ 见 15） |
| **幂等标记过期时间固定** | 60s 后可能被误判为新请求 | 按业务场景差异化配置 |
| **Watchdog 在 GC 暂停时无法续期** | Full GC > 15s 导致锁过期 | 无完美方案，DB 乐观锁兜底 |

---

> **下一篇**：`05-trace-context-propagation.md` — 全链路流量染色：6 个染色标记的设计、HTTP Header → Feign → RocketMQ 三通道透传、ThreadLocal 跨线程传递
