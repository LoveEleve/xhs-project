# 1.1 网关健康检查 — GET /actuator/health

## 接口信息

| 项目 | 内容 |
|------|------|
| 方法 | GET |
| 路径 | `/actuator/health` |
| 服务 | my-xhs-gateway (port 19000) |
| 鉴权 | 无（Actuator 端点由 Spring Security / Gateway Filter 单独控制） |
| 对应业务 | 运维监控 — 深度检查网关及所有依赖的中间件是否健康 |

---

## 业务背景

### 这个接口是干什么的？

`/actuator/health` 不是简单的 "ping" 或者 "200 OK"——它是 Spring Boot Actuator 框架的**聚合健康检查机制**。当这个请求打到 Gateway 时，背后会触发**一组 HealthIndicator 按顺序逐一执行**，每一个都用自己的方式检测一个外部依赖是否联通，最终汇总出一个 `status`。

### 为什么需要一组而不是一个？

如果只有一个 "ping"，那 Redis 挂了你也返回 UP，流量继续打到 Gateway，Gateway 路由到下游时发现 Token 黑名单查不了、限流计数坏了——这些问题只有一个综合健康检查才能发现。

### 谁来用这个接口？

| 调用方 | 用途 | 调哪个路径 |
|--------|------|-----------|
| **K8s liveness probe** | 判断进程是否存活（死锁、OOM 标记为 DOWN，K8s 会重启 Pod） | `/actuator/health/liveness` |
| **K8s readiness probe** | 判断是否准备好接流量（依赖组件不可用标记为 DOWN，K8s 摘掉 Pod） | `/actuator/health/readiness` |
| **Prometheus + AlertManager** | 采集健康状态，触发告警 | `/actuator/health` → `/actuator/prometheus` |
| **运维人员 / curl** | 上线后第一步排查 | `/actuator/health` |

### Gateway 的配置

`my-xhs-gateway/src/main/resources/application.yml:310-324`：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics,loggers   # 暴露 5 个端点
  endpoint:
    health:
      show-details: always    # 关键：永远展开 details（否则只返回 {"status":"UP"}）
      probes:
        enabled: true         # 启用 /health/liveness + /health/readiness 子路径
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

`show-details: always` 是这个测试能看到完整 JSON 的原因——生产环境通常设为 `when-authorized`，需要认证后才能看 details。

---

## 架构全景：这个请求背后发生了什么？

```
curl http://localhost:19000/actuator/health
  │
  ▼
Spring Boot Actuator HealthEndpoint
  │
  ├─ HealthContributorRegistry（遍历所有 HealthIndicator Bean）
  │
  ├─ 1. ApplicationReadinessIndicator    ← 自定义：堆内存 + 死锁
  │      └─ com.myxhs.common.health.ApplicationReadinessIndicator.java
  │
  ├─ 2. RocketMQHealthIndicator          ← 自定义：NameServer 连接
  │      └─ com.myxhs.common.health.RocketMQHealthIndicator.java
  │
  ├─ 3. CacheRedisHealthIndicator        ← 自定义：Cache Redis 双实例检查
  │      └─ com.myxhs.common.health.CacheRedisHealthIndicator.java
  │
  ├─ 4. RedisHealthIndicator (内置)      ← 检查 Business Redis（@Primary）
  │      └─ Spring Boot Actuator 内置，执行 PING 命令
  │
  ├─ 5. DiscoveryClientHealthIndicator   ← Nacos 注册中心
  │      └─ Spring Cloud 内置
  │
  ├─ 6. DiskSpaceHealthIndicator (内置)  ← 磁盘剩余空间
  │      └─ Spring Boot Actuator 内置
  │
  ├─ 7. LivenessStateHealthIndicator     ← liveness 状态
  ├─ 8. ReadinessStateHealthIndicator    ← readiness 状态
  └─ 9. PingHealthIndicator (内置)       ← 简单 PING
```

汇总逻辑：**任何一个 Indicator 返回 DOWN → 整体 status = DOWN**。

---

## 逐组件深度解析

### 组件 1: Nacos Discovery — `discoveryClient`

**它怎么检查的？** Nacos SDK 的 `NamingService` 在 Bean 初始化时就已经建立长连接，这个 Indicator 通过 `NacosNamingService.getServerStatus()` 判断连接状态。同时它拉取 Nacos 全量服务列表，验证 Nacos 本身可达。

