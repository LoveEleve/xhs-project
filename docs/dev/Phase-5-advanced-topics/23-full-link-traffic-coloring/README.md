# 全链路流量染色

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

---

## 🎯 一、问题场景

### 1.1 为什么需要流量染色？

```
问题：压测流量写入了生产数据库，导致真实订单数据被污染
原因：压测请求和正常请求走同一条链路，无法区分

流量染色 = 请求头标记 + 全链路透传 + 数据隔离
一次标记，全程有效，压测数据写影子表，不污染生产
```

### 1.2 染色标记定义

| 标记 | 说明 | 用途 |
|------|------|------|
| `X-Trace-Id` | 全链路追踪 ID | 串联 15 个服务的日志 |
| `X-User-Id` | 用户 ID | 下游服务获取当前用户 |
| `X-Gray-Tag` | 灰度标记(beta/stable) | 灰度发布路由 |
| `X-Api-Version` | API 版本号(v1/v2) | 多版本 API 路由 |
| `X-AB-Group` | AB 测试分组(A/B/C) | 推荐策略/UI 实验 |
| `X-Pressure-Test` | 压测标记(true/false) | 压测流量隔离写影子表 |

---

## 🏗️ 二、全链路透传架构

```
Gateway染色 → HTTP Header透传 → Feign透传 → MQ透传 → 异步任务透传
    ↓              ↓                ↓           ↓            ↓
X-Trace-Id    X-Gray-Tag      X-AB-Group   X-Pressure   ThreadLocal
X-User-Id     X-Api-Version
```

### 2.1 四层透传机制

| 层级 | 实现 | 说明 |
|------|------|------|
| Gateway 入口 | TrafficDyeingFilter | 注入 6 个 Header |
| Feign 同步调用 | TraceFeignRequestInterceptor | 自动传播 Header 到下游 |
| MQ 异步消息 | TraceMessagePostProcessor | Message Property 携带标记 |
| 线程池异步 | TraceTaskDecorator | TaskDecorator 继承 ThreadLocal |

### 2.2 数据隔离（影子表）

```
X-Pressure-Test=true 时：
  ShardingSphere Hint → 路由到 t_order_shadow 表
  正常请求 → t_order 表

影子表结构与生产表完全一致，只是表名加 _shadow 后缀
```

---

## 💻 三、核心代码实现

### 3.1 TraceContext（ThreadLocal 上下文）

```java
public class TraceContext {
    private String traceId;
    private String userId;
    private String grayTag;
    private String pressureTest;
    private String abGroup;
}

public class TraceContextHolder {
    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    public static void set(TraceContext ctx) { CONTEXT.set(ctx); }
    public static TraceContext get() { return CONTEXT.get(); }
    public static void clear() { CONTEXT.remove(); }

    public static boolean isPressureTest() {
        TraceContext ctx = get();
        return ctx != null && "true".equals(ctx.getPressureTest());
    }
}
```

### 3.2 Feign 透传拦截器

```java
@Component
public class TraceFeignRequestInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        TraceContext ctx = TraceContextHolder.get();
        if (ctx != null) {
            template.header("X-Trace-Id", ctx.getTraceId());
            template.header("X-User-Id", ctx.getUserId());
            template.header("X-Gray-Tag", ctx.getGrayTag());
            template.header("X-Pressure-Test", ctx.getPressureTest());
            template.header("X-AB-Group", ctx.getAbGroup());
        }
    }
}
```

### 3.3 MQ 透传

```java
// 发送时：注入染色标记到 Message Property
public class TraceMessagePostProcessor implements MessagePostProcessor {
    @Override
    public org.springframework.messaging.Message<?> postProcessMessage(Message<?> message) {
        TraceContext ctx = TraceContextHolder.get();
        if (ctx != null) {
            return MessageBuilder.fromMessage(message)
                .setHeader("X-Trace-Id", ctx.getTraceId())
                .setHeader("X-Pressure-Test", ctx.getPressureTest())
                .build();
        }
        return message;
    }
}

// 消费时：从 Message Property 恢复到 ThreadLocal
public abstract class BaseTraceConsumer {
    protected void restoreTraceContext(MessageExt msg) {
        TraceContext ctx = new TraceContext();
        ctx.setTraceId(msg.getUserProperty("X-Trace-Id"));
        ctx.setPressureTest(msg.getUserProperty("X-Pressure-Test"));
        TraceContextHolder.set(ctx);
    }
}
```

### 3.4 线程池透传

```java
public class TraceTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        TraceContext ctx = TraceContextHolder.get(); // 主线程获取
        return () -> {
            TraceContextHolder.set(ctx); // 子线程恢复
            try {
                runnable.run();
            } finally {
                TraceContextHolder.clear();
            }
        };
    }
}
```

---

## ⚖️ 四、方案对比

| 维度 | Header 透传（✅ 选定） | 独立压测环境 | 流量录制回放 |
|------|----------------------|------------|------------|
| 成本 | 低（复用生产环境） | 高（独立集群） | 中 |
| 真实性 | 高（真实链路） | 中（环境差异） | 高 |
| 数据隔离 | 影子表 | 天然隔离 | 不需要 |
| 复杂度 | 中 | 低 | 中 |

---

## 🐛 五、踩坑记录

### 5.1 线程池丢失 ThreadLocal

- **现象**：异步任务中 TraceContext 为 null
- **解决**：线程池配置 TraceTaskDecorator，主线程 ThreadLocal 传递到子线程

### 5.2 影子表忘记建索引

- **现象**：压测时影子表查询极慢
- **解决**：影子表结构必须与生产表完全一致（包括索引）

---

## 🎤 六、面试考察点

### Q1: 全链路流量染色怎么实现的？

> 1. "Gateway 入口注入 6 个 Header（TraceId/UserId/GrayTag/PressureTest/ABGroup/ApiVersion）"
> 2. "同步透传：Feign RequestInterceptor 自动传播 Header"
> 3. "异步透传：MQ Message Property + 线程池 TaskDecorator"
> 4. "数据隔离：X-Pressure-Test=true 时 ShardingSphere Hint 路由到影子表"

### Q2: 压测流量怎么保证不污染生产数据？

> 1. "影子表：压测数据写 t_order_shadow，生产数据写 t_order"
> 2. "ShardingSphere Hint 路由：根据 ThreadLocal 中的压测标记决定路由"
> 3. "压测结束后 TRUNCATE 影子表，不影响生产"

---

## 📚 参考资料

| 资料 | 参考内容 |
|------|----------|
| 📄 phase-5/README.md §3.23 | 全链路流量染色完整设计 |
| 📄 02-module-detailed-design.md §2.5 | 流量染色架构 + 6 个 Header |
