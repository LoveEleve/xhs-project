# 05 — 全链路流量染色

> **目标读者**：P7+ 工程师，需要理解 6 个染色标记的设计依据、HTTP → Feign → RocketMQ 三通道透传、ThreadLocal 跨线程传递。
> **回答三个问题**：为什么需要 6 个标记？跨进程怎么透传？跨线程怎么传递？

---

## 一、为什么需要流量染色？

在一个请求经过多个微服务时，需要一种机制让下游服务知道这个请求的"身份"。这就是流量染色。

### 1.1 染色标记全景

| 标记 | Header | 用途 | 谁设置 |
|------|--------|------|--------|
| **traceId** | `X-Trace-Id` | 全链路追踪，串联所有日志 | Gateway 自动生成 |
| **userId** | `X-User-Id` | 当前用户身份 | Gateway 鉴权后注入 |
| **grayTag** | `X-Gray-Tag` | 灰度路由：`beta`/`stable` | 客户端指定，默认 `stable` |
| **apiVersion** | `X-Api-Version` | API 版本：`v1`/`v2` | 客户端指定，默认 `v1` |
| **abGroup** | `X-AB-Group` | AB 测试分组：`A`/`B`/`C` | Gateway 根据 userId hash 自动分组 |
| **pressureTest** | `X-Pressure-Test` | 压测标记 | 仅内网可设为 `true` |

### 1.2 为什么是这 6 个？

| 标记 | 必要性 | 如果缺失 |
|------|--------|---------|
| traceId | **必须** | 无法追踪请求链路，日志孤立 |
| userId | **必须** | 下游服务不知道谁在操作 |
| grayTag | 灰度发布需要 | 无法实现金丝雀发布 |
| apiVersion | 多版本 API 需要 | 无法路由到正确的 API 版本 |
| abGroup | 实验需要 | 无法做 AB 测试 |
| pressureTest | 压测需要 | 压测流量污染生产数据 |

---

## 二、三通道透传架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Gateway: TrafficColoringFilter            │
│  注入/补齐 6 个染色标记到 HTTP Header                         │
└──────────────────────┬──────────────────────────────────────┘
                       │ HTTP Header
                       ▼
┌─────────────────────────────────────────────────────────────┐
│  下游服务: TraceIdConfig.TraceContextInterceptor (preHandle) │
│  HTTP Header → TraceContext (ThreadLocal)                   │
│  TraceId → MDC                                              │
└──────────┬──────────────────┬───────────────────┬───────────┘
           │                  │                   │
           ▼                  ▼                   ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐
    │ Feign 调用    │  │ MQ 发送       │  │ @Async 异步      │
    │ (通道 1)     │  │ (通道 2)      │  │ (通道 3)         │
    └──────────────┘  └──────────────┘  └──────────────────┘
           │                  │                   │
    FeignTrace        MqTraceHelper      AsyncConfig
    Interceptor       .wrapWithTrace     .TaskDecorator
    Config            Context()          .snapshot()
           │                  │                   │
           ▼                  ▼                   ▼
    HTTP Header         MQ UserProperty    深拷贝 TraceContext
    → 下游服务          → Consumer 恢复      → 子线程恢复
```

### 2.1 通道 1：HTTP Header → Feign → HTTP Header

```
请求进入 Service A
  │
  ├── TraceContextInterceptor.preHandle()
  │     HTTP Header "X-Trace-Id: abc123" → TraceContextHolder.set(ctx)
  │
  ├── Service A 调用 Feign → Service B
  │     │
  │     └── FeignTraceInterceptor.apply()
  │           TraceContextHolder.get() → RequestTemplate.header("X-Trace-Id", "abc123")
  │
  └── Service B 收到请求
        └── TraceContextInterceptor.preHandle()
              HTTP Header "X-Trace-Id: abc123" → TraceContextHolder.set(ctx)