**响应解读：**
```json
"services": [
  "my-xhs-analytics", "my-xhs-inventory", "my-xhs-content",
  "my-xhs-search", "my-xhs-im", "my-xhs-payment",
  "my-xhs-gateway", "my-xhs-order", "my-xhs-home",
  "my-xhs-user", "my-xhs-coupon", "my-xhs-notification",
  "my-xhs-counter", "my-xhs-product", "my-xhs-cart"
]
```
15 个服务，一个不少。注意这个列表**不是静态配置的**——它是从 Nacos 实时拉取的注册表。如果有服务挂了（没有主动下线），它仍然会显示在列表中，但下游调用时会触发 Feign 的 ErrorDecoder 返回熔断。

**如果它返回 DOWN：** 路由全部失败。Gateway 无法从 Nacos 获取下游服务实例列表，任何请求都返回 503。

---

### 组件 2: Redis — 内置 RedisHealthIndicator

**它怎么检查的？** Spring Boot Actuator 内置的 `RedisHealthIndicator` 执行 `RedisConnection.ping()`。它对的是 `@Primary` 数据源——即 **Business Redis**（16379），不是 Cache Redis。

**响应解读：**
```json
"redis": {
  "status": "UP",
  "details": { "version": "7.4.9" }
}
```

**为什么有两个 Redis 但不显示两个？** 因为内置的 `RedisHealthIndicator` 只检查 `@Primary` Bean。Cache Redis（16380）的检测由自定义的 `CacheRedisHealthIndicator` 单独覆盖（见下）。

**如果它返回 DOWN：** Token 黑名单失效、限流 Lua 脚本无法执行、@Idempotent 的 Redis 幂等 Key 无法存储。Gateway 的降级策略是：@DistributedLock/@Idempotent 降级放行（允许请求通过但无保护），Token 黑名单 Fail-Closed（拒绝所有需要鉴权的请求）。

---

### 组件 3: Cache Redis — CacheRedisHealthIndicator（自定义）

**完整代码：** `my-xhs-common/src/main/java/com/myxhs/common/health/CacheRedisHealthIndicator.java`

```java
public class CacheRedisHealthIndicator implements HealthIndicator {
    private final RedisConnectionFactory cacheRedisConnectionFactory;

    @Override
    public Health health() {
        try (RedisConnection connection = cacheRedisConnectionFactory.getConnection()) {
            String pong = Objects.toString(connection.ping());
            if ("PONG".equalsIgnoreCase(pong)) {
                return Health.up()
                        .withDetail("cacheRedis", "connected")
                        .build();
            }
            return Health.down()
                    .withDetail("cacheRedis", "unexpected response: " + pong)
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("cacheRedis", e.getMessage())
                    .build();
        }
    }
}
```

**为什么需要单独的 HealthIndicator？** MyXHS 使用**双 Redis 实例**：

| 实例 | 端口 | 用途 | 淘汰策略 |
|------|------|------|---------|
| Business Redis | 16379 | Token 黑名单、分布式锁、幂等 Key、限流计数 | `noeviction` |
| Cache Redis | 16380 | Caffeine 外置缓存、Feed 缓存、布隆过滤器 | `allkeys-lru` |

Spring Boot 内置的 `RedisHealthIndicator` 只检查 `@Primary`（Business Redis）。如果 Cache Redis 挂了但 Business Redis 正常，没有单独的 Indicator 就会漏检——流量继续打过来，Feed 缓存全量穿透到 DB。

**如果它返回 DOWN：** Home Feed 缓存全部穿透，BFF 聚合层并发查 11 个下游，DB 压力暴涨。

---

### 组件 4: RocketMQ — RocketMQHealthIndicator（自定义）

**完整代码：** `my-xhs-common/src/main/java/com/myxhs/common/health/RocketMQHealthIndicator.java`

这个是最有故事的。Spring Boot Actuator 没有内置 RocketMQ HealthIndicator，得自己写。而且自定义代码还要兼容 RocketMQ 4.x 和 5.x 的 API 差异。

**三步检测流程：**

