# 分布式基础组件

> 所属服务：my-xhs-common | 开发阶段：Phase-4 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

公共基础组件是所有微服务共用的基础设施，在 my-xhs-common 中实现 4 个核心自定义注解组件 + 统一工具封装。所有服务引入 common 模块即可使用，避免重复造轮子。核心组件：①`@Idempotent` 幂等注解（防重复提交/重复消费）；②`@DistributedLock` 分布式锁注解（Redisson + SpEL）；③`@RateLimit` 限流注解（Redis Lua 滑动窗口）；④雪花 ID 生成器（CosId，全局唯一有序 ID）。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| @Idempotent 幂等注解 | ✅ | Redis SET NX + SpEL 参数解析 |
| @DistributedLock 分布式锁 | ✅ | Redisson RLock + Watchdog 续期 |
| @RateLimit 限流注解 | ✅ | Redis Lua 滑动窗口 |
| 雪花 ID 生成器 | ✅ | CosId + Redis 分配 WorkerId |
| 号段模式 ID | ✅ | MySQL 号段表 + 双 Buffer |
| 统一响应体 R\<T\> | ✅ | 统一 code + message + data |
| 错误码体系 | ✅ | 模块化错误码枚举 |
| RedisOperator 封装 | ✅ | 泛型 Redis 操作工具 |
| CacheHelper 缓存助手 | ✅ | Cache Aside 模式封装 |
| SpEL 表达式解析器 | ✅ | 从方法参数中提取 Key 值 |

### 1.3 使用方预估

| 组件 | 使用服务 | 典型场景 |
|------|---------|---------|
| @Idempotent | order/payment/coupon | 下单/支付/领券防重复 |
| @DistributedLock | inventory/coupon/analytics | 库存扣减/领券/关注 |
| @RateLimit | 所有服务 | 接口防刷 |
| 雪花 ID | order/content | 订单 ID/笔记 ID |
| 号段模式 | user | 用户 ID（短 ID） |

---

## 🏗️ 二、架构设计

### 2.1 模块结构

```
my-xhs-common/
├── annotation/
│   ├── Idempotent.java           — 幂等注解
│   ├── DistributedLock.java      — 分布式锁注解
│   └── RateLimit.java            — 限流注解
├── aspect/
│   ├── IdempotentAspect.java     — 幂等 AOP 切面
│   ├── DistributedLockAspect.java — 分布式锁 AOP 切面
│   └── RateLimitAspect.java      — 限流 AOP 切面
├── id/
│   ├── IdGeneratorUtil.java      — ID 生成工具（三合一）
│   ├── SnowflakeIdGenerator.java — 雪花 ID（CosId）
│   ├── SegmentIdGenerator.java   — 号段模式
│   └── RedisIdGenerator.java     — Redis 自增
├── spel/
│   └── SpELParser.java           — SpEL 表达式解析器
├── cache/
│   ├── RedisOperator.java        — 泛型 Redis 操作封装
│   └── CacheHelper.java          — Cache Aside 模式封装
├── response/
│   ├── R.java                    — 统一响应体
│   └── ErrorCode.java            — 错误码枚举
└── exception/
    ├── IdempotentException.java
    ├── DistributedLockException.java
    └── RateLimitException.java
```

### 2.2 AOP 切面处理流程

```
方法调用 → AOP 拦截
    │
    ▼
┌─────────────────────────────────────────────────────┐
│ SpEL 解析 Key（从方法参数中提取动态值）                 │
│ 例：@Idempotent(key = "'order:' + #request.userId") │
│ → 解析为 "order:10001"                              │
└────────────────────┬────────────────────────────────┘
                     │
    ┌────────────────┼────────────────┐
    ▼                ▼                ▼
┌────────┐    ┌──────────┐    ┌────────┐
│幂等切面 │    │分布式锁   │    │限流切面 │
│SET NX  │    │Redisson  │    │Lua脚本 │
│成功→放行│    │tryLock   │    │窗口判断│
│失败→拒绝│    │成功→执行  │    │通过→放行│
└────────┘    │finally释放│    │超限→拒绝│
              └──────────┘    └────────┘
```

---

## 🗄️ 三、数据库设计

### 3.1 号段模式表

