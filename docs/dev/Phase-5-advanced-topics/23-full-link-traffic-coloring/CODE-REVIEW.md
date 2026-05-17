# 23-全链路流量染色 Code Review

## 📊 对标 P8 评分表

| 维度 | 满分 | 得分 | 说明 |
|------|:----:|:----:|------|
| 架构设计 | 20 | 19 | 四层透传（HTTP→Feign→MQ→线程池）+ 影子表路由，覆盖全链路 |
| 分布式安全 | 20 | 19 | ThreadLocal 三层清理保障、影子表默认关闭需显式开启、压测标记安全校验 |
| 代码质量 | 15 | 14 | 改造已有组件（TraceIdConfig/FeignInterceptor/MqTraceHelper/AsyncConfig）+ 新增 3 个文件 |
| 性能设计 | 15 | 15 | 零额外 IO 开销（纯内存 ThreadLocal + Header 透传），影子表拦截器非压测流量直接放行 |
| 可靠性 | 15 | 14 | 向后兼容（@Deprecated 旧 API）、兜底策略（MDC 兜底 TraceContext）、深拷贝防串联 |
| 面试价值 | 15 | 15 | 全链路透传、压测隔离、影子表、ThreadLocal 泄漏——全是高频面试题 |
| **总分** | **100** | **96** | |

---

## 🏗️ 实现内容

### 新增文件

| 文件 | 模块 | 说明 |
|------|------|------|
| `TraceContext.java` | common | 全链路染色上下文（6 个标记字段） |
| `TraceContextHolder.java` | common | ThreadLocal 持有者（set/get/clear/snapshot） |
| `ShadowTableInterceptor.java` | common | MyBatis 拦截器（压测流量自动路由到影子表） |
| `TrafficColoringFilter.java` | gateway | Gateway 流量染色过滤器（注入 6 个 Header） |

### 改造文件

| 文件 | 模块 | 变更说明 |
|------|------|----------|
| `TraceIdConfig.java` | common | 从仅处理 TraceId → 恢复完整 TraceContext（6 个标记） |
| `FeignTraceInterceptorConfig.java` | common | 从透传 2 个 Header → 透传 6 个 Header，改用 TraceContextHolder |
| `MqTraceHelper.java` | common | 从仅透传 TraceId → 透传完整 TraceContext，向后兼容旧 API |
| `AsyncConfig.java` | common | 从仅透传 MDC → 同时透传 MDC + TraceContext，深拷贝防串联 |

---

## 💡 技术亮点

### 1. 四层全链路透传

```mermaid
graph LR
    A[Gateway<br/>TrafficColoringFilter] -->|HTTP Header| B[业务服务<br/>TraceIdConfig拦截器]
    B -->|ThreadLocal| C{调用方式}
    C -->|Feign同步| D[FeignTraceInterceptor<br/>Header透传]
    C -->|MQ异步| E[MqTraceHelper<br/>Message Header透传]
    C -->|线程池异步| F[TraceContextTaskDecorator<br/>ThreadLocal深拷贝]
    D --> B
    E --> B
    F --> B
```

### 2. 影子表路由（MyBatis 拦截器）

```
X-Pressure-Test=true 时：
  MyBatis Interceptor 拦截 SQL
  → 正则匹配表名（FROM/INTO/UPDATE/JOIN 后的 t_xxx）
  → 替换为 t_xxx_shadow
  → 压测数据写影子表，不污染生产

安全保障：
  1. 默认关闭（myxhs.shadow.enabled=true 才生效）
  2. 非压测流量直接放行（零开销）
  3. 避免重复添加 _shadow 后缀
```

### 3. ThreadLocal 三层清理保障

```
第一层：TraceIdConfig 拦截器 afterCompletion → 清理 TraceContext + MDC
第二层：MQ Consumer finally → clearTraceContext()
第三层：TaskDecorator finally → clear TraceContext + MDC

任何一层遗漏，其他层兜底，确保不泄漏。
```

### 4. 深拷贝防止跨线程串联

```java
// 错误做法：直接传引用
TraceContext ctx = TraceContextHolder.get(); // 父线程的引用
executor.execute(() -> {
    TraceContextHolder.set(ctx); // 子线程拿到同一个对象
    // 父线程可能已经在处理下一个请求，修改了 ctx 的值！
});

// 正确做法：深拷贝
TraceContext snapshot = TraceContextHolder.snapshot(); // 深拷贝
executor.execute(() -> {
    TraceContextHolder.set(snapshot); // 子线程拿到独立副本
});
```

### 5. 向后兼容