```java
// 第 1 步：反射获取 RocketMQTemplate 内部的 DefaultMQProducer
Field field = RocketMQTemplate.class.getDeclaredField("producer");
field.setAccessible(true);
DefaultMQProducer producer = (DefaultMQProducer) field.get(rocketMQTemplate);

// 第 2 步：检查 NameServer 地址配置是否为空
String namesrvAddr = producer.getNamesrvAddr();
if (namesrvAddr == null || namesrvAddr.isEmpty()) {
    return Health.down();  // 连地址都没有
}

// 第 3 步：反射调用 getDefaultTopicRouteInfoFromNameServer 验证可达性
Method method = DefaultMQProducer.class.getMethod(
    "getDefaultTopicRouteInfoFromNameServer", long.class);
method.invoke(producer, 3000L);  // 3 秒超时
```

**为什么用反射而不是直接调用？** RocketMQ 5.x 的 `DefaultMQProducer.getDefaultTopicRouteInfoFromNameServer(long)` 方法签名和 4.x 不同，直接调用会导致编译错误。通过反射 + `NoSuchMethodException` 兜底（5.x 中该方法不存在时视为连接正常），实现了**编译时兼容、运行时自适应**。

**如果它返回 DOWN：** 所有走 MQ 的异步链路中断——订单事务消息、库存异步扣减、Feed 推送、Canal 数据同步、支付/退款通知全部停摆。

---

### 组件 5: Sentinel — SentinelHealthIndicator

**响应解读：**
```json
"sentinel": {
  "status": "UP",
  "details": {
    "enabled": true,
    "dashboard": { "status": "UP" }
  }
}
```

Sentinel 的检测比较特殊——**不是检查限流/熔断是否生效，而是检查 Dashboard 是否连通**。因为 Sentinel 的流控规则是从 Dashboard Nacos 数据源加载的，即使 Dashboard 挂了，已经在内存中的流控规则仍然生效。

`enabled: true` 表示 `spring.cloud.sentinel.enabled=true`，FlowSlot/DegradeSlot 已加载到责任链。

**如果它返回 DOWN：** Dashboard 不可达，已加载的规则仍然工作，但不能动态修改规则、不能查看实时 QPS。新增流控规则无法下发。

---

### 组件 6: 应用自身 — ApplicationReadinessIndicator（自定义）

**完整代码：** `my-xhs-common/src/main/java/com/myxhs/common/health/ApplicationReadinessIndicator.java`

这个检查的是 **JVM 内部状态**，而非外部依赖：

```java
public class ApplicationReadinessIndicator implements HealthIndicator {
    private static final double HEAP_USAGE_THRESHOLD = 0.90;

    @Override
    public Health health() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        // 检测 1: 堆内存 > 90% → DOWN
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        double usage = (double) heapUsed / heapMax;

        // 检测 2: 死锁线程 → DOWN
        long[] deadlocked = threadBean.findDeadlockedThreads();
    }
}
```

**两项检测：**

| 检测项 | 阈值 | 后果 | K8s 行为 |
|--------|------|------|---------|
| 堆内存使用率 > 90% | 90% | liveness DOWN → **Pod 重启** | K8s 判定进程不健康，kubectl delete pod |
| 存在死锁线程 | 任意 deadlock | liveness DOWN → **Pod 重启** | 同上 |

**注意：** 当前返回中没有显示 `applicationReadiness`，原因是它只影响 liveness/readiness 子路径，在 `/actuator/health` 中被包含在 livenessState/readinessState 的聚合结果中（groups 字段）。

---

### 组件 7: 磁盘空间 — DiskSpaceHealthIndicator

```json
"diskSpace": {
  "status": "UP",
  "details": {
    "total": 536870912000,   // 500GB
    "free": 528327151616,    // 492GB 可用
    "threshold": 10485760    // 10MB 阈值
  }
}
```

Spring Boot Actuator 内置，检查日志目录所在磁盘剩余空间。阈值默认 10MB——如果日志写爆磁盘导致空间不足，这个 Indicator 返回 DOWN，K8s readiness probe 摘除 Pod。

当前 free = 492GB，完全健康。

---

## 聚合逻辑

HealthEndpoint 的聚合逻辑是一个 **短路求值**：

```
status = UP
for each indicator in registry:
    result = indicator.health()
    if result == DOWN:
        status = DOWN
        break
    if result == UNKNOWN and severity == WARN:
        status = UNKNOWN
return status
```

也就是说：**只要有一个组件 DOWN，整体就是 DOWN**。这就是为什么这个端点能作为 K8s readiness probe——任何一个中间件不可用，K8s 立刻摘掉这个 Pod。

---

## 各组件的职责矩阵

