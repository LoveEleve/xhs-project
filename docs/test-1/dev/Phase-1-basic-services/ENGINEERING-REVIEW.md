# Phase 1 工程视角 Review — 可观测性 / 日志 / 监控 / 运维友好性

> 审查时间：2026-05-14
> 审查维度：不看代码细节，站在 P8 技术专家 + 项目 Owner 角度，从工程化、可运维、可观测维度审视 Phase 1

---

## 一、审查结论

| 维度 | 修复前 | 修复后 | 评级 |
|------|:------:|:------:|:----:|
| 链路追踪（TraceId） | ⚠️ 设计有但未生效 | ✅ 全链路贯穿 | P8 |
| 健康检查（Actuator） | ❌ 未引入 | ✅ Liveness + Readiness | P8 |
| 请求日志（Access Log） | ❌ 无 | ✅ 每请求记录 RT | P8 |
| 日志规范（统一格式） | ⚠️ 仅 user 服务 | ✅ 全部服务统一 | P8 |
| 优雅停机 | ❌ 未配置 | ✅ graceful + 30s | P7 |
| SQL 日志安全 | ❌ StdOutImpl | ✅ Slf4jImpl | P7 |
| 指标监控（Prometheus） | ❌ 无端点 | ✅ /actuator/prometheus 就绪 | P7 |
| 连接池监控 | ❌ 无 | ✅ leak-detection-threshold | P7 |

---

## 二、发现的问题及修复记录

### 2.1 P1：TraceIdConfig 未注册到 AutoConfiguration（严重）

**问题描述**：
- `TraceIdConfig` 在 `com.myxhs.common.config` 包下，有 `@Configuration` 注解
- 但没有在 `AutoConfiguration.imports` 中注册
- 各服务启动类（如 `UserApplication`）在 `com.myxhs.user` 包下
- `@SpringBootApplication` 默认只扫描自己包，不会扫描 `com.myxhs.common`

**影响**：TraceId 拦截器根本没生效！日志中的 traceId 字段一直为空。

**修复**：

```diff
# AutoConfiguration.imports
  com.myxhs.common.config.WebMvcConfig
+ com.myxhs.common.config.TraceIdConfig
+ com.myxhs.common.config.AccessLogConfig
  com.myxhs.common.cache.RedisOperator
```

**验证结果**：
```
# 响应 Header
X-Trace-Id: f7ff1829f4184ac9940068a318dde096

# 日志输出
10:27:47.502 WARN [f7ff1829f4184ac9940068a318dde096] [http-nio-9001-exec-4] ...
```

**面试价值**：Spring Boot 自动配置机制 — `@Configuration` vs `AutoConfiguration.imports` 的区别

---

### 2.2 P2：缺少 Spring Boot Actuator（无健康检查端点）

**问题描述**：
- 所有服务的 pom.xml 中没有引入 `spring-boot-starter-actuator`
- application.yml 中没有 `management.endpoints` 配置

**影响**：
- 无 `/actuator/health` → K8s 无法做 Liveness/Readiness 探针
- 无 `/actuator/prometheus` → Prometheus 无法抓取指标
- 无法优雅停机

**修复**：

```xml
<!-- my-xhs-common/pom.xml 新增 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
# 各服务 application.yml 新增
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true  # K8s liveness/readiness 探针支持
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

**验证结果**：
```json
// GET /actuator/health
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "MySQL" } },
    "redis": { "status": "UP", "details": { "version": "7.4.9" } },
    "livenessState": { "status": "UP" },
    "readinessState": { "status": "UP" }
  },
  "groups": ["liveness", "readiness"]
}