```

**FeignTraceInterceptor 实现**（`FeignTraceInterceptorConfig.java:36-50`）：
```java
@Bean
public RequestInterceptor traceIdFeignInterceptor() {
    return template -> {
        TraceContext ctx = TraceContextHolder.get();
        if (ctx == null) return;  // 定时任务等场景没有上下文
        setHeaderIfPresent(template, TRACE_ID_HEADER, ctx.getTraceId());
        setHeaderIfPresent(template, USER_ID_HEADER, ctx.getUserId());
        // ... 其余 4 个标记
    };
}
```

### 2.2 通道 2：HTTP Header → MQ → Consumer

```
Service A 发送 MQ 消息
  │
  └── MqTraceHelper.wrapWithTraceContext(message)
        TraceContextHolder.get() → message.putUserProperty("X-Trace-Id", "abc123")
        → RocketMQ Broker
              │
              ▼
        Consumer 收到消息
        └── MqTraceHelper.restoreTraceContext(messageExt)
              messageExt.getUserProperty("X-Trace-Id") → TraceContextHolder.set(ctx)
              → MDC.put("traceId", "abc123")
              → 执行业务逻辑
              → finally: clearTraceContext()
```

**MQ 透传的兜底策略**（`MqTraceHelper.java:57-78`）：
```
优先级:
1. TraceContextHolder.get()  → 完整上下文，透传全部 6 个标记
2. MDC.get("traceId")        → 仅透传 traceId（定时任务场景）
3. 都不存在                   → 原样返回消息，不透传
```

### 2.3 通道 3：HTTP Request → @Async 子线程

```
主线程 (Tomcat-1)
  │
  ├── TraceContextHolder: {traceId: "abc123", userId: "456"}
  ├── MDC: {traceId: "abc123"}
  │
  └── @Async 调用
        │
        └── AsyncConfig.TraceContextTaskDecorator.decorate()
              │
              ├── 快照:
              │     mdcContext = MDC.getCopyOfContextMap()    // Map 拷贝
              │     traceContext = TraceContextHolder.snapshot() // 深拷贝对象
              │
              └── 子线程 (async-1):
                    ├── MDC.setContextMap(mdcContext)          // 恢复 MDC
                    ├── TraceContextHolder.set(traceContext)   // 恢复上下文
                    ├── 执行业务逻辑
                    └── finally:
                          ├── MDC.clear()
                          └── TraceContextHolder.clear()
```

---

## 三、压测流量隔离：ShadowTableInterceptor

### 3.1 设计目标

压测流量不能污染生产数据，但又要使用生产环境的真实数据。影子表方案：压测流量写入 `t_order_shadow`，生产流量写入 `t_order`。

### 3.2 工作流程

```
请求到达
  │
  ├── Gateway: TrafficColoringFilter
  │     检查 X-Pressure-Test 是否为 "true"
  │     且来源 IP 以 10. 开头（内网）
  │     → 设置 pressureTest = true
  │
  ├── 下游服务: TraceContextHolder.isPressureTest() = true
  │
  └── MyBatis 执行 SQL
        │
        └── ShadowTableInterceptor.intercept()
              │
              ├── 非压测流量 → 放行，原 SQL 执行
              │
              └── 压测流量 → 重写 SQL
                    INSERT INTO t_order (...) → INSERT INTO t_order_shadow (...)
                    SELECT * FROM t_order → SELECT * FROM t_order_shadow
```

### 3.3 安全控制

| 控制点 | 说明 |
|--------|------|
| IP 白名单 | 仅 `10.x.x.x` 内网 IP 可设置压测标记 |
| 外部伪造防护 | 外部 IP 伪造 `X-Pressure-Test: true` 会被 Gateway 拒绝 |
| 默认关闭 | `@ConditionalOnProperty(myxhs.shadow.enabled=true)`，生产未配置则不生效 |

### 3.4 影子表要求

```
1. 表结构完全一致（包括索引）
2. 表名 = 生产表名 + "_shadow"
3. 压测结束后 TRUNCATE 清理
```

---

## 四、SkyWalking 手动 Span：BizSpanHelper

### 4.1 为什么需要手动 Span？

SkyWalking Agent 自动埋点覆盖了 HTTP、Feign、MQ 等通用场景，但无法覆盖**业务语义**的 Span：

```
自动 Span:
  GET /api/orders/create → 只知道是一个 HTTP 请求

手动 Span:
  createOrder → preDeductInventory → useCoupon → createPayment
  每个 Span 带业务 Tag: orderId=123, skuId=456, couponId=789