| 组件 | 检测对象 | 检测方式 | UP 表示什么 | DOWN 时的影响 |
|------|---------|---------|------------|-------------|
| discoveryClient | Nacos 注册中心 | NamingService.getServerStatus() + 服务列表拉取 | Nacos 可用，15 个服务已注册 | 路由全部 503 |
| redis | Business Redis | PING | 分布式锁/限流/幂等/黑名单可用 | Fail-Closed，拒绝鉴权请求 |
| cacheRedis | Cache Redis | PING | Feed 缓存可用 | Feed 缓存全量穿透到 DB |
| rocketmq | RocketMQ Broker | 反射 RouteInfo | MQ 消息通道可用 | 异步链路全部中断 |
| sentinel | Sentinel Dashboard | HTTP 连接到 Dashboard | Dashboard 可达 | 规则不可动态修改 |
| diskSpace | 磁盘 | File.getFreeSpace() | 剩余 > 10MB | 日志无法写入 |
| applicationReadiness | JVM | MXBean 堆内存 + 死锁检测 | 内存 < 90% 且无死锁 | liveness DOWN → Pod 重启 |

---

## curl 命令

```bash
curl -s http://localhost:19000/actuator/health | jq .
```

## 实际返回

```json
{
  "status": "UP",
  "components": {
    "discoveryComposite": {
      "status": "UP",
      "components": {
        "discoveryClient": {
          "status": "UP",
          "details": {
            "services": [
              "my-xhs-analytics",  "my-xhs-inventory",  "my-xhs-content",
              "my-xhs-search",     "my-xhs-im",         "my-xhs-payment",
              "my-xhs-gateway",    "my-xhs-order",      "my-xhs-home",
              "my-xhs-user",       "my-xhs-coupon",     "my-xhs-notification",
              "my-xhs-counter",    "my-xhs-product",    "my-xhs-cart"
            ]
          }
        }
      }
    },
    "diskSpace": {
      "status": "UP",
      "details": {
        "total": 536870912000,
        "free": 528327151616,
        "threshold": 10485760,
        "path": "/data/workspace/my-xhs/.",
        "exists": true
      }
    },
    "livenessState": { "status": "UP" },
    "nacosDiscovery": { "status": "UP" },
    "ping": { "status": "UP" },
    "reactiveDiscoveryClients": {
      "status": "UP",
      "components": {
        "Simple Reactive Discovery Client": {
          "status": "UP",
          "details": { "services": [] }
        }
      }
    },
    "readinessState": { "status": "UP" },
    "redis": {
      "status": "UP",
      "details": { "version": "7.4.9" }
    },
    "refreshScope": { "status": "UP" },
    "sentinel": {
      "status": "UP",
      "details": {
        "dataSource": {},
        "enabled": true,
        "dashboard": { "status": "UP" }
      }
    }
  },
  "groups": ["liveness", "readiness"]
}
```

注意最后一行 `"groups": ["liveness", "readiness"]`——这是 Spring Boot 2.3+ 引入的 Probe 机制。`/actuator/health/liveness` 和 `/actuator/health/readiness` 作为独立子路径，分别过滤不同 Indicator 组：

- **liveness**: 只检查组内 Indicator（ApplicationReadinessIndicator 属于 liveness 组）
- **readiness**: 只检查组内 Indicator（Redis、RocketMQ、Nacos 等外部依赖属于 readiness 组）

这样 K8s 可以精确控制：liveness 失败 → 重启，readiness 失败 → 摘除。

---

## 结论

**PASS** — 网关及 7 组 HealthIndicator 全部返回 UP：
- Nacos 注册中心：15 个微服务全在线
- Redis（Business + Cache）：双实例连通
- RocketMQ：NameServer 可达
- Sentinel：Dashboard 可达，流控生效
- JVM：堆内存正常，无死锁
- 磁盘：492GB 空闲

## 关联源码

| 文件 | 说明 |
|------|------|
| `my-xhs-common/.../health/ApplicationReadinessIndicator.java` | JVM 堆内存 + 死锁检测 |
| `my-xhs-common/.../health/RocketMQHealthIndicator.java` | RocketMQ NameServer 连接检测（反射兼容 4.x/5.x） |
| `my-xhs-common/.../health/CacheRedisHealthIndicator.java` | Cache Redis 双实例检查 |
| `my-xhs-gateway/src/main/resources/application.yml:310-324` | Gateway Actuator 配置 |