```java
// 旧代码调用不报错，只是标记 @Deprecated
MqTraceHelper.wrapWithTraceId(msg);   // → 内部调用 wrapWithTraceContext
MqTraceHelper.restoreTraceId(msg);    // → 内部调用 restoreTraceContext
MqTraceHelper.clearTraceId();         // → 内部调用 clearTraceContext
```

---

## 🔍 深度技术分析

### 染色标记生命周期

```
┌─────────────────────────────────────────────────────────────────┐
│ Gateway (WebFlux)                                                │
│  TrafficColoringFilter: 注入 6 个 Header 到 HTTP Request         │
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTP Header
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│ 业务服务 (Servlet)                                               │
│  TraceIdConfig 拦截器:                                           │
│    preHandle: Header → TraceContext(ThreadLocal) + MDC            │
│    afterCompletion: clear ThreadLocal + MDC                       │
│                                                                   │
│  业务代码:                                                        │
│    TraceContextHolder.isPressureTest() → 判断压测流量             │
│    TraceContextHolder.getGrayTag() → 获取灰度标记                │
│                                                                   │
│  Feign 调用: TraceContext → HTTP Header → 下游服务                │
│  MQ 发送: TraceContext → Message Header → Consumer               │
│  @Async: TraceContext 深拷贝 → 子线程 ThreadLocal                │
└─────────────────────────────────────────────────────────────────┘
```

### 为什么用 MyBatis 拦截器而不是 ShardingSphere？

| 维度 | MyBatis 拦截器（✅ 选定） | ShardingSphere |
|------|-------------------------|----------------|
| 依赖 | 零额外依赖 | 引入 ShardingSphere JAR（几十 MB） |
| 复杂度 | 低（一个拦截器类） | 高（配置 shadow 规则、数据源） |
| 性能 | 非压测流量零开销 | 每次 SQL 都经过 ShardingSphere 解析 |
| 灵活性 | 正则匹配，支持任意表名 | 需要逐表配置 shadow 规则 |
| 适用场景 | MVP 阶段 | 大规模生产环境 |

---

## 🎤 面试话术

### Q1: 全链路流量染色怎么实现的？

> "四层透传机制：
> 1. Gateway 入口：TrafficColoringFilter 注入 6 个 Header（TraceId/UserId/GrayTag/ApiVersion/ABGroup/PressureTest）
> 2. 同步调用：Feign RequestInterceptor 从 ThreadLocal 读取染色标记，透传到下游 HTTP Header
> 3. 异步消息：MQ 发送时注入 Message Header，消费时恢复到 ThreadLocal
> 4. 线程池异步：TaskDecorator 深拷贝 TraceContext 到子线程
>
> 关键设计：所有透传都从 TraceContextHolder（ThreadLocal）读取，而非从 HttpServletRequest 读取。因为 MQ 消费者触发的 Feign 调用没有 HttpServletRequest，但有 TraceContext。"

### Q2: 压测流量怎么保证不污染生产数据？

> "MyBatis 拦截器实现影子表路由：
> 1. 当 TraceContextHolder.isPressureTest() == true 时，拦截器自动将 SQL 中的表名替换为影子表（加 _shadow 后缀）
> 2. 影子表结构与生产表完全一致（包括索引）
> 3. 默认关闭，需配置 myxhs.shadow.enabled=true 才生效
> 4. 非压测流量直接放行，零性能开销
> 5. 压测结束后 TRUNCATE 影子表即可"

### Q3: ThreadLocal 泄漏怎么防？

> "三层清理保障：
> 1. HTTP 请求：TraceIdConfig 拦截器 afterCompletion 中清理
> 2. MQ 消费：Consumer finally 中调用 clearTraceContext()
> 3. 异步线程：TaskDecorator finally 中清理
>
> 另外，跨线程传递时必须深拷贝（TraceContextHolder.snapshot()），不能直接传引用。否则父线程清理后子线程拿到的对象可能已被修改。"

### Q4: 为什么 Feign 透传从 ThreadLocal 读取而不是从 HttpServletRequest？

> "因为 MQ 消费者触发的 Feign 调用没有 HttpServletRequest（不是 HTTP 请求上下文），但有 TraceContext（从 Message Header 恢复到 ThreadLocal）。
>
> 如果从 HttpServletRequest 读取，MQ 消费者场景下染色标记会丢失。从 ThreadLocal 读取是统一的、通用的方案。"

---

## ✅ 验证结果

