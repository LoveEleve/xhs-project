---
name: http-client-toolkit
stage: 组件封装
description: HTTP 客户端工具封装——连接池管理、超时三件套、重试（指数退避）、熔断降级、负载均衡、traceId 透传
---

# HTTP 客户端工具封装（http-client-toolkit）

## 1. 职责

为项目生成 HTTP 客户端基础设施代码，包括连接池管理、超时配置（connect/read/write）、重试策略（指数退避+最大次数）、熔断降级、负载均衡、traceId 透传。**禁止在业务代码中直接创建 HTTP 客户端而不经过封装层**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/http-client-toolkit [pool\|retry\|circuit\|loadbalance\|all]` |
| 自动加载 | coding-skill 阶段检测到 `HttpClient` / `RestTemplate` / `OkHttp` / `httpx` / `requests` / `net/http` 时 |

## 3. 前置条件

- 项目已识别技术栈（Java RestTemplate/OkHttp/WebClient / Python httpx/requests / Go net/http/resty）
- 已确定目标包路径（按语言规范）
- 已确定下游服务列表（至少一个 URL 或服务名）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `pool` | 连接池配置 | 无连接池或使用默认配置 |
| `retry` | 重试策略 | 外部调用无重试 |
| `circuit` | 熔断降级 | 外部调用无熔断保护 |
| `loadbalance` | 负载均衡 | 多实例调用无负载均衡 |
| `all`（默认） | 全部组件 | 首次引入 HTTP 客户端 |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/http/
├── client/
│   ├── HttpClientFactory.java        — HTTP 客户端工厂（连接池/超时/拦截器）
│   ├── TraceIdInterceptor.java       — traceId 请求/响应拦截器
│   └── LoggingInterceptor.java       — 请求/响应日志拦截器
├── retry/
│   ├── RetryStrategy.java            — 重试策略接口
│   ├── ExponentialBackoffRetry.java  — 指数退避重试
│   └── RetryableHttpClient.java      — 可重试 HTTP 客户端
├── circuit/
│   ├── CircuitBreaker.java           — 熔断器接口
│   ├── SlidingWindowBreaker.java     — 滑动窗口熔断器
│   └── FallbackHandler.java          — 降级处理器
├── loadbalance/
│   ├── LoadBalancer.java             — 负载均衡接口
│   ├── RoundRobinBalancer.java       — 轮询
│   ├── WeightedBalancer.java         — 加权
│   └── ServiceDiscovery.java         — 服务发现（从配置中心获取实例列表）
└── config/
    └── HttpClientConfig.java         — 配置类（连接池大小/超时/重试次数）

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `HttpClientFactory`：连接池最大连接数 200，每条路由最大 50，keep-alive 存活时间 60s，空闲连接回收 30s
- 超时三件套：connectTimeout=5s（建立连接），readTimeout=10s（等待响应），writeTimeout=10s（发送数据），全部可配置
- `RetryStrategy`：指数退避（base=1s，multiplier=2，maxRetries=3，maxDelay=10s），仅对 GET/幂等请求重试，POST/PUT/DELETE 不自动重试
- `CircuitBreaker`：滑动窗口统计最近 100 次请求，失败率 > 50% 熔断 10s，半开后允许 3 次试探请求，成功则关闭
- `TraceIdInterceptor`：从 MDC 上下文提取 traceId，注入请求头 `X-Trace-Id`，响应中提取下游 traceId 记录日志
- `LoggingInterceptor`：记录请求方法/URL/耗时/状态码，响应体大于 10KB 截断，不记录敏感字段（Authorization/Token）

### Step 3: 生成测试

```
{basePackage}/http/
├── client/HttpClientFactoryTest.java
├── retry/ExponentialBackoffRetryTest.java
├── circuit/SlidingWindowBreakerTest.java
├── loadbalance/RoundRobinBalancerTest.java
└── client/TraceIdInterceptorTest.java
```

测试要求：
- 连接池：验证连接池配置生效、连接复用、空闲回收
- 重试：验证指数退避时间间隔、最大重试次数、非幂等请求不重试
- 熔断：验证滑动窗口统计、熔断状态切换、半开试探逻辑
- 负载均衡：验证轮询/加权分发、空实例列表处理
- 使用 Mock HTTP 服务器测试

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 所有 HTTP 调用经过封装层（非直接 `new HttpClient()`）
   - [ ] 超时三件套全部配置（非只配了 connectTimeout）
   - [ ] 重试仅对幂等请求生效
   - [ ] 熔断器有半开试探机制（非熔断后永不恢复）
   - [ ] traceId 自动透传（非手动在业务代码中设置请求头）

## 5. 输出文件清单

```
{targetPackage}/http/
├── client/HttpClientFactory.{java|py|go}
├── client/TraceIdInterceptor.{java|py|go}
├── client/LoggingInterceptor.{java|py|go}
├── retry/RetryStrategy.{java|py|go}
├── retry/ExponentialBackoffRetry.{java|py|go}
├── retry/RetryableHttpClient.{java|py|go}
├── circuit/CircuitBreaker.{java|py|go}
├── circuit/SlidingWindowBreaker.{java|py|go}
├── circuit/FallbackHandler.{java|py|go}
├── loadbalance/LoadBalancer.{java|py|go}
├── loadbalance/RoundRobinBalancer.{java|py|go}
├── loadbalance/WeightedBalancer.{java|py|go}
├── config/HttpClientConfig.{java|py|go}
├── client/HttpClientFactoryTest.{java|py|go}
├── retry/ExponentialBackoffRetryTest.{java|py|go}
├── circuit/SlidingWindowBreakerTest.{java|py|go}
└── loadbalance/RoundRobinBalancerTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有 HTTP 调用经过封装层
- ✅ 超时三件套全部配置
- ✅ 重试仅对幂等请求生效
- ✅ 熔断器有半开试探机制
- ✅ traceId 自动透传
- ❌ 禁止直接 `new HttpClient()` / `new RestTemplate()` 而不经过工厂
- ❌ 禁止重试非幂等请求（POST/PUT/DELETE）
- ❌ 禁止熔断器无半开试探（会永久熔断）
- ❌ 禁止在业务代码中手动设置 traceId 请求头

## 7. 约束

- ❌ 不生成服务发现的具体实现（只提供接口，具体实现由 Nacos/Eureka/K8s 提供）
- ❌ 不修改已有的 HTTP 调用代码（只新增封装层，建议用户逐步迁移）
- ❌ 不引入未确认的 HTTP 客户端库（项目中已有的优先复用）
- ❌ 不生成 API 网关配置（由网关团队管理）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
