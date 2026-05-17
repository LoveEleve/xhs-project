# My-XHS Home + Gateway 模块深度 Code Review

> 对标大厂 P8 标准 | 审查时间：2026-05-16 | 审查人：AI Code Reviewer

---

## 一、P8 对标评分表

### Home 模块

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | **8.5/10** | 推拉混合Feed + 2层并行聚合 + 动态超时控制，设计成熟 |
| 并发安全 | **8.0/10** | MDC透传Bug已修复，双层线程池隔离，Feed游标精度问题已修复 |
| 分布式考量 | **8.0/10** | XXL-Job分布式调度OK，Feed清理已限速，大V标记缓存缩短为10分钟 |
| 性能优化 | **7.5/10** | 大V发件箱Pipeline批量查询OK，逐个Feign N+1问题待下游提供批量接口 |
| 生产就绪 | **8.0/10** | 测试接口已隔离到@Profile("dev")，Lua脚本条件裁剪优化 |
| **综合** | **8.0/10** | |

### Gateway 模块

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | **8.0/10** | 过滤器链分工明确、Order设计合理 |
| 安全机制 | **8.5/10** | HMAC白名单逻辑已修复，JWT+HMAC+黑名单三重校验 |
| 限流熔断 | **7.5/10** | 已添加Nacos数据源配置说明，生产环境需启用 |
| 灰度/染色 | **7.0/10** | 框架搭好了但GrayLoadBalancer未实现，压测标记IP校验已添加 |
| 生产就绪 | **7.5/10** | 密钥加密说明已添加，CORS配置已存在 |
| **综合** | **7.5/10** | |

---

## 二、发现的问题及修复记录

### 🔴 P0 级（功能缺陷/安全漏洞）

#### P0-1: HMAC签名校验白名单逻辑漏洞 ✅ 已修复

**文件**: `HmacSignatureFilter.java`

**问题**: 如果没有 `X-Timestamp` Header，直接放行。攻击者只要不传 `X-Timestamp`，就完全绕过HMAC签名校验。

**修复方案**: 
1. 在 `AuthProperties` 中添加独立的 `hmacWhiteList` 配置
2. 修改 `HmacSignatureFilter` 逻辑：先检查路径是否在HMAC白名单中，白名单内放行，否则必须校验签名
3. 在 `application.yml` 中添加 `hmac-white-list` 配置项

**修复前**:
```java
String timestamp = request.getHeaders().getFirst("X-Timestamp");
if (!StringUtils.hasText(timestamp)) {
    return chain.filter(exchange);  // ⚠️ 漏洞：直接放行
}
```

**修复后**:
```java
// 检查是否在 HMAC 签名白名单中（公开接口，不需要签名校验）
if (isHmacWhiteListed(path)) {
    return chain.filter(exchange);
}
// 非白名单路径必须进行签名校验
String timestamp = request.getHeaders().getFirst("X-Timestamp");
if (!StringUtils.hasText(timestamp)) {
    return forbidden(exchange, "Missing required headers for HMAC verification");
}
```

---

#### P0-2: 测试接口暴露在生产环境中 ✅ 已修复

**文件**: `HomeController.java` → `FeedTestController.java`

**问题**: `/test/push-inbox` 和 `/test/push-outbox` 接口直接暴露，任何人可以向用户Feed注入垃圾内容。

**修复方案**:
1. 从 `HomeController` 中移除测试接口
2. 创建独立的 `FeedTestController`，使用 `@Profile("dev")` 限制只在开发环境生效
3. 生产环境使用 `--spring.profiles.active=prod` 启动时，测试接口不会被加载

**修复前**:
```java
// HomeController.java 中直接暴露
@PostMapping("/test/push-inbox")
public R<Void> testPushInbox(...) { ... }

@PostMapping("/test/push-outbox")
public R<Void> testPushOutbox(...) { ... }
```

**修复后**:
```java
// FeedTestController.java - 独立文件
@RestController
@RequestMapping("/api/home/test")
@Profile("dev")  // 仅开发环境可用
public class FeedTestController { ... }
```

---

#### P0-3: MDC透传实现有Bug ✅ 已修复

**文件**: `AggregatorThreadPoolConfig.java`

**问题**: `MdcAwareThreadFactory` 在 `newThread()` 时捕获MDC上下文，但这是**线程创建时**的上下文，不是**任务提交时**的上下文。线程池会复用线程，第一次创建线程时的MDC会被后续复用时一直使用，导致TraceId错误。

**修复方案**: 
1. 将 `MdcAwareThreadFactory` 改为 `MdcAwareExecutorService` 包装器
2. 在每次 `execute()` 时捕获当前线程的MDC上下文
3. 包装Runnable：执行前设置MDC，执行后清理MDC
4. 委托给底层 `ThreadPoolExecutor` 执行

**修复前**:
```java
static class MdcAwareThreadFactory implements ThreadFactory {
    public Thread newThread(Runnable r) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap(); // ← 线程创建时的上下文
        Runnable wrapped = () -> {
            MDC.setContextMap(contextMap); // ← 复用时仍然是旧上下文
            r.run();
        };
    }
}
```