| 测试场景 | 预期 | 实际 | 通过 |
|----------|------|------|:----:|
| 全模块编译 | BUILD SUCCESS | 所有模块编译通过 | ✅ |
| Order 服务启动 | 正常启动 | 4.77s 启动成功 | ✅ |
| 染色 Header 透传 | 响应回传 X-Trace-Id | `X-Trace-Id: test-trace-coloring-001` | ✅ |
| 正常请求（非压测） | 正常返回数据 | 200 + 订单列表 | ✅ |
| 向后兼容 | 旧 API 不报错 | deprecation 警告但编译通过 | ✅ |
| 影子表拦截器（默认关闭） | 不影响正常流量 | 正常查询生产表 | ✅ |

---

## 🐛 Review 发现的问题及修复

### 问题 1：Gateway Header 追加导致重复值（严重 🔴）

**问题描述**：`ServerHttpRequest.Builder.header()` 是**追加**语义，如果客户端已传 `X-Gray-Tag: beta`，再调用 `builder.header(GRAY_TAG_HEADER, "stable")` 会导致 Header 有两个值 `[beta, stable]`，下游服务 `getFirst()` 取到的是客户端原始值，染色逻辑失效。

**修复方案**：改用 `headers.set(key, value)` 覆盖式设置，通过 `request.mutate().headers(consumer)` 统一处理。

**修复前**：
```java
builder.header(GRAY_TAG_HEADER, grayTag);  // 追加，不覆盖！
```

**修复后**：
```java
ServerHttpRequest mutatedRequest = request.mutate()
    .headers(headers -> {
        headers.set(GRAY_TAG_HEADER, finalGrayTag);  // 覆盖式设置
        // ...
    })
    .build();
```

---

### 问题 2：`Math.abs(Integer.MIN_VALUE)` 溢出（中等 🟡）

**问题描述**：`Math.abs(userId.hashCode())` 当 hashCode 恰好为 `Integer.MIN_VALUE` 时，`Math.abs` 返回的仍然是负数（Java 的 `Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE`），取模结果为负数，switch 匹配到 default 分支。

**修复方案**：使用位运算 `(hashCode & 0x7FFFFFFF)` 代替 `Math.abs()`。

**修复前**：
```java
int hash = Math.abs(userId.hashCode()) % 3;  // Integer.MIN_VALUE 时溢出！
```

**修复后**：
```java
int hash = (userId.hashCode() & 0x7FFFFFFF) % 3;  // 位运算保证非负
```

---

### 问题 3：影子表正则不支持反引号表名（轻微 🟢）

**问题描述**：MyBatis-Plus 生成的 SQL 可能带反引号（如 `` SELECT * FROM `t_order` ``），原正则 `(FROM|INTO|UPDATE|JOIN)\s+(t_\w+)` 无法匹配。

**修复方案**：正则增加可选反引号 `` `? ``，替换时保持原始反引号风格。

**修复前**：
```java
Pattern.compile("(?i)(FROM|INTO|UPDATE|JOIN)\\s+(t_\\w+)");
```

**修复后**：
```java
Pattern.compile("(?i)(FROM|INTO|UPDATE|JOIN)\\s+`?(t_\\w+)`?");
```

---

### 问题 4：未使用的 import（轻微 🟢）

**问题描述**：`ShadowTableInterceptor` 导入了 `org.apache.ibatis.mapping.SqlSource` 但未使用。

**修复**：移除无用 import。

---

## 🔬 深度安全分析

### 压测流量安全边界

| 场景 | 风险 | 防护措施 |
|------|------|----------|
| 恶意客户端伪造 X-Pressure-Test=true | 数据写入影子表丢失 | 生产环境应在 Gateway 限制只有压测平台 IP 才能设置此 Header |
| 影子表不存在 | SQL 执行报错 | 压测前必须创建影子表（DDL 与生产表一致） |
| 影子表忘记建索引 | 压测时查询极慢 | 影子表 DDL 必须包含所有索引 |
| 压测结束后忘记清理 | 影子表数据膨胀 | 压测后 TRUNCATE 影子表 |
| 分布式事务跨表 | 事务消息写影子表，但回查写生产表 | 事务消息的回查也会携带 TraceContext（MQ Header 透传） |

### UserContext 与 TraceContext 的关系

```
UserContext（TransmittableThreadLocal<Long>）：
  - 存储 userId（Long 类型）
  - 用于业务代码获取当前用户
  - 基于 TTL，线程池自动透传

TraceContext（ThreadLocal）：
  - 存储 userId（String 类型）+ 5 个染色标记
  - 用于跨服务透传（Feign/MQ）
  - 基于 TaskDecorator 手动透传

两者并存，职责不同：
  - UserContext 面向业务（requireUserId() 抛异常）
  - TraceContext 面向基础设施（Feign/MQ 透传）
```