```

### 4.2 实现方式

**反射调用，零编译期依赖**（`BizSpanHelper.java:72-136`）：

```java
// 静态初始化：检测 SkyWalking Agent 是否加载
private static final boolean SKYWALKING_AVAILABLE;
static {
    boolean available = false;
    try {
        Class.forName("org.apache.skywalking.apm.toolkit.trace.TraceContext");
        available = true;
    } catch (ClassNotFoundException e) { /* 本地开发环境 */ }
    SKYWALKING_AVAILABLE = available;
}

// 使用方式：
BizSpanHelper.trace("inventory.preDeduct", () -> {
    inventoryService.preDeduct(request);
}, "skuId", "12345", "qty", "10");
```

**为什么用反射？** 避免 SkyWalking JAR 的编译期强依赖。本地开发环境没有 SkyWalking Agent 时，`BizSpanHelper.trace()` 直接执行 runnable，不创建 Span。

---

## 五、数据模型

### 5.1 TraceContext

```java
@Data
public class TraceContext {
    private String traceId;       // 全链路追踪 ID
    private String userId;        // 用户 ID
    private String grayTag;       // 灰度标记
    private String apiVersion;    // API 版本
    private String abGroup;       // AB 测试分组
    private String pressureTest;  // 压测标记
}
```

### 5.2 TraceContextHolder

```java
public class TraceContextHolder {
    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    public static void set(TraceContext context) { CONTEXT.set(context); }
    public static TraceContext get() { return CONTEXT.get(); }
    public static void clear() { CONTEXT.remove(); }

    // 深拷贝：跨线程传递用
    public static TraceContext snapshot() {
        TraceContext ctx = CONTEXT.get();
        if (ctx == null) return null;
        TraceContext copy = new TraceContext();
        copy.setTraceId(ctx.getTraceId());
        // ... 逐字段拷贝
        return copy;
    }

    // 便捷方法（null-safe）
    public static boolean isPressureTest() { ... }
    public static String getGrayTag() { ... }
}
```

---

## 六、AB 分组算法

```java
// TrafficColoringFilter.java:129-140
int hash = (userId.hashCode() & 0x7FFFFFFF) % 3;
// 结果: 0 → A 组, 1 → B 组, 2 → C 组
```

| 设计点 | 说明 |
|--------|------|
| `& 0x7FFFFFFF` | 代替 `Math.abs()`，避免 `Integer.MIN_VALUE` 溢出 |
| 取模 3 | 三组均匀分布 |
| 未登录用户 | 默认分到 A 组 |
| 固定分组 | 同一用户永远在同一组（一致性哈希思想） |

---

## 七、内存泄漏防护

ThreadLocal 使用不当会导致内存泄漏。MyXHS 的三层防护：

| 场景 | 清理时机 | 代码位置 |
|------|---------|---------|
| HTTP 请求结束 | `afterCompletion` 中 `TraceContextHolder.clear()` | `TraceIdConfig.java:96` |
| MQ 消费完成 | `finally` 中 `clearTraceContext()` | `MqTraceHelper.java:138` |
| @Async 子线程结束 | `TaskDecorator` 的 `finally` 中清理 | `AsyncConfig.java:84-87` |

---

## 八、代价与局限

| 局限 | 影响 | 改进方向 |
|------|------|------|
| **ThreadLocal 在 Reactor 中失效** | WebFlux 响应式编程中 ThreadLocal 不适用 | Gateway 使用了 WebFlux，染色通过 Exchange attributes 传递 |
| **MQ 消息 header 大小** | 6 个 Header 增加消息体积 | 开销可忽略（< 200 bytes） |
| **SkyWalking 反射调用性能** | 每次调用都走反射 | 缓存 Method 对象，性能影响 < 0.1ms |
| **影子表非自动创建** | 需要手动创建和维护影子表 | 可考虑启动时自动 DDL |
| **AB 分组基于 userId hash** | 新增用户会改变分组分布 | 比例稳定，影响可忽略 |

---

> **下一篇**：`06-cache-strategy.md` — 缓存体系：Cache Aside 模式、三层一致性保障、Caffeine + Redis 多级缓存、布隆过滤器、缓存预热
