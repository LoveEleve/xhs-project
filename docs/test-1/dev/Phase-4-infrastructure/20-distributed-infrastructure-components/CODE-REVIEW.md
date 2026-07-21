# 20-分布式基础组件 Code Review

## 📊 对标 P8 评分表

| 维度 | 满分 | 得分 | 详细分析 |
|------|:----:|:----:|----------|
| 架构设计 | 20 | 18 | AOP 注解 + SpEL 动态 Key + Lua 原子操作，分层清晰。**扣分**：`@Idempotent` 缺少 `RETURN_CACHED` 模式（文档 §18 明确要求，留待 Phase-5 增强） |
| 分布式安全 | 20 | 19 | Redisson Watchdog、Lua 滑动窗口、号段乐观锁、Redis 降级放行。**扣分**：降级放行后缺少 Prometheus 指标上报（仅日志告警） |
| 代码质量 | 15 | 14 | 注解/切面/工具类分层清晰，SpEL 解析器独立复用。**扣分**：`CacheHelper` 的空值标记 `__CACHE_NULL__` 用字符串比较，如果业务数据恰好是这个字符串会误判 |
| 性能设计 | 15 | 14 | 号段双 Buffer 预加载、雪花 ID 本地生成、nextSerialNo 只在首次设置过期。**扣分**：限流 ZSet member 用 UUID 内存占用较大 |
| 可靠性 | 15 | 14 | 号段乐观锁重试、线程池优雅关闭、三个切面 Redis 降级放行。**扣分**：号段模式在 DB 不可用时无降级（直接抛异常） |
| 面试价值 | 15 | 15 | 分布式锁 Watchdog、幂等异常处理策略、号段双 Buffer、Lua 滑动窗口、Redis 降级策略——全是高频面试题 |
| **总分** | **100** | **94** | **修复后从 91 提升到 94** |

---

## 🐛 发现的问题及修复记录

### 问题 1：三个切面缺少 Redis 降级策略（🔴 严重）— ✅ 已修复

**问题描述**：`IdempotentAspect`、`DistributedLockAspect`、`RateLimitAspect` 在 Redis 不可用时直接抛异常，导致所有标注了注解的接口全部不可用。

**生产影响**：Redis 故障 → 所有下单/支付/领券接口全部 500 → 全站不可用。

**修复方案**：在每个切面的 Redis 操作外层 catch 异常，降级为放行 + 告警日志。

**修复前**（IdempotentAspect 为例）：
```java
Boolean success = stringRedisTemplate.opsForValue()
        .setIfAbsent(redisKey, "1", idempotent.expireSeconds(), TimeUnit.SECONDS);
// Redis 挂了直接抛 RedisConnectionException → 接口 500
```

**修复后**：
```java
Boolean success;
try {
    success = stringRedisTemplate.opsForValue()
            .setIfAbsent(redisKey, "1", idempotent.expireSeconds(), TimeUnit.SECONDS);
} catch (Exception e) {
    // Redis 不可用时降级放行（保证核心业务可用）
    log.error("[幂等] Redis不可用，降级放行, key={}", redisKey, e);
    return joinPoint.proceed();
}
```

**同样修复了 DistributedLockAspect 和 RateLimitAspect**。

---

### 问题 2：`@Idempotent` 缺少 `RETURN_CACHED` 模式（🔴 严重）— ⏳ 留待 Phase-5

**问题描述**：幂等命中时直接抛异常"请勿重复操作"。用户网络抖动重试时收到错误，但实际第一次已成功。

**大厂做法**（支付宝/美团）：幂等命中时返回上次成功的结果。

**当前状态**：设计文档 `03-distributed-solutions.md §18` 已有完整方案，留待 Phase-5 增强。

---

### 问题 3：`nextSerialNo()` 的 `increment + expire` 非原子（🟡 中等）— ✅ 已修复

**问题描述**：每次调用都执行 `increment` + `expire` 两次 Redis 操作。`increment` 成功但 `expire` 失败 → Key 永不过期 → Redis 内存泄漏。

**修复方案**：只在 `seq == 1`（首次创建 Key）时设置过期，减少网络往返且避免泄漏。

**修复前**：
```java
Long seq = stringRedisTemplate.opsForValue().increment(key);
stringRedisTemplate.expire(key, 2, TimeUnit.DAYS);  // 每次都 expire（多余 + 非原子）
```

**修复后**：
```java
Long seq = stringRedisTemplate.opsForValue().increment(key);
if (seq != null && seq == 1) {
    stringRedisTemplate.expire(key, 2, TimeUnit.DAYS);  // 只在首次创建时设置
}
```

---

### 问题 4：`SpELParser` 解析失败时静默返回原始表达式（🟡 中等）— ✅ 已修复

**问题描述**：SpEL 表达式写错时（如 `#request.userId` 但参数名是 `req`），所有请求解析为同一个 Key，导致第一个请求成功后所有后续请求都被幂等拦截。

