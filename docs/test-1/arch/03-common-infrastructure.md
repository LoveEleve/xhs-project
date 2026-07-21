# 03 — common 模块全景

> **目标读者**：P7+ 工程师，需要理解 common 模块的组件注册顺序和依赖关系。
> **回答三个问题**：为什么放 common 而不是独立模块？组件之间如何协作？每个组件的职责和边界是什么？

---

## 一、为什么有 common 模块？

### 1.1 设计原则

**所有服务共享的基础设施代码放在 common，避免重复**。这包括：

| 类别 | 示例 | 如果每个服务自己写 |
|------|------|-------------------|
| AOP 防护 | @RateLimit / @DistributedLock / @Idempotent | 15 个服务各写一遍，不一致 |
| 全链路染色 | TraceId 透传 | 链路断裂，无法追踪 |
| 响应包装 | Result<T> 自动包装 | 每个 Controller 手动包装 |
| Feign 增强 | 自动解包、异常还原 | 每个调用方自己处理 |
| 读写分离 | @ReadOnly 路由 | 每个服务自己实现 |

### 1.2 为什么不拆成独立微服务？

| 方案 | 优点 | 缺点 |
|------|------|------|
| **common JAR（当前）** | 编译期依赖，零运行时开销 | 修改需要所有服务重新编译 |
| **独立服务** | 独立部署，热更新 | 增加一次网络调用，每个请求都多一次 RTT |
| **Sidecar** | 语言无关 | 运维复杂度剧增 |

**选择 common JAR**：对于 Java 技术栈统一的项目，JAR 是最简单有效的共享方式。所有组件运行在同一个 JVM 进程内，零网络开销。

---

## 二、组件全景图

```
my-xhs-common 模块（~110 个 Java 文件，20 个 @Configuration）

┌─────────────────────────────────────────────────────────────┐
│                   请求处理链（按执行顺序）                      │
├─────────────────────────────────────────────────────────────┤
│ 1. TraceContextInterceptor   → 从 Header 恢复 TraceId       │
│ 2. UserContextInterceptor    → 从 Header 提取 UserId        │
│ 3. AccessLogInterceptor      → 记录访问日志                  │
│ 4. ApiVersionHandlerMapping  → 版本路由匹配                  │
│ 5. @RateLimit (Order=10)     → 限流                         │
│ 6. @DistributedLock (Order=50) → 分布式锁                   │
│ 7. @Idempotent (Order=100)   → 幂等                         │
│ 8. Controller 执行                                         │
│ 9. ResponseAutoWrapper       → 自动包装 Result<T>           │
│ 10. GlobalExceptionHandler   → 统一异常处理                  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                   基础设施组件                                │
├─────────────────────────────────────────────────────────────┤
│ • ReadWriteRoutingDataSource  → 读写分离                     │
│ • CacheHelper                 → 多级缓存                     │
│ • RedissonConfig              → 分布式锁                     │
│ • SqlGuardInterceptor         → SQL 熔断                     │
│ • FeignUnifiedConfig          → Feign 增强                   │
│ • TraceIdConfig               → 全链路染色                   │
│ • XxlJobConfig                → 分布式调度                   │
│ • ZoneContext                 → 多活路由                     │
│ • LeastConnectionsLoadBalancer → 负载均衡                    │
│ • BusinessMetrics             → 业务指标                     │
└─────────────────────────────────────────────────────────────┘
```

---

## 三、核心组件详解

### 3.1 读写分离路由（ReadWriteRoutingDataSource）

**文件**：`common/datasource/ReadWriteRoutingDataSource.java`

三级路由策略（优先级从高到低）：

```
1. DataSourceContextHolder 手动指定   → 显式调用 setDataSourceType(MASTER/SLAVE)
2. @Transactional(readOnly=true)     → 自动路由到从库
3. SELECT 语句分析                   → SELECT 路由到从库，其他到主库
```

**从库不可用的降级**：
```
SLAVE 连接失败 → 自动切换 MASTER → 30s 后探测 SLAVE → 恢复后切回
```

**配置方式**：
```yaml
spring:
  datasource:
    readwrite:
      enabled: true
    master:
      url: jdbc:mysql://host:13307/my_xhs_content
    slave:
      url: jdbc:mysql://host:13311/my_xhs_content
```

→ 详细设计见 `40-mysql-engineering-issues.md`

### 3.2 响应自动包装（ResponseAutoWrapper）

**文件**：`common/response/ResponseAutoWrapper.java`

实现 `ResponseBodyAdvice<Object>`，在 Controller 返回值写入响应体之前自动包装：

```java
// Controller 写：
@GetMapping("/notes/{id}")
public NoteDTO getNote(@PathVariable Long id) {
    return noteService.getById(id);
}

// 实际返回：
{
  "code": 200,
  "message": "success",
  "data": { "id": 1, "title": "..." },
  "timestamp": 1700000000000
}
```

