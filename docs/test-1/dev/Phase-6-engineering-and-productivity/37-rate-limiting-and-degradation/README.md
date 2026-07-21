# 限流降级方案

> 所属维度：分布式方案 | 开发阶段：Phase-6 | 核心组件：Sentinel + Redis Lua

---

## 🎯 一、为什么需要限流

> 📖 **知识来源**：《高并发系统：设计原理与实践》第5章 — 限流设计
> - "限流不是简单地拒绝请求，而是保护系统在可承受范围内运行"
> - "固定窗口有临界突刺问题，滑动窗口更平滑"
> - "单机限流不够，网关+业务服务双层限流"

---

## 📋 二、四种限流算法对比

| 算法 | 原理 | 优点 | 缺点 | my-xhs采用 |
|------|------|------|------|-------------|
| 固定窗口 | 1分钟内不超过N次 | 简单 | 临界点突发流量(0:59和1:01各N次=2N次) | ❌ |
| 滑动窗口 | 窗口平滑滑动 | 解决临界问题 | 实现复杂 | ✅ Redis Lua实现 |
| 漏桶 | 固定速率流出 | 流量平滑 | 无法应对突发 | ❌ |
| 令牌桶 | 固定速率生成令牌 | 允许适度突发 | — | ✅ Sentinel采用 |

---

## 🏗️ 三、my-xhs 限流分层

| 层级 | 限流方式 | 场景 | 实现 |
|------|----------|------|------|
| 网关层 | IP级滑动窗口 | 同一IP 1秒100次 | Redis Lua |
| 服务层 | 接口级令牌桶 | 下单接口QPS限5000 | Sentinel |
| 参数级 | 热点参数限流 | 商品详情按SKU ID限流 | Sentinel热点参数 |
| 注解级 | @RateLimit | 同一用户1分钟点赞10次 | AOP + Redis Lua |

---

## 💻 四、核心实现

### 4.1 Redis Lua 滑动窗口

```lua
-- 滑动窗口限流Lua脚本
-- KEYS[1] = 限流Key
-- ARGV[1] = 窗口大小(毫秒)
-- ARGV[2] = 最大请求数
-- ARGV[3] = 当前时间戳
-- ARGV[4] = 唯一请求ID

-- 移除窗口外的请求
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[3] - ARGV[1])

-- 获取当前窗口请求数
local count = redis.call('ZCARD', KEYS[1])

if count < tonumber(ARGV[2]) then
    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[4])
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
    return 1  -- 允许
else
    return 0  -- 拒绝
end
```

### 4.2 线程池隔离 vs 信号量隔离

> 📖 **知识来源**：《高并发系统：设计原理与实践》第6章 — 服务隔离策略
> - "线程池隔离：为每个依赖服务分配独立线程池，避免依赖服务拖垮整个应用"
> - "信号量隔离：使用信号量（计数器）限制对依赖服务的并发调用数，轻量级"
> - "Hystrix 默认线程池隔离，Sentinel 默认信号量隔离"
> 
> **参考来源**：huazai-ecshop 项目中的 Sentinel 服务治理设计
> **自己的思考**：需要加入自己的思考和创新，不能照搬
> - 参考点：线程池隔离适合 IO 密集型操作，信号量隔离适合 CPU 密集型操作
> - 创新点：可以考虑混合隔离策略（核心服务用线程池，非核心用信号量）

| 隔离方式 | 原理 | 优点 | 缺点 | my-xhs 采用 |
|---------|------|------|------|-------------|
| 线程池隔离 | 每个依赖服务独立线程池 | 真正隔离，避免雪崩 | 线程开销大，上下文切换 | ❌ |
| 信号量隔离 | 计数器限制并发数 | 轻量级，无线程开销 | 不隔离线程，可能阻塞 | ✅ Sentinel 默认 |

```java
// Sentinel 信号量隔离示例
@SentinelResource(value = "getUser", 
                blockHandler = "handleBlock",
                fallback = "handleFallback")
public User getUser(Long userId) {
    // 信号量隔离，默认最多 10 个并发线程
    return userService.getById(userId);
}

// Hystrix 线程池隔离示例（参考）
@HystrixCommand(groupKey = "userGroup",
                commandKey = "getUser",
                threadPoolKey = "userThreadPool",
                threadPoolProperties = {
                    @HystrixProperty(name = "coreSize", value = "10"),
                    @HystrixProperty(name = "maxQueueSize", value = "20")
                })
public User getUser(Long userId) {
    return userService.getById(userId);
}
```

#### 💡 面试考察点

**Q：线程池隔离和信号量隔离有什么区别？你们用的是哪种？**

推荐回答思路：

> 1. "线程池隔离：每个依赖服务独立线程池，真正隔离但开销大"
> 2. "信号量隔离：计数器限制并发数，轻量级但不隔离线程"
> 3. "Hystrix 默认线程池隔离，Sentinel 默认信号量隔离"
> 4. "我们用的 Sentinel 信号量隔离——轻量级，适合大部分场景"

---

### 4.3 @RateLimit 注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int count() default 10;       // 窗口内最大请求数
    int window() default 60;      // 窗口大小(秒)
    String key() default "";      // 限流Key(支持SpEL)
    String message() default "操作过于频繁";
}

// 使用示例
@RateLimit(count = 10, window = 60, key = "#userId")
public void like(Long userId, Long noteId) {
    // 同一用户1分钟最多点赞10次
}
```

### 4.3 Sentinel 熔断降级

```java
// 自定义降级响应
@Component
public class SentinelBlockHandler implements BlockExceptionHandler {
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, 
                       BlockException e) throws Exception {
        R<?> result;
        if (e instanceof FlowException) {
            result = R.fail(429, "请求过于频繁，请稍后重试");
        } else if (e instanceof DegradeException) {
            result = R.fail(503, "服务暂时不可用，请稍后重试");
        } else {
            result = R.fail(429, "系统繁忙");
        }
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(result));
    }
}
```

---

## ⚖️ 五、方案对比

| 维度 | Sentinel | Hystrix | Resilience4j |
|------|---------|---------|-------------|
| 维护状态 | ✅ 活跃 | ❌ 停更 | ✅ 活跃 |
| Dashboard | ✅ 可视化 | ✅ | ❌ |
| 热点参数限流 | ✅ | ❌ | ❌ |
| Spring Cloud集成 | ✅ Alibaba | ✅ Netflix | ✅ |
| 规则持久化 | Nacos/ZK | — | — |

**最终选择**：Sentinel — Spring Cloud Alibaba全家桶、Dashboard可视化、热点参数限流独有。

---

## 🎤 六、面试考察点

### Q1: 限流算法知道哪些？你们用的是哪种？

**推荐回答思路**：

> 1. "4种：固定窗口(临界突刺)、滑动窗口(平滑)、漏桶(匀速)、令牌桶(允许突发)"
> 2. "网关层用滑动窗口(Redis Lua)——按IP限流，精确无临界问题"
> 3. "服务层用Sentinel令牌桶——按接口/参数限流，允许合理突发"

### Q2: 限流和熔断有什么区别？

**推荐回答思路**：

> 1. "限流是**主动防御**——超过阈值直接拒绝，保护自己"
> 2. "熔断是**被动保护**——下游服务异常时断开调用，保护自己不被拖死"
> 3. "限流保护入口，熔断保护出口，两者互补"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📖 《高并发系统：设计原理与实践》 | 第5章 | 限流算法原理 |
| 📄 03-分布式解决方案.md | §7 | 4种算法对比+分层限流+Lua实现 |
| 📄 02-模块详细设计.md | §1.2 | @RateLimit注解设计 |