**修复后**:
```java
static class MdcAwareExecutorService extends AbstractExecutorService {
    public void execute(Runnable command) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap(); // ← 任务提交时的上下文
        delegate.execute(() -> {
            MDC.setContextMap(contextMap); // ← 每次任务执行都是正确上下文
            try {
                command.run();
            } finally {
                MDC.clear();
            }
        });
    }
}
```

---

### 🟡 P1 级（重要优化）

#### P1-1: Feed游标精度丢失 ✅ 已修复

**文件**: `FeedService.java`

**问题**: `nextCursor` 使用 `minScore.longValue()` 截断浮点精度，未来如果score包含小数会导致分页跳过记录。

**修复方案**: 添加 `formatCursor()` 方法，整数值用long格式避免科学计数法，包含小数的score保留完整精度。

---

#### P1-3: Pipeline过度裁剪 ✅ 已修复

**文件**: `FeedPushConsumer.java`

**问题**: Pipeline中对每个粉丝都执行 `ZREMRANGEBYRANK`，大多数收件箱远未达到500条上限，裁剪操作是浪费的。

**修复方案**: 使用 Lua 脚本实现 ZADD + 条件裁剪 + EXPIRE，只在 ZADD 后 ZCARD > maxSize 时才裁剪。Lua 脚本保证原子性，且将3-4个Redis命令合并为1次网络往返。

```lua
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
local card = redis.call('ZCARD', KEYS[1])
if card > tonumber(ARGV[3]) then
  redis.call('ZREMRANGEBYRANK', KEYS[1], 0, card - tonumber(ARGV[3]) - 1)
end
redis.call('EXPIRE', KEYS[1], ARGV[4])
return card
```

---

#### P1-4: 大V标记缓存不一致 ✅ 已修复

**文件**: `FeedPushConsumer.java`

**问题**: 大V标记缓存1小时，如果用户在缓存期内粉丝数跨越阈值，缓存仍显示"非大V"，可能导致推模式给百万粉丝写入，造成Redis写入风暴。

**修复方案**: 缓存TTL从1小时缩短为10分钟，减少缓存不一致窗口。

---

#### P1-5: Sentinel限流规则不持久化 ✅ 已添加配置说明

**文件**: `RateLimitFilter.java` + `application.yml`

**问题**: 限流规则硬编码在 `@PostConstruct` 中，重启后丢失。

**修复方案**: 
1. 在 `application.yml` 中添加 Nacos 数据源配置模板（注释掉，说明如何启用）
2. 在 `RateLimitFilter` 的 `init()` 方法中添加 Nacos 数据源说明

---

#### P1-6: 密钥明文存储 ✅ 已添加安全警告

**文件**: `application.yml`

**问题**: JWT和HMAC密钥明文存储在配置文件中。

**修复方案**: 添加详细的安全警告注释，说明3种生产环境加密方案：Nacos加密配置、Spring Cloud Vault、K8s Secrets。

---

### 🟢 P2 级（代码优化）

#### P2-1: Feed清理SCAN无限速 ✅ 已修复

**文件**: `FeedCleanupJob.java`

**问题**: SCAN遍历所有Key后逐个执行 `ZREMRANGEBYSCORE`，Key数量大时持续占用Redis CPU。

**修复方案**: 每处理一个Key后休眠50ms，防止Redis CPU飙高。

---

#### P2-4: 压测标记可伪造 ✅ 已修复

**文件**: `TrafficColoringFilter.java`

**问题**: 客户端可以设置 `X-Pressure-Test: true` 伪造压测流量。

**修复方案**: 添加来源IP校验，只允许内网IP（10.0.0.0/8）设置压测标记，外部IP伪造时记录警告日志。

---

#### P2-5: 大V发件箱串行查询 ✅ 已修复

**文件**: `FeedService.java`

**问题**: 串行查询每个大V的发件箱，10个大V = 10次Redis网络往返。

**修复方案**: 使用 Pipeline 一次性查询所有大V的发件箱，1次网络往返替代N次。

---

## 三、技术亮点和面试价值评估

### ⭐⭐⭐⭐⭐ 最高价值（面试必问）

| # | 技术点 | 面试价值 |
|---|--------|----------|
| 1 | **推拉混合Feed模型** | Instagram/微博经典方案，考察系统设计能力 |
| 2 | **2层并行聚合 + 动态超时** | BFF层编排能力，考察并发编程和降级设计 |
| 3 | **HMAC签名 + 防重放 + 时序攻击防护** | 安全设计能力，三个维度缺一不可 |
| 4 | **MDC透传 Bug 及修复** | 线程池复用 vs ThreadLocal 的经典陷阱 |

### ⭐⭐⭐⭐ 高价值

| # | 技术点 | 面试价值 |
|---|--------|----------|
| 5 | **双层线程池隔离** | 嵌套CompletableFuture线程池饥饿问题 |
| 6 | **Lua脚本条件裁剪** | 原子操作 + 性能优化 |
| 7 | **流量染色6标记** | 全链路追踪设计 |
| 8 | **Sentinel限流策略** | 滑动窗口 vs 固定窗口 vs 令牌桶 |

---

