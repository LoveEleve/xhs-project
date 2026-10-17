---
name: openfeign-toolkit
stage: 组件封装
description: OpenFeign 工具封装——统一超时配置、错误解码器、重试配置、请求/响应拦截器（traceId/鉴权头）、熔断集成
---

# OpenFeign 工具封装（openfeign-toolkit）

> **Lang**: Java 特有（基于 Spring Cloud OpenFeign）
> **存放位置**: `harness-java/skills/`

## 1. 职责

为 Java 项目生成 Feign 基础设施代码，包括统一超时配置（connect/read）、错误解码器（ErrorDecoder）、重试配置（Retryer）、请求/响应拦截器（traceId 透传/鉴权头自动注入）、熔断集成（Sentinel/Resilience4j）。**禁止在业务代码中手动注入 traceId 和鉴权头**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/openfeign-toolkit [timeout|decoder|retry|interceptor|circuit|all]` |
| 自动加载 | coding-skill 阶段检测到 `@FeignClient` / `Feign.Builder` / `feign` 依赖时 |

## 3. 前置条件

- 项目已识别为 Java + Spring Cloud OpenFeign 项目
- 已确定目标包路径（`{basePackage}.feign`）
- 已确定下游服务列表（至少一个 `@FeignClient` 接口）
- 已确定熔断框架（Sentinel / Resilience4j / 无）
- 用户未指定 -> 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `timeout` | 统一超时配置 | 无 connectTimeout/readTimeout 配置 |
| `decoder` | 错误解码器 | 返回状态码非 200 时无错误处理 |
| `retry` | 重试配置 | 调用失败无重试 |
| `interceptor` | 请求/响应拦截器 | 手动设置 traceId/鉴权头 |
| `circuit` | 熔断集成 | 无熔断保护 |
| `all`（默认） | 全部组件 | 首次引入 Feign |

### Step 2: 生成代码文件

```
{basePackage}/feign/
├── config/
│   ├── FeignConfig.java                — 全局 Feign 配置
│   ├── FeignTimeoutConfig.java         — 超时配置（按服务名）
│   └── FeignLoggerConfig.java          — 日志级别配置
├── interceptor/
│   ├── FeignTraceIdInterceptor.java    — traceId 拦截器（MDC 注入请求头）
│   ├── FeignAuthInterceptor.java       — 鉴权拦截器（自动注入 Token）
│   └── FeignResponseInterceptor.java   — 响应拦截器（记录状态码/耗时）
├── decoder/
│   ├── FeignErrorDecoder.java          — 统一错误解码器（状态码 -> 业务异常）
│   └── FeignErrorResponse.java         — 错误响应结构
├── retry/
│   ├── FeignRetryStrategy.java         — 重试策略接口
│   └── FeignExponentialBackoffRetry.java — 指数退避重试
├── circuit/
│   ├── FeignCircuitBreakerConfig.java  — 熔断配置（Sentinel/Resilience4j）
│   └── FeignFallbackFactory.java       — 降级工厂
└── annotation/
    └── FeignClientCustomizer.java      — 自定义注解（按服务配置超时/重试）
```

**每个组件的关键实现约束**：

- `FeignConfig`：全局配置 connectTimeout=5000ms, readTimeout=10000ms，开启 Feign 日志，使用 Apache HttpClient 或 OkHttp 连接池（非 JDK 默认 URLConnection）
- `FeignTimeoutConfig`：支持按服务名配置超时（如 `order-service.connectTimeout=3000, order-service.readTimeout=5000`），未配置的服务使用全局默认值
- `FeignTraceIdInterceptor`：实现 `RequestInterceptor`，从 MDC 提取 traceId，注入请求头 `X-Trace-Id`，响应拦截器记录下游 traceId
- `FeignAuthInterceptor`：从安全上下文获取 Token，自动注入请求头，Token 过期自动刷新（刷新后重试当前请求）
- `FeignErrorDecoder`：根据 HTTP 状态码解码为具体业务异常（400=参数错误, 401=未授权, 403=无权限, 404=服务不存在, 429=限流, 500=服务端错误, 503=服务不可用），保留原始响应体（便于排查）
- 重试策略：指数退避 base=100ms, multiplier=2, maxRetries=3, maxInterval=5s，仅对 GET 请求重试，非幂等请求不重试，重试时重新设置超时
- 熔断集成：Sentinel 模式使用 `@SentinelResource` + fallback，Resilience4j 模式使用 `@CircuitBreaker` + `@TimeLimiter`，熔断默认配置（失败率 50%/窗口 10s/半开 3 次）

### Step 3: 生成测试

```
{basePackage}/feign/
├── config/FeignConfigTest.java
├── interceptor/FeignTraceIdInterceptorTest.java
├── decoder/FeignErrorDecoderTest.java
├── retry/FeignExponentialBackoffRetryTest.java
├── circuit/FeignFallbackFactoryTest.java
└── interceptor/FeignAuthInterceptorTest.java
```

测试要求：
- 拦截器：验证 traceId 注入、鉴权头注入、Token 过期自动刷新
- 错误解码器：验证各状态码解码为正确的业务异常
- 重试：验证指数退避时间间隔、幂等/非幂等行为
- 熔断：验证熔断状态切换、降级调用

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 所有 Feign 客户端有统一超时配置
   - [ ] traceId 自动透传（非手动设置请求头）
   - [ ] 鉴权头自动注入（非手动设置）
   - [ ] 错误解码器已实现（非默认解码器）
   - [ ] 重试仅对幂等请求生效
   - [ ] 熔断集成已配置

## 5. 输出文件清单

```
{basePackage}/feign/
├── config/FeignConfig.java
├── config/FeignTimeoutConfig.java
├── config/FeignLoggerConfig.java
├── interceptor/FeignTraceIdInterceptor.java
├── interceptor/FeignAuthInterceptor.java
├── interceptor/FeignResponseInterceptor.java
├── decoder/FeignErrorDecoder.java
├── decoder/FeignErrorResponse.java
├── retry/FeignRetryStrategy.java
├── retry/FeignExponentialBackoffRetry.java
├── circuit/FeignCircuitBreakerConfig.java
├── circuit/FeignFallbackFactory.java
├── annotation/FeignClientCustomizer.java
├── config/FeignConfigTest.java
├── interceptor/FeignTraceIdInterceptorTest.java
├── decoder/FeignErrorDecoderTest.java
├── retry/FeignExponentialBackoffRetryTest.java
├── circuit/FeignFallbackFactoryTest.java
└── interceptor/FeignAuthInterceptorTest.java
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有 Feign 客户端有统一超时配置
- ✅ traceId 自动透传
- ✅ 鉴权头自动注入
- ✅ 错误解码器已实现
- ✅ 重试仅对幂等请求生效
- ❌ 禁止手动设置 traceId 请求头
- ❌ 禁止手动设置鉴权头
- ❌ 禁止重试非幂等请求
- ❌ 禁止使用默认错误解码器（会吞掉错误信息）
- ❌ 禁止 Feign 无熔断保护（生产环境必备）

## 7. 约束

- ❌ 不生成 `@FeignClient` 接口定义（接口由业务需求生成）
- ❌ 不修改已有的 `@FeignClient` 接口（只新增拦截器和配置）
- ❌ 不引入未确认的 Feign 版本（项目中已有的优先复用）
- ❌ 不生成 Nacos/Eureka 服务发现配置（由注册中心管理）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