```sql
-- 号段分配表（号段模式 ID 生成器使用）
CREATE TABLE IF NOT EXISTS t_id_segment (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    biz_tag     VARCHAR(64)  NOT NULL COMMENT '业务标签(如user/order)',
    max_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '当前最大ID',
    step        INT          NOT NULL DEFAULT 1000 COMMENT '号段步长',
    version     INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    description VARCHAR(256) DEFAULT NULL COMMENT '描述',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE INDEX uk_biz_tag (biz_tag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='号段分配表';

-- 初始化数据
INSERT INTO t_id_segment (biz_tag, max_id, step, description) VALUES
('user', 10000, 1000, '用户ID号段'),
('order', 0, 5000, '订单ID号段');
```

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `idempotent:{prefix}:{key}` | String | 自定义（默认 60s） | 幂等标记（SET NX） |
| `lock:{prefix}:{key}` | String | Redisson 管理 | 分布式锁（Watchdog 续期） |
| `ratelimit:{prefix}:{key}:{userId}` | ZSet | windowSeconds | 限流滑动窗口 |
| `id:worker:{serviceName}` | String | 永久 | 雪花 ID WorkerId 分配 |
| `id:segment:{bizTag}` | Hash | 永久 | 号段缓存（currentId/maxId） |

---

## 📡 五、接口设计

> 分布式基础组件不暴露 API 接口，以注解 + 工具类形式供其他服务使用。

### 5.1 注解使用示例

```java
// 幂等：防止重复下单
@Idempotent(key = "'order:create:' + #request.userId", expireSeconds = 30)
public OrderVO createOrder(CreateOrderRequest request) { ... }

// 分布式锁：库存扣减
@DistributedLock(key = "'inventory:deduct:' + #skuId", waitTime = 3, leaseTime = 10)
public void deductStock(Long skuId, Integer quantity) { ... }

// 限流：接口防刷
@RateLimit(windowSeconds = 1, maxRequests = 10, perUser = true)
public void sendComment(CommentRequest request) { ... }
```

---

## 💻 六、核心代码实现

### 6.1 @Idempotent 幂等注解 + 切面

```java
/**
 * 幂等注解定义
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /** 幂等Key的SpEL表达式 */
    String key();
    /** 过期时间（秒） */
    long expireSeconds() default 60;
    /** Key前缀 */
    String prefix() default "idempotent";
    /** 重复请求提示信息 */
    String message() default "请勿重复操作";
}

/**
 * 幂等 AOP 切面
 * 原理：Redis SET NX（不存在则设置），设置成功=首次请求，设置失败=重复请求
 */
@Aspect
@Component
public class IdempotentAspect {

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        // 1. SpEL 解析 Key
        String key = SpELParser.parse(idempotent.key(), joinPoint);
        String redisKey = idempotent.prefix() + ":" + key;

        // 2. Redis SET NX EX（原子操作）
        Boolean success = redisTemplate.opsForValue()
            .setIfAbsent(redisKey, "1", idempotent.expireSeconds(), TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(success)) {
            throw new IdempotentException(idempotent.message());
        }

        // 3. 执行业务方法
        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            // 业务异常时删除幂等标记，允许重试
            redisTemplate.delete(redisKey);
            throw e;
        }
    }
}
```

### 6.2 @DistributedLock 分布式锁注解 + 切面

```java
/**
 * 分布式锁注解定义
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    /** 锁Key的SpEL表达式 */
    String key();
    /** 等待获取锁的超时时间（秒） */
    long waitTime() default 3;
    /** 锁自动释放时间（秒），-1=Watchdog续期 */
    long leaseTime() default -1;
    /** Key前缀 */
    String prefix() default "lock";
    /** 获取锁失败提示 */
    String message() default "操作过于频繁，请稍后重试";
}

/**
 * 分布式锁 AOP 切面
 * 基于 Redisson RLock，支持 Watchdog 自动续期
 * Watchdog 原理：锁持有期间每 10 秒自动续期（leaseTime/3），业务执行完释放
 */
@Aspect
@Component
public class DistributedLockAspect {

    @Autowired
    private RedissonClient redissonClient;

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        // 1. SpEL 解析 Key
        String key = SpELParser.parse(distributedLock.key(), joinPoint);
        String lockKey = distributedLock.prefix() + ":" + key;

        // 2. 获取 Redisson RLock
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired;
        try {
            // leaseTime=-1 时启用 Watchdog 自动续期
            if (distributedLock.leaseTime() == -1) {
                acquired = lock.tryLock(distributedLock.waitTime(), TimeUnit.SECONDS);
            } else {
                acquired = lock.tryLock(distributedLock.waitTime(),
                    distributedLock.leaseTime(), TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DistributedLockException("获取锁被中断");
        }

        if (!acquired) {
            throw new DistributedLockException(distributedLock.message());
        }

        // 3. 执行业务方法，finally 释放锁
        try {
            return joinPoint.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 6.3 @RateLimit 限流注解 + Lua 脚本

```java
/**
 * 限流注解定义
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    /** 时间窗口（秒） */
    int windowSeconds() default 1;
    /** 窗口内最大请求数 */
    int maxRequests() default 100;
    /** 是否按用户限流 */
    boolean perUser() default false;
    /** Key前缀 */
    String prefix() default "ratelimit";
    /** 限流提示 */
    String message() default "请求过于频繁，请稍后重试";
}