**防重复包装规则**：
| 返回类型 | 行为 |
|---------|------|
| 已是 `R<T>` 类型 | 不包装 |
| `String` 类型 | 不包装（StringHttpMessageConverter 序列化路径不同） |
| `void` / `null` | 包装为 `R.ok()` |
| 其他 POJO | 包装为 `R.ok(data)` |

**代价**：
- `supports()` 方法每次请求都执行，有反射开销（约 0.01ms，可忽略）
- String 类型不包装意味着返回 String 时需手动包装，不一致

### 3.3 API 多版本路由（ApiVersionHandlerMapping）

**文件**：`common/version/ApiVersionHandlerMapping.java`

扩展 Spring MVC 的 `RequestMappingHandlerMapping`，注入 `@ApiVersion` 作为额外的路由匹配条件：

```
请求: GET /api/notes/1, Accept-Version: 2.0

匹配流程:
1. URL 匹配: /api/notes/{id} ✓
2. HTTP Method 匹配: GET ✓
3. ApiVersionCondition 匹配:
   - 扫描到两个方法: @ApiVersion("1.0") 和 @ApiVersion("2.0")
   - Accept-Version=2.0 → 匹配 v2.0 ✓
4. 路由到 getNoteV2() 方法
```

**默认版本策略**：无 `Accept-Version` Header 时，匹配数值最大的版本（最新版）。

→ 详细设计见 `47-api-versioning.md`

### 3.4 最少连接负载均衡（LeastConnectionsLoadBalancer）

**文件**：`common/loadbalancer/LeastConnectionsLoadBalancer.java`

替代 Spring Cloud LoadBalancer 默认的 RoundRobin：

```
选择算法:
1. 过滤不健康实例
2. 对每个实例计算有效权重 = weight × 预热系数
   - 启动 < 60s: 预热系数 = elapsedSeconds / 60
   - 启动 ≥ 60s: 预热系数 = 1.0
3. 选择 effectiveWeight / activeConnections 最大的实例
4. 通过 markRequestStart/markRequestEnd 追踪活跃连接数
```

**为什么不用 RoundRobin？**
- RoundRobin 假设所有实例处理能力相同
- 实际上不同实例可能配置不同、负载不同
- LeastConnections 自动将请求路由到最空闲的实例

### 3.5 Feign 统一增强（FeignUnifiedConfig）

**文件**：`common/config/FeignUnifiedConfig.java`

**两个核心增强**：

1. **RUnpackDecoder**：自动解包 `R<T>` 中的 data 字段
   ```
   下游返回: R.ok(UserDTO) → {"code":200, "data":{"id":1,"name":"张三"}}
   Feign 接口定义: UserDTO getUser(@PathVariable Long id);
   自动解包后返回: UserDTO{id=1, name="张三"}
   ```

2. **业务异常还原**：下游 `R.fail(BIZ_ERROR)` 自动转为 `BizException`
   ```
   下游返回: {"code":40001, "message":"用户不存在"}
   → Feign 调用方抛出: BizException("用户不存在")
   ```

**FeignSafeConfig 补充**：
- 全局关闭 Feign 重试（`Retryer.NEVER_RETRY`）：避免重复提交
- 自定义 `ErrorDecoder`：将 HTTP 503/504 转为 `RemoteException`

→ 详细设计见 `42-feign-unified-enhancement.md`

---

## 四、组件依赖关系

### 4.1 启动时的 Bean 注册顺序

```
Spring Boot 启动
  │
  ├── 1. AutoConfiguration.imports 加载
  │     ├── ZoneContextAutoConfiguration    (Zone 上下文)
  │     ├── ZoneLoadBalancerConfiguration   (Zone 优先负载均衡)
  │     └── RedisInterceptorAutoConfiguration (Redis 命令拦截)
  │
  ├── 2. @Configuration 类扫描
  │     ├── RedisConfig              (Business Redis, noeviction)
  │     ├── RedisMultiSourceConfig   (Cache Redis, allkeys-lru)
  │     ├── RedissonConfig           (分布式锁)
  │     ├── DataSourceConfig         (读写分离)
  │     ├── MybatisPlusConfig        (分页插件)
  │     ├── FeignUnifiedConfig       (Feign 增强)
  │     ├── FeignSafeConfig          (Feign 安全)
  │     ├── SentinelBulkheadConfig   (舱壁隔离)
  │     ├── TraceIdConfig            (全链路染色)
  │     ├── AsyncConfig              (异步线程池)
  │     ├── JacksonConfig            (JSON 序列化)
  │     ├── WebMvcConfig             (拦截器注册)
  │     ├── AccessLogConfig          (访问日志)
  │     ├── HttpCacheConfig          (HTTP 缓存)
  │     ├── LeastConnectionsLoadBalancerConfig (负载均衡)
  │     ├── LettuceMetricsConfig     (Redis 指标)
  │     ├── TransactionConfig        (事务管理)
  │     ├── ApiVersionAutoConfiguration (API 版本)
  │     ├── MetricsAutoConfiguration (Micrometer)
  │     └── MyBatisMetricsAutoConfiguration (MyBatis 指标)
  │
  ├── 3. AOP 切面注册（按 @Order 排序）
  │     ├── @Order(1)  IdempotentMessageAspect   (MQ 幂等)
  │     ├── @Order(10) RateLimitAspect            (限流)
  │     ├── @Order(50) DistributedLockAspect      (分布式锁)
  │     └── @Order(100) IdempotentAspect          (HTTP 幂等)
  │
  └── 4. CommandLineRunner 执行
        └── DataGeneratorRunner   (数据生成，需显式开启)
```

