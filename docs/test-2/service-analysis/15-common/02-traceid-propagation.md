# Common TraceId 全链路传播 — 深度技术分析

> 关联源码：`TraceContext.java` / `TraceContextHolder.java` / `TraceIdConfig.java` / `FeignTraceInterceptorConfig.java` / `MqTraceHelper.java` / `AsyncConfig.java`

---

## 业务背景

微服务架构下，一个用户请求经过 Gateway → Home BFF → Content/Analytics 等多个服务。问题：**如何把一次请求的所有日志串联起来？**

```
用户请求 → Gateway → Home → Content → Analytics
    ↑                                          ↑
  traceId=abc                               traceId=abc（同一 ID）
```

没有 TraceId 全链路传播的后果：
- 排查问题需要逐台机器手动 grep traceId
- 无法区分"服务超时"和"请求未到达"
- AB 测试分组不跨服务传递，用户侧和推荐侧分组不一致

---

## 6 字段上下文

```java
@Data
public class TraceContext {
    String traceId;      // 全链路追踪 ID
    String userId;       // 用户 ID
    String grayTag;      // 灰度标记 beta/stable
    String apiVersion;   // API 版本 v1/v2
    String abGroup;      // AB 分组 A/B/C
    String pressureTest; // 压测标记 true/false
}
```

traceId 是必填的，其他 5 个染色标记是可选。Gateway 注入后沿途传播。

---

## 传播路径

### 1. HTTP 请求入口（TraceIdConfig）

```
Gateway TrafficColoringFilter → 注入 Header
    ↓
下游服务 TraceIdConfig 拦截器（Order=-100）
    ↓
从 Header 恢复 6 字段到 TraceContextHolder
    ↓
MDC.put("traceId", traceId) → 日志自动携带
    ↓
afterCompletion → 清理 ThreadLocal + MDC
```

**优先级最高（Order=-100）**，确保在其他拦截器之前执行，所有业务代码都能读到 TraceContext。

### 2. Feign 调用（FeignTraceInterceptorConfig）

```java
template.header("X-Trace-Id", ctx.getTraceId());
template.header("X-Gray-Tag", ctx.getGrayTag());
// ... 6 个字段全部透传
```

**关键设计**：从 `TraceContextHolder`（ThreadLocal）读取，而非从 `HttpServletRequest`。因为 MQ 消费者触发的 Feign 调用没有 HTTP 请求，但有 TraceContext。

### 3. MQ 消息（MqTraceHelper）

```
Producer：
  wrapWithTraceContext() → 6 字段写入消息 Header
    ↓
RocketMQ Broker
    ↓
Consumer：
  restoreTraceContext() → 从 Header 恢复到 TraceContextHolder
  → finally → clearTraceId()
```

MQ 消费者恢复 traceId 后，该消费者触发的 Feign 调用也能正确透传。

### 4. 异步线程（AsyncConfig TaskDecorator）

```java
@Override
public void execute(Runnable command) {
    Map<String, String> ctxMap = MDC.getCopyOfContextMap();
    TraceContext snapshot = TraceContextHolder.snapshot();
    delegate.execute(() -> {
        // 任务执行前恢复
        if (snapshot != null) TraceContextHolder.set(snapshot);
        if (ctxMap != null) MDC.setContextMap(ctxMap);
        try {
            command.run();
        } finally {
            // 任务结束后清理
            TraceContextHolder.clear();
            MDC.clear();
        }
    });
}
```

```@Async``` / ```CompletableFuture``` 场景下，子线程自动继承父线程的 TraceContext。

---

## TraceId 生成

```java
private String generateTraceId() {
    return UUID.randomUUID().toString().replace("-", "");
}
```

32 位无横线 UUID。Gateway 优先透传上游的 traceId（如客户端携带的 X-Trace-Id），没有则自动生成。

---

## SkyWalking 关联

```java
// 通过反射调用 SkyWalking API（避免编译期依赖 apm-toolkit-trace）
Class<?> swContext = Class.forName("org.apache.skywalking.apm.toolkit.trace.TraceContext");
Method putCorrelation = swContext.getMethod("putCorrelation", String.class, String.class);
putCorrelation.invoke(null, "traceId", ctx.getTraceId());
```

业务 traceId 注入到 SkyWalking Span 的 Correlation Context。在 SkyWalking UI 中：
- 按业务 traceId 搜索 Span
- 按 SkyWalking traceId 搜索日志（ES）

双向打通：业务 ID ↔ 调用链。

---

## 面试 Q&A

**Q: 为什么 Feign 拦截器从 TraceContextHolder 读取，不从 HttpServletRequest？**
A: 因为 MQ 消费者没有 HTTP 请求。消费者恢复 traceId 后可能调用 Feign，此时只有 TraceContextHolder 有数据。

**Q: 异步线程怎么传递 TraceContext？**
A: 自定义 TaskDecorator 包装线程池。在 execute() 时 snapshot 当前上下文的深拷贝，在 run() 前恢复，finally 中清理。深拷贝是因为 ThreadLocal 引用传递时，父线程清理后子线程拿到 null。

**Q: 6 个字段中哪些必须在所有服务间透传？**
A: traceId 必须，其余可选。但建议全部透传，否则 AB 分组到下游就断了。

---

## 生产实验

HTTP 请求链路验证：

```
请求 → Gateway (注入 traceId)
     → Home BFF (从 Header 恢复，记录 ACCESS 日志带 traceId)
     → Content Feign (透传 traceId)
     → Content 服务 (从 Header 恢复，记录 ACCESS 日志带同一 traceId)
```

ES 验证：`q=traceId:"..."` → 同一次请求的所有日志，命中。

---

## 发散

### OpenTelemetry 迁移

当前 TraceId 是自研方案。后续可迁移到 OpenTelemetry：
- OTel 的 SpanContext 天然跨服务传播
- 集成 OTel Java Agent 后自动拦截 HTTP/MQ/DB 调用
- 业务 traceId 作为 Span 的 Tag 附加

### 染色标记治理

6 个染色标记维护成本高。后续可考虑统一用 JSON 字符串传递：
```json
X-Trace-Context: {"traceId":"...","gray":"beta","ab":"A","pt":"true"}
```
减少 Header 数量，也方便新增标记。