/**
 * 限流 AOP 切面
 * Redis Lua 滑动窗口算法（ZSet 实现）
 */
@Aspect
@Component
public class RateLimitAspect {

    // Lua 脚本：原子操作（移除过期 + 判断 + 记录）
    private static final String LUA_SCRIPT = """
        redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
        local count = redis.call('ZCARD', KEYS[1])
        if count < tonumber(ARGV[2]) then
            redis.call('ZADD', KEYS[1], ARGV[3], ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return 1
        else
            return 0
        end
        """;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(joinPoint, rateLimit);
        long now = System.currentTimeMillis();
        long windowStart = now - rateLimit.windowSeconds() * 1000L;

        // 执行 Lua 脚本
        Long result = redisTemplate.execute(
            new DefaultRedisScript<>(LUA_SCRIPT, Long.class),
            Collections.singletonList(key),
            String.valueOf(windowStart),
            String.valueOf(rateLimit.maxRequests()),
            String.valueOf(now),
            String.valueOf(rateLimit.windowSeconds())
        );

        if (result == null || result == 0) {
            throw new RateLimitException(rateLimit.message());
        }

        return joinPoint.proceed();
    }
}
```

### 6.4 雪花 ID 生成器（CosId）

```java
/**
 * ID 生成工具类（三合一：雪花 + 号段 + Redis自增）
 */
@Component
public class IdGeneratorUtil {

    private final IdGenerator snowflakeGenerator;
    private final SegmentIdGenerator segmentGenerator;
    private final StringRedisTemplate redisTemplate;

    /** 雪花 ID（订单/笔记/评论） */
    public long nextId() {
        return snowflakeGenerator.generate();
    }

    /** 号段模式 ID（用户ID，短ID） */
    public long nextSegmentId(String bizTag) {
        return segmentGenerator.nextId(bizTag);
    }

    /** Redis 自增 ID（流水号，日期前缀） */
    public String nextSerialNo(String prefix) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String key = "id:serial:" + prefix + ":" + dateStr;
        Long seq = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, 2, TimeUnit.DAYS);
        return dateStr + String.format("%06d", seq);
    }
}
```

### 6.5 SpEL 表达式解析器

```java
/**
 * SpEL 表达式解析器
 * 从方法参数中提取动态 Key 值
 * 例：@DistributedLock(key = "'inventory:' + #skuId")
 *     方法参数 skuId=123 → 解析为 "inventory:123"
 */
public class SpELParser {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    public static String parse(String expression, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < paramNames.length; i++) {
            context.setVariable(paramNames[i], args[i]);
        }