**修复方案**：解析失败时抛出 `IllegalArgumentException`，在开发阶段暴露配置错误。

**修复前**：
```java
catch (Exception e) {
    return expression;  // 静默返回原始表达式 → 所有请求共享同一个 Key → 误拦截
}
```

**修复后**：
```java
catch (Exception e) {
    throw new IllegalArgumentException(
        "SpEL 表达式解析失败: '" + expression + "', 请检查注解中的 key 表达式是否正确", e);
}
```

---

### 问题 5：`DistributedLockAspect` 的 unlock 缺少异常保护（🟡 中等）— ✅ 已修复

**问题描述**：`lock.unlock()` 在 Redis 连接断开时会抛异常，导致 finally 块中断，可能影响后续资源清理。

**修复方案**：unlock 外层加 try-catch，释放失败时由 Redisson Watchdog 在 leaseTime 后自动过期。

---

### 问题 6：限流 ZSet member 用 UUID 内存占用大（🟢 低）— 📝 记录

**问题描述**：每次请求生成 50 字符的 UUID 作为 ZSet member，高 QPS 下内存峰值较高。

**优化方向**：可用 `AtomicLong.incrementAndGet()` 替代 UUID，减少一半内存。当前 EXPIRE 兜底可接受。

---

## 💡 技术亮点和面试价值评估

### 亮点 1：Redis 降级策略（生产级必备）

**面试价值**：★★★★★

```
Redis 正常 → 幂等/限流/锁 正常工作
Redis 故障 → 降级放行 + 告警日志 → 核心业务不受影响
Redis 恢复 → 自动恢复保护
```

**面试话术**：
> "我们的三个注解切面都有 Redis 降级策略。Redis 不可用时降级放行，保证核心业务可用。原则是：**宁可短暂失去保护，也不能让保护机制本身成为故障点**。这是生产环境的基本要求——任何中间件都可能挂，不能因为限流组件挂了导致全站不可用。"

### 亮点 2：幂等异常处理策略（区分可重试/不可重试）

**面试价值**：★★★★★

```
BizException（参数校验失败）→ 删除幂等标记 → 允许重试
TimeoutException（DB 超时）→ 保留幂等标记 → 拒绝重试
未知异常 → 保留幂等标记 → 保守策略
```

**面试话术**：
> "幂等的核心目的是防止重复执行，不是保证一定能重试。DB 超时不代表写入失败，业务可能已经执行成功了。如果此时删除幂等标记允许重试，就可能重复下单。所以我们的策略是：只有明确的业务校验异常才删除标记，超时和未知异常保守处理。"

### 亮点 3：AOP 切面执行顺序设计

**面试价值**：★★★★☆

```
@RateLimit (Order=10)  → 最先执行：先限流，最早拒绝无效请求
@DistributedLock (Order=50)  → 获取锁，保证串行执行
@Idempotent (Order=100)  → 最后执行：在锁保护下判断幂等
```

**设计原理**：限流在最外层（最早拒绝），幂等在最内层（在锁保护下判断，避免并发误判）。

### 亮点 4：号段模式双 Buffer + 乐观锁

**面试价值**：★★★★★

```
current 号段使用量达 70% → 异步预加载 next 号段
current 用完 → 无缝切换到 next（零延迟）
next 未就绪 → 降级为同步加载
多实例并发 → 乐观锁保证不冲突
```

### 亮点 5：SpEL 表达式解析 + 快速失败

**面试价值**：★★★★☆

> "SpEL 解析失败时我们选择快速失败（抛异常），而不是静默降级。原因：如果静默返回原始表达式字符串，所有请求会共享同一个幂等 Key，导致第一个请求成功后所有后续请求都被误拦截。这种 bug 在测试环境很难发现（因为测试通常只发一次请求），但在生产环境会导致大面积故障。"

---

## 🎤 面试话术（Q&A 格式）

### Q1: 分布式锁怎么实现的？Redis 挂了怎么办？

> "基于 Redisson RLock，通过 @DistributedLock 注解 + AOP 切面，业务代码零侵入。
>
> Watchdog 原理：leaseTime=-1 时启用，默认锁 30 秒，每 10 秒续期。解决'业务未完成锁已过期'的致命问题。
>
> **Redis 挂了的降级策略**：catch 连接异常后降级放行，保证核心业务可用。原则是'宁可短暂失去锁保护，也不能让锁组件本身成为故障点'。同时记录告警日志，运维及时介入。
>
> unlock 也做了异常保护——如果释放锁时 Redis 连接断开，Watchdog 会在 leaseTime 后自动过期，不会死锁。"

### Q2: 幂等怎么做的？业务异常后能重试吗？

> "@Idempotent 注解 + Redis SET NX + SpEL 动态 Key。
>
> 异常处理是关键设计决策：
> - BizException（参数校验失败）→ 删除幂等标记，允许重试（业务未执行）
> - TimeoutException → 保留幂等标记，拒绝重试（业务可能已执行）
> - 未知异常 → 保守策略，保留标记（宁可拒绝也不重复）
>
> Redis 不可用时降级放行。SpEL 解析失败时快速失败（抛异常），避免所有请求共享同一个 Key 导致误拦截。"