## 四、面试话术（Q&A 格式）

### Q1: "Feed流推拉混合模型怎么设计？"

> 我们采用推拉混合模型：
> - **普通用户发笔记** → 推模式：遍历粉丝列表，批量ZADD到每个粉丝的收件箱。ZADD天然幂等
> - **大V发笔记** → 拉模式：只写入自己的发件箱，粉丝读取Feed时实时拉取
> - **阈值判断**：粉丝数 ≥ 10万走拉模式，Redis缓存10分钟（缩短不一致窗口）
>
> 读Feed流程：收件箱 ZREVRANGEBYSCORE + 大V发件箱 Pipeline批量拉取 → 合并排序 → 游标分页
>
> 清理机制：XXL-Job定时任务每天凌晨3点 SCAN + ZREMRANGEBYSCORE，每Key限速50ms

### Q2: "BFF层并行聚合怎么设计超时和降级？"

> 2层并行编排 + 动态超时：
> - 第1层：笔记详情 + 社交状态 + 计数，3秒超时
> - 第2层：动态计算超时 = 全局4秒 - 第1层已用时间（至少500ms）
>
> 降级策略：下游超时 → `getNow(默认值)` 返回降级数据
>
> 线程池隔离：外层 `aggregatorPool` + 内层 `batchFeignPool`，避免嵌套CompletableFuture饥饿

### Q3: "网关HMAC签名校验怎么实现？"

> 三重校验：
> 1. **timestamp有效期**：请求时间戳与当前时间差 > 5分钟拒绝
> 2. **nonce唯一性**：Lua脚本 `SET NX EX 300`，5分钟内相同nonce拒绝
> 3. **签名校验**：`HmacSHA256(secretKey, method + path + timestamp + nonce)`，`MessageDigest.isEqual`常量时间比较防时序攻击
>
> 安全原则：HMAC白名单和JWT白名单独立配置，公开接口白名单放行，写接口必须签名

### Q4: "线程池MDC透传有什么坑？"

> **经典Bug**：在 `ThreadFactory.newThread()` 中捕获MDC上下文，线程池复用时MDC错误。
>
> **根因**：`newThread()` 只在线程创建时调用一次，线程池复用线程时不会重新调用。所以第2个任务获取的是第1个任务提交时的MDC。
>
> **修复**：改为 `ExecutorService` 包装器，在每次 `execute()` 时捕获MDC上下文并包装Runnable。

### Q5: "Feed推送如何优化Redis操作？"

> 三个优化点：
> 1. **Lua脚本条件裁剪**：ZADD后只在ZCARD > maxSize时才ZREMRANGEBYRANK，避免大多数收件箱的无条件裁剪
> 2. **大V发件箱Pipeline查询**：1次网络往返替代N次串行查询
> 3. **大V标记缓存缩短**：从1小时缩短为10分钟，防止缓存不一致导致写扩散风暴

---

## 五、修复前后代码对比

详见各问题的修复前后代码片段（已在第二节中标注 ✅ 已修复）。

---

## 六、深度技术分析

### 6.1 推拉混合模型的原子性边界

**推模式写扩散**：
- ZADD天然幂等 → 重复消费MQ消息不会产生重复Feed
- Lua脚本 ZADD + ZCARD + 条件裁剪 → 原子操作，避免并发写入时裁剪不一致
- 粉丝列表从 Redis ZSet 分页遍历 → 避免一次性加载OOM

**拉模式读扩散**：
- Pipeline批量查询大V发件箱 → 1次网络往返，但结果需要在BFF层合并排序
- 合并排序的内存开销：假设20条笔记 + 10个大V × 每人2条 = 40条，可控
- 游标分页：`lastScore - 0.001` 避免重复，`0.001` 是可接受的精度损失

### 6.2 网关过滤器链的一致性保证

**过滤器顺序设计**：
1. RequestLogFilter（100）：最早生成TraceId
2. GatewayAuthFilter（1000）：JWT鉴权必须在HMAC之前（先识别用户，再校验签名）
3. TrafficColoringFilter（1200）：染色必须在限流之前（限流可能需要压测标记）
4. HmacSignatureFilter（1500）：签名校验必须在限流之前（限流前先验证请求合法性）
5. RateLimitFilter（2500）：限流在最后（已确认请求合法才限流）

### 6.3 分布式考量

**XXL-Job Feed清理任务**：
- 已使用XXL-Job分布式调度，保证只有一个实例执行
- 但如果XXL-Job不可用，任务不会执行，收件箱会持续膨胀
- 建议：添加收件箱容量监控告警（ZCARD超过1000时告警）

**Sentinel限流规则**：
- 当前单机限流，多实例部署时实际QPS = 单机阈值 × 实例数
- 生产环境需接入 Sentinel Token Server 实现集群限流
- Nacos数据源持久化：配置模板已添加，取消注释即可启用

**大V标记缓存**：
- 10分钟TTL，多实例间通过Redis共享缓存，一致性OK
- 极端情况：Redis故障恢复后缓存丢失，所有用户查ZCARD → 风暴
- 建议：大V标记使用本地Caffeine缓存 + Redis二级缓存，降低Redis依赖