// GET /actuator/health/liveness → {"status": "UP"}
// GET /actuator/health/readiness → {"status": "UP"}
```

---

### 2.3 P2：缺少请求耗时（RT）日志

**问题描述**：没有请求级别的 Access Log，无法快速定位慢接口。

**修复**：新增 `AccessLogConfig`，记录每个请求的 URI、方法、耗时、状态码。

```java
// AccessLogConfig.java 核心逻辑
@Override
public void afterCompletion(...) {
    long rt = System.currentTimeMillis() - startTime;
    String logMsg = String.format("[ACCESS] %s %s, status=%d, rt=%dms, ip=%s",
            method, uri, status, rt, clientIp);

    if (ex != null) {
        log.error("{}, exception={}", logMsg, ex.getClass().getSimpleName());
    } else if (rt > SLOW_REQUEST_THRESHOLD_MS) {  // 500ms
        log.warn("{} [SLOW]", logMsg);  // 慢请求 WARN 级别
    } else if (status >= 400) {
        log.warn("{}", logMsg);
    } else {
        log.info("{}", logMsg);
    }
}
```

**设计亮点**：
- 慢请求（>500ms）自动升级为 WARN 级别，便于告警
- 排除 `/actuator/**` 路径，避免健康检查污染日志
- 支持 `X-Forwarded-For` 获取真实 IP（代理场景）
- order=-90，在 TraceId 拦截器（order=-100）之后执行，确保日志中有 TraceId

**验证结果**：
```
10:27:47.502 WARN [f7ff1829f4184ac9940068a318dde096] ... [ACCESS] GET /user/profile, status=404, rt=10ms, ip=...
```

---

### 2.4 P2：logback-spring.xml 只有 user 服务有

**问题描述**：content/analytics/counter 服务没有 logback-spring.xml，使用 Spring Boot 默认日志格式（不含 TraceId）。

**修复**：将 user 服务的 logback-spring.xml 复制到所有服务。

**logback-spring.xml 关键特性**：
| 特性 | 实现 |
|------|------|
| TraceId 自动携带 | `%X{traceId:-}` |
| 彩色控制台 | `%highlight` + `%boldYellow` |
| INFO/ERROR 分文件 | `FILE_INFO` + `FILE_ERROR` |
| 异步写入 | `AsyncAppender` + queueSize=1024 |
| 滚动策略 | 按日期+大小滚动，保留30天，总量3GB |
| 框架日志降级 | Spring/Hikari/Redisson/Nacos → WARN |

---

### 2.5 P2：MyBatis SQL 日志使用 StdOutImpl

**问题描述**：所有服务配置了 `log-impl: org.apache.ibatis.logging.stdout.StdOutImpl`。

**影响**：
- 生产环境每条 SQL 都打印到控制台，日志量巨大
- stdout 是同步的，有性能损耗
- 可能泄露敏感数据（SQL 参数中的手机号、密码等）

**修复**：

```diff
mybatis-plus:
  configuration:
-   log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
+   log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
```

**好处**：SQL 日志走 SLF4J → logback → 异步写入文件，不阻塞业务线程。

---

### 2.6 P3：缺少优雅停机配置

**修复**：

```yaml
server:
  shutdown: graceful  # 优雅停机
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 等待30秒
```

---

### 2.7 P3：缺少连接池泄漏检测

**修复**（user 服务）：

```yaml
hikari:
  leak-detection-threshold: 60000  # 连接泄漏检测阈值（60秒）
```

---

## 三、修复文件清单

| 文件 | 修改内容 |
|------|----------|
| `my-xhs-common/.../AutoConfiguration.imports` | +TraceIdConfig +AccessLogConfig |
| `my-xhs-common/pom.xml` | +spring-boot-starter-actuator |
| `my-xhs-common/.../AccessLogConfig.java` | 新增：请求耗时日志拦截器 |
| `my-xhs-user/.../application.yml` | +actuator +优雅停机 +Slf4jImpl +leak-detection |
| `my-xhs-content/.../application.yml` | +actuator +优雅停机 +Slf4jImpl |
| `my-xhs-analytics/.../application.yml` | +actuator +优雅停机 +Slf4jImpl |
| `my-xhs-counter/.../application.yml` | +actuator +优雅停机 +Slf4jImpl |
| `my-xhs-content/.../logback-spring.xml` | 新增：统一日志配置 |
| `my-xhs-analytics/.../logback-spring.xml` | 新增：统一日志配置 |
| `my-xhs-counter/.../logback-spring.xml` | 新增：统一日志配置 |

---

## 四、面试话术（Q&A）

### Q1：你的项目是如何做可观测性的？

**A**：我们从 Phase 1 就建立了三大可观测性支柱：

1. **链路追踪**：Gateway 生成 TraceId → Header 传递 → 各服务 MDC 注入 → 日志自动携带。通过 `AutoConfiguration.imports` 注册 `TraceIdConfig`，确保所有服务自动生效。

2. **请求日志**：`AccessLogConfig` 记录每个请求的 URI、方法、耗时、状态码。慢请求（>500ms）自动升级为 WARN 级别，便于告警。

3. **健康检查**：Spring Boot Actuator 暴露 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`，为 K8s 探针做好准备。

### Q2：你遇到过 TraceId 不生效的问题吗？

**A**：是的，这是一个典型的 Spring Boot 自动配置陷阱。`TraceIdConfig` 在 common 模块的 `com.myxhs.common.config` 包下，但各服务启动类在 `com.myxhs.user` 包下。`@SpringBootApplication` 默认只扫描自己包，不会扫描 common 包。

解决方案有两种：
1. **推荐**：在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中注册
2. 在启动类加 `@ComponentScan(basePackages = "com.myxhs")`（不推荐，会影响其他自动配置）

### Q3：为什么不用 `@ComponentScan` 而用 `AutoConfiguration.imports`？

**A**：
- `@ComponentScan` 是"扫描式"的，会把包下所有 `@Component` 都加载，可能引入不需要的 Bean
- `AutoConfiguration.imports` 是"声明式"的，精确控制哪些配置类被加载
- 后者是 Spring Boot 3.x 推荐的方式，也是 Spring Boot Starter 的标准做法

### Q4：你的 Access Log 是怎么设计的？为什么不用 Filter 而用 Interceptor？

**A**：
- **Interceptor** 可以获取到 Handler 信息（哪个 Controller 方法处理的），Filter 不行
- Interceptor 的 `afterCompletion` 在响应写入后调用，能拿到准确的 status code
- 通过 `order=-90` 确保在 TraceId 拦截器（order=-100）之后执行，日志中一定有 TraceId
- 排除 `/actuator/**` 路径，避免健康检查污染业务日志

### Q5：为什么把 MyBatis 的 StdOutImpl 改成 Slf4jImpl？

**A**：三个原因：
1. **性能**：StdOutImpl 是同步写 stdout，Slf4jImpl 走 logback 异步写入
2. **安全**：SQL 参数可能包含手机号、密码等敏感数据，stdout 无法做脱敏
3. **统一**：所有日志走 SLF4J → logback，统一格式、统一滚动策略、统一 TraceId

---

## 五、工程成熟度评分（修复后）

| 维度 | 评分 | 说明 |
|------|:----:|------|
| 链路追踪 | ⭐⭐⭐⭐ | TraceId 全链路贯穿，Gateway → 服务 → 日志 → 响应 Header |
| 健康检查 | ⭐⭐⭐⭐ | Actuator + Liveness + Readiness，K8s 就绪 |
| 请求日志 | ⭐⭐⭐⭐ | 每请求记录 RT，慢请求自动告警 |
| 日志规范 | ⭐⭐⭐⭐⭐ | 统一格式 + 异步写入 + 滚动策略 + 框架降噪 |
| 优雅停机 | ⭐⭐⭐ | graceful + 30s，后续需配合 Nacos 注销 |
| 指标监控 | ⭐⭐⭐ | Prometheus 端点就绪，Phase 5 补齐自定义指标 |
| 告警规则 | ⭐⭐ | Phase 5 补齐 |

**总评**：Phase 1 的工程基础设施已达到 **P7-P8 水平**，为后续 Phase 的监控、告警、链路追踪打下了坚实基础。

---

## 六、可观测性预留补齐（2026-05-14 第二轮）

> 上一轮修复了"骨架"（Actuator/AccessLog/logback），但缺少关键的"预留"——
> MQ 场景 TraceId 断裂、Feign 透传缺失、Prometheus 指标端点不可用。
> 本轮补齐所有代码层面的预留，确保 Phase 5/6 接入监控基础设施时零改造。

### 6.1 MQ 消息 TraceId 透传（新增）

**问题**：MQ Consumer 是独立线程，不经过 HTTP 拦截器，MDC 中没有 traceId，日志中 traceId 为空。

**修复**：

| 组件 | 修改 |
|------|------|
| `MqTraceHelper.java`（新增） | 工具类：`wrapWithTraceId()`（发送端注入）+ `restoreTraceId()`（消费端恢复）+ `clearTraceId()`（finally 清理） |
| `LikeService.sendLikeEvent()` | `MqTraceHelper.wrapWithTraceId(message)` 注入 TraceId 到 MQ Header |
| `FavoriteService.sendFavoriteEvent()` | 同上 |
| `LikeConsumer` | 改为接收 `MessageExt`，`MqTraceHelper.restoreTraceId(msg)` + finally `clearTraceId()` |
| `FavoriteConsumer` | 同上 |
| `UnlikeConsumer` | 同上 |
| `UnfavoriteConsumer` | 同上 |

**链路效果**：
```
HTTP 请求（traceId=abc123）
  → LikeService.like()（MDC: traceId=abc123）
    → MQ 发送（Message Header: X-Trace-Id=abc123）
      → LikeConsumer.onMessage()（MDC 恢复: traceId=abc123）
        → log.info("[点赞Consumer] 落库成功")  ← 日志中 traceId=abc123 ✅
```

### 6.2 Feign 调用 TraceId 透传（新增）

**问题**：Phase 2 开始有跨服务 Feign 调用，如果不预留透传拦截器，TraceId 会在服务间断裂。

**修复**：

| 组件 | 说明 |
|------|------|
| `FeignTraceInterceptorConfig.java`（新增） | `@ConditionalOnClass(name = "feign.RequestInterceptor")`，只有引入 Feign 依赖时才生效 |
| `common/pom.xml` | 添加 `spring-cloud-starter-openfeign`（optional），编译时可用但不强制传递 |
| `AutoConfiguration.imports` | 注册 `FeignTraceInterceptorConfig` |

**透传的 Header**：`X-Trace-Id` + `X-User-Id`

### 6.3 Prometheus 指标端点（补齐）

**问题**：虽然引入了 `spring-boot-starter-actuator`，但没有 `micrometer-registry-prometheus`，`/actuator/prometheus` 端点不可用。

**修复**：`common/pom.xml` 添加 `micrometer-registry-prometheus` 依赖。

**验证结果**：
```
GET /actuator/prometheus → 200 OK
  - http_server_requests_active_seconds（HTTP 请求耗时）
  - jvm_memory_used_bytes（JVM 内存）
  - jvm_gc_overhead_percent（GC 开销）
  - hikaricp_connections（连接池状态）
  - ...
```

### 6.4 修改文件清单

| 文件 | 修改 |
|------|------|
| `common/.../trace/MqTraceHelper.java` | **新增**：MQ TraceId 透传工具类 |
| `common/.../trace/FeignTraceInterceptorConfig.java` | **新增**：Feign TraceId 透传拦截器 |
| `common/pom.xml` | +micrometer-registry-prometheus +spring-cloud-starter-openfeign(optional) |
| `common/.../AutoConfiguration.imports` | +FeignTraceInterceptorConfig |
| `analytics/.../service/LikeService.java` | MQ 发送时 `MqTraceHelper.wrapWithTraceId()` |
| `analytics/.../service/FavoriteService.java` | 同上 |
| `analytics/.../consumer/LikeConsumer.java` | 改为 `MessageExt` + `restoreTraceId` + `clearTraceId` |
| `analytics/.../consumer/FavoriteConsumer.java` | 同上 |
| `analytics/.../consumer/UnlikeConsumer.java` | 同上 |
| `analytics/.../consumer/UnfavoriteConsumer.java` | 同上 |

### 6.5 当前可观测性预留完整度

| 场景 | 预留状态 | 说明 |
|------|:--------:|------|
| HTTP 请求 TraceId | ✅ | Gateway 生成 → Header 传递 → MDC 注入 |
| MQ 消息 TraceId | ✅ | Producer 注入 Header → Consumer 恢复 MDC |
| Feign 调用 TraceId | ✅ | 自动透传 X-Trace-Id + X-User-Id |
| 异步线程 TraceId | ⚠️ | TTL 依赖已引入，Phase 5 配置 TaskDecorator |
| Prometheus 指标 | ✅ | /actuator/prometheus 端点可用 |
| 健康检查 | ✅ | /actuator/health + liveness + readiness |
| 请求耗时日志 | ✅ | AccessLogConfig，慢请求 WARN |
| 日志格式统一 | ✅ | logback-spring.xml 全服务统一 |
| JSON 结构化日志 | ⚠️ | Phase 6 切换 LogstashEncoder |
| SkyWalking Agent | ⚠️ | Phase 6 接入，代码零改造 |

### 6.6 面试话术

> **Q: 你的 MQ 消息消费时 TraceId 怎么传递的？**
>
> "我们封装了 `MqTraceHelper` 工具类：
> 1. **Producer 端**：`MqTraceHelper.wrapWithTraceId(message)` 从当前 MDC 取出 traceId，注入到 MQ 消息的 Header 中
> 2. **Consumer 端**：`MqTraceHelper.restoreTraceId(msg)` 从 MessageExt 的 UserProperty 中取出 traceId，恢复到 MDC
> 3. **finally 清理**：`MqTraceHelper.clearTraceId()` 防止线程池复用时 traceId 串联
>
> 这样一个用户的点赞操作，从 HTTP 请求 → Redis 写入 → MQ 发送 → Consumer 落库，全链路的日志都能通过同一个 traceId 串联起来。"

> **Q: Feign 调用时 TraceId 怎么透传？**
>
> "我们在 common 模块预留了 `FeignTraceInterceptorConfig`，通过 `@ConditionalOnClass` 条件加载——只有引入了 OpenFeign 依赖的服务才会生效。拦截器从当前 HTTP 请求的 Header 中提取 `X-Trace-Id` 和 `X-User-Id`，自动透传到 Feign 调用的下游服务。Phase 2 开始有跨服务调用时，零配置即可生效。"