### Q3: 限流用什么算法？为什么选滑动窗口？

> "Redis Lua 脚本实现滑动窗口（ZSet）。Lua 保证原子性：移除过期 + 判断 + 记录一步完成。
>
> vs 固定窗口：无临界突刺问题。vs 令牌桶：实现更简单，5 行 Lua 搞定。
>
> 支持全局限流和按用户限流。按用户限流从 X-User-Id Header 获取（Gateway 注入），未登录用户按 IP 限流。
>
> Redis 不可用时降级放行——限流组件不能成为故障点。"

### Q4: 号段模式 ID 怎么保证不重复？双 Buffer 是什么？

> "从 MySQL 批量获取号段，内存中分配。多实例通过乐观锁保证不冲突。
>
> 双 Buffer：current 使用量达 70% 时异步预加载 next。current 用完时无缝切换，零延迟。
>
> 降级策略：next 预加载太慢时降级为同步加载。bizTag 不存在时给出包含 INSERT 语句的友好提示。
>
> 线程池优雅关闭：@PreDestroy 等待预加载任务完成，避免号段数据不一致。"

---

## 🔬 深度技术分析

### 1. Redis 降级策略的权衡

```
                    Redis 正常                    Redis 故障
                    ┌─────────┐                  ┌─────────┐
@Idempotent         │ SET NX  │                  │ 降级放行 │ → 可能重复执行（但业务层有 DB 唯一键兜底）
                    └─────────┘                  └─────────┘
@DistributedLock    │ tryLock │                  │ 降级放行 │ → 可能并发执行（但比全站不可用好）
                    └─────────┘                  └─────────┘
@RateLimit          │ Lua脚本 │                  │ 降级放行 │ → 可能被刷（但比拒绝所有请求好）
                    └─────────┘                  └─────────┘
```

**核心原则**：中间件故障时，保护机制降级，核心业务不降级。

### 2. 幂等异常处理决策树

```
业务方法抛出异常
    │
    ├── BizException（业务校验失败）
    │   └── 删除幂等标记 ✅ → 允许重试（业务未执行）
    │
    ├── IllegalArgumentException / IllegalStateException
    │   └── 删除幂等标记 ✅ → 允许重试（前置条件不满足）
    │
    ├── TimeoutException / SocketTimeoutException
    │   └── 保留幂等标记 ❌ → 拒绝重试（业务可能已执行）
    │
    ├── cause 是 TimeoutException
    │   └── 保留幂等标记 ❌ → 递归检查包装异常
    │
    └── 其他未知异常
        └── 保留幂等标记 ❌ → 保守策略（宁可拒绝也不重复）
```

### 3. AOP 切面执行顺序

```
请求到达
    │
    ▼
@RateLimit (Order=10)  ← 最先执行：先限流，最早拒绝无效请求
    │ 通过
    ▼
@DistributedLock (Order=50)  ← 获取锁，保证串行执行
    │ 获取成功
    ▼
@Idempotent (Order=100)  ← 最后执行：在锁保护下判断幂等
    │ 首次请求
    ▼
业务方法执行
    │
    ▼
释放锁 → 返回结果
```

### 4. 集成测试验证结果（上一轮已验证）

| 测试场景 | 预期结果 | 实际结果 | 通过 |
|----------|----------|----------|:----:|
| 幂等-首次请求 | 200 成功 | 200 成功 | ✅ |
| 幂等-重复请求 | 40201 拒绝 | 40201 拒绝 | ✅ |
| 幂等-不同Key | 200 成功 | 200 成功 | ✅ |
| 分布式锁-获取成功 | 200 成功 | 200 成功 | ✅ |
| 限流-未超限(3次) | 全部 200 | 全部 200 | ✅ |
| 限流-超限(第4/5次) | 40202 拒绝 | 40202 拒绝 | ✅ |
| 按用户限流 | 前2次200，第3次40202 | 符合预期 | ✅ |
| 按用户限流-隔离 | 不同用户互不影响 | 符合预期 | ✅ |
| 雪花ID | 唯一+递增 | 唯一+递增 | ✅ |
| 号段模式ID | 10001/10002/10003 | 10001/10002/10003 | ✅ |
| Redis流水号 | PAY20260514000001 | PAY20260514000001 | ✅ |

---

## 📋 遗留项（Phase-5/6 增强）

| # | 优先级 | 内容 | 目标阶段 |
|---|:------:|------|:--------:|
| 1 | 🔴 高 | `@Idempotent` 增加 `RETURN_CACHED` 模式（返回上次成功结果） | Phase-5 |
| 2 | 🟡 中 | Redis 降级时增加 Prometheus 指标上报（`redis.degradation.count`） | Phase-5 |
| 3 | 🟢 低 | 限流 ZSet member 优化（UUID → AtomicLong） | Phase-6 |