### 4.2 运行时组件协作

```
HTTP 请求到达
  │
  ├── Filter Chain
  │   └── ApiMetricsFilter          → 记录请求耗时指标
  │
  ├── Interceptor Chain
  │   ├── TraceContextInterceptor   → 恢复 TraceId + MDC
  │   ├── UserContextInterceptor    → 提取 UserId
  │   └── AccessLogInterceptor      → 记录访问日志
  │
  ├── HandlerMapping
  │   └── ApiVersionHandlerMapping  → 版本路由匹配
  │
  ├── AOP Chain (按 @Order 执行)
  │   ├── @RateLimit                → Redis Lua 限流
  │   ├── @DistributedLock          → Redisson 加锁
  │   └── @Idempotent               → Redis SETNX 幂等
  │
  ├── Controller 方法执行
  │   ├── MyBatis 执行 SQL
  │   │   ├── SqlGuardInterceptor   → 慢 SQL 检测
  │   │   ├── ShadowTableInterceptor → 压测影子表路由
  │   │   └── ReadWriteRoutingDataSource → 读写分离路由
  │   │
  │   ├── Feign 调用下游
  │   │   ├── FeignTraceInterceptor → 透传 TraceId
  │   │   ├── LeastConnectionsLoadBalancer → 负载均衡
  │   │   └── RUnpackDecoder        → 自动解包 Result<T>
  │   │
  │   └── Redis 操作
  │       └── RedisTemplateWrapper  → 命令拦截
  │
  ├── ResponseBodyAdvice
  │   ├── EtagResponseBodyAdvice    → ETag 生成
  │   └── ResponseAutoWrapper      → 自动包装 Result<T>
  │
  └── @ExceptionHandler
      └── GlobalExceptionHandler    → 统一异常处理
```

---

## 五、组件分类与职责矩阵

| 组件 | 类型 | 条件装配 | 依赖 |
|------|------|---------|------|
| ReadWriteRoutingDataSource | DataSource | `readwrite.enabled=true` | HikariCP |
| RedissonConfig | Lock | 无 | Redis |
| CacheHelper | Cache | 无 | Redis + Caffeine |
| @RateLimit | AOP | 无 | Redis Lua |
| @DistributedLock | AOP | 无 | Redisson |
| @Idempotent | AOP | 无 | Redis SETNX |
| TraceIdConfig | Trace | `@ConditionalOnWebApplication` | TTL |
| FeignUnifiedConfig | Feign | `@ConditionalOnClass(Feign)` | Feign |
| FeignSafeConfig | Feign | `@ConditionalOnClass(Retryer)` | Feign |
| SqlGuardInterceptor | MyBatis | 无 | MyBatis |
| ApiVersionHandlerMapping | WebMVC | `@ConditionalOnWebApplication` | Spring MVC |
| ResponseAutoWrapper | Response | `@ConditionalOnWebApplication` | Spring MVC |
| LeastConnectionsLoadBalancer | LB | `@ConditionalOnClass(ReactorLoadBalancer)` | Spring Cloud LB |
| XxlJobConfig | Schedule | `xxl.job.enabled=true` | XXL-Job |
| BusinessMetrics | Metrics | 无 | Micrometer |
| ZoneContext | Zone | 无 | Nacos |
| GracefulShutdownListener | Shutdown | 无 | Spring Events |
| TccFenceService | TCC | 无 | MySQL |
| ChaosInterceptor | Chaos | `chaos.enabled=true` | 无 |

---

## 六、代价与局限

| 局限 | 影响 | 改进方向 |
|------|------|---------|
| **common 修改需全量编译** | 修改 common 后 15 个服务都要重新编译 | 合理的，common 应保持稳定 |
| **String 类型不包装** | 返回 String 时需手动包装 Result | 技术限制，Spring MVC StringHttpMessageConverter 优先级问题 |
| **读写分离路由依赖 @Transactional(readOnly)** | 非事务方法无法自动路由到从库 | 需要显式使用 @ReadOnly 注解或手动切换 |
| **AOP 切面不控制调用顺序** | @RateLimit → @DistributedLock → @Idempotent 的依赖关系仅在 @Order 中体现，无编译期校验 | 文档约定 + Code Review |

---

> **下一篇**：`04-aop-three-layer-defense.md` — @RateLimit → @DistributedLock → @Idempotent 三层 AOP 防护的详细设计