        return PARSER.parseExpression(expression).getValue(context, String.class);
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 分布式锁：Redisson vs 手写 Redis 锁 vs ZooKeeper

| 维度 | Redisson（✅ 选定） | 手写 SET NX | ZooKeeper |
|------|-------------------|------------|-----------|
| 原子性 | ✅ Lua 脚本 | ❌ SET + EXPIRE 非原子 | ✅ |
| 自动续期 | ✅ Watchdog | ❌ 需手动估算过期时间 | ✅ 临时节点 |
| 可重入 | ✅ | ❌ 需自己实现 | ✅ |
| 性能 | 高（Redis） | 高 | 中（ZK 写性能差） |
| 复杂度 | 低（开箱即用） | 高（坑多） | 中 |

**选择理由**：Redisson 开箱即用，Watchdog 解决"业务未完成锁已过期"的致命问题。手写 Redis 锁有两个坑：①SET + EXPIRE 非原子（SET 成功 EXPIRE 失败=死锁）；②无法自动续期（业务 30 秒，锁 10 秒=并发问题）。

### 7.2 分布式 ID：雪花 ID vs 号段模式

| 维度 | 雪花 ID | 号段模式 |
|------|---------|---------|
| ID 长度 | 18 位 | 8-10 位 |
| 趋势递增 | ✅ | ✅ |
| 性能 | 极高（本地生成） | 高（内存分配） |
| 依赖 | 时钟 | MySQL |
| 适用场景 | 订单/笔记（内部 ID） | 用户 ID（对外展示） |

---

## 🐛 八、踩坑记录

### 8.1 Redisson Watchdog 不生效

- **现象**：指定了 leaseTime 后 Watchdog 不续期
- **原因**：Redisson 设计：指定 leaseTime 时不启用 Watchdog（认为你已经估算好时间）
- **解决**：需要 Watchdog 时 leaseTime 设为 -1（默认 30 秒，每 10 秒续期）
- **教训**：Watchdog 只在 leaseTime=-1 时生效

### 8.2 SpEL 解析 NPE

- **现象**：`@Idempotent(key = "'order:' + #request.userId")` 报 NPE
- **原因**：request 参数为 null 时 SpEL 解析失败
- **解决**：SpEL 解析前做 null 检查，null 参数用 "null" 字符串替代

### 8.3 雪花 ID 时钟回拨

- **现象**：服务报错 "Clock moved backwards"
- **原因**：NTP 时间同步导致时钟回拨
- **解决**：小回拨（< 5ms）等待，大回拨（≥ 5ms）告警 + 人工介入
- **教训**：K8s Pod 的 NTP 配置要确认

### 8.4 幂等注解业务异常后无法重试

- **现象**：业务抛异常后，重试请求被幂等拦截
- **原因**：SET NX 成功后业务失败，但幂等标记未删除
- **解决**：catch 业务异常时主动删除幂等标记，允许重试

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 幂等-首次请求 | 正常请求 | 放行执行 | ⬜ |
| 幂等-重复请求 | 60 秒内相同 Key | 抛出 IdempotentException | ⬜ |
| 幂等-业务异常重试 | 业务抛异常后重试 | 允许重试 | ⬜ |
| 分布式锁-获取成功 | 无竞争 | 正常执行 + 自动释放 | ⬜ |
| 分布式锁-等待超时 | 锁被占用 3 秒 | 抛出 DistributedLockException | ⬜ |
| 分布式锁-Watchdog | 业务执行 40 秒 | 锁自动续期不过期 | ⬜ |
| 限流-未超限 | 1 秒内 5 次（限 10） | 全部放行 | ⬜ |
| 限流-超限 | 1 秒内 11 次（限 10） | 第 11 次抛出 RateLimitException | ⬜ |
| 雪花 ID | 并发生成 10 万个 | 全部唯一 + 趋势递增 | ⬜ |
| 号段模式 | 号段用完 | 自动取新号段 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 分布式锁怎么实现的？Watchdog 是什么原理？

> 1. "基于 Redisson RLock，底层是 Redis Lua 脚本保证原子性"
> 2. "Watchdog 原理：leaseTime=-1 时启用，默认锁 30 秒，每 10 秒（leaseTime/3）自动续期"
> 3. "解决的问题：业务未执行完锁已过期（业务 30 秒，锁 10 秒=并发问题）"
> 4. "vs 手写 Redis 锁：SET NX + EXPIRE 非原子 + 无法续期，两个致命问题"

### Q2: 幂等怎么做的？Token 机制是怎么回事？

> 1. "方案一（我们用的）：@Idempotent 注解 + Redis SET NX + SpEL 解析 Key"
> 2. "原理：同一个 Key 在过期时间内只能 SET 成功一次，第二次 SET 失败=重复请求"
> 3. "Token 机制（方案二）：服务端先生成 Token 给客户端，客户端请求时带上 Token，服务端校验并删除"
> 4. "业务异常时删除幂等标记，允许重试（不删除=异常后无法重试）"

### Q3: 雪花 ID 有什么问题？怎么解决时钟回拨？

> 1. "核心问题：NTP 同步可能导致时钟回拨，生成重复 ID"
> 2. "解决：小回拨（< 5ms）sleep 等待，大回拨（≥ 5ms）拒绝生成 + 告警"
> 3. "WorkerId 分配：Redis INCR 自动分配（0-31），启动时申请，避免手动配置出错"
> 4. "为什么用 CosId 不手写：CosId 内置时钟回拨检测 + Provider 自动化 WorkerId"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-4/README.md | §3.20 | 分布式基础组件完整设计 |
| 📄 02-module-detailed-design.md | §1 | common 模块注解/缓存/ID |
| 📄 03-distributed-solutions.md | §5/§12/§13 | 分布式 ID/锁/幂等方案 |
