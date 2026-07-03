# my-xhs P8 级别技术深化路线图

> 基于综合评审报告 + 小马哥训练营 4 期大纲 + P8 级别工程标准
> 创建日期：2026-05-31

---

## 前置：外部服务部署清单

### 已部署（docker-compose.yml 已覆盖）

| 服务 | 端口 | 版本 | 用途 | 状态 |
|------|------|------|------|:---:|
| MySQL × 4 | 13306-13309 | 8.0 | user/content/order/inventory 四个实例 | ✅ |
| Redis | 16379 | 7 | 缓存 + 分布式锁 + 限流 + 幂等 | ✅ |
| Nacos | 18848 | 2.3.2 | 服务注册发现 | ✅ |
| RocketMQ NameServer | 19876 | 5.1.4 | 消息队列 | ✅ |
| RocketMQ Broker | 10911 | 5.1.4 | 消息队列 | ✅ |
| Elasticsearch | 19200 | 8.12.2 | 搜索 + 推荐 | ✅ |
| Canal | 11111 | 1.1.7 | Binlog 增量同步 | ✅ |
| XXL-Job Admin | 18080 | 2.4.2 | 分布式调度 | ✅ |
| Prometheus | 19090 | 2.48.1 | 指标采集 | ✅ |
| Grafana | 13000 | 10.2.3 | 可视化 + 告警 | ✅ |
| SkyWalking OAP | 11800/12800 | 9.7.0 | 链路追踪后端 | ✅ |
| SkyWalking UI | 18081 | 9.7.0 | 链路追踪 UI | ✅ |

### 需要新增部署的外部服务

| # | 服务 | 推荐端口 | 版本 | 用途 | 优先级 |
|---|------|----------|------|------|:---:|
| 1 | **Sentinel Dashboard** | 18082 | 1.8.8 | 实时限流规则管理 + 熔断降级控制台 | 🔴 P0 |
| 2 | **JD-hotkey Worker** | 9900 | 1.0.0 | 热点 Key 自动探测上报 | 🔴 P0 |
| 3 | **JD-hotkey Dashboard** | 9901 | 1.0.0 | 热点数据可视化 | 🟡 P1 |
| 4 | **Jenkins** | 18083 | 2.462+ | CI/CD 自动化构建 | 🔴 P0 |
| 5 | **Nexus/阿里云效** | 18084 | — | 私有 Maven 仓库 | 🟡 P1 |
| 6 | **SkyWalking Agent 挂载** | — | 9.7.0 | JVM -javaagent 参数 | 🔴 P0 |
| 7 | **MySQL 读写分离 (ProxySQL)** | 16033 | 2.6 | 所有 MySQL 实例的读写分离代理 | 🟡 P1 |
| 8 | **GoReplay** | — | 1.3 | 流量录制回放压测 | 🟡 P1 |
| 9 | **JMeter 分布式** | — | 5.6 | 分布式压测集群 | 🟡 P1 |
| 10 | **ELK/EFK 日志平台** | 15601 | 8.x | 分布式日志收集 + 搜索 | 🟢 P2 |
| 11 | **Redis Cluster/Sentinel** | — | 7 | 高可用 Redis（当前单点） | 🟢 P2 |
| 12 | **Nacos 集群** | 18848×3 | 2.3.2 | Nacos 高可用集群（当前单点） | 🟢 P2 |

---

## 一、分层限流体系全景设计（核心深度内容）

### 限流分层架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                  P8 级别四层纵深限流防御体系                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  L1: 网关层限流 ───────── 总流量入口，与应用无关                       │
│      ├─ Sentinel 集群限流 (目前单机)                                  │
│      ├─ 多维 KeyResolver (IP/用户/API/租户)                           │
│      ├─ 网关级 TokenServer 集群限流                                   │
│      └─ 预热/匀速排队 (WarmUp/RateLimiter)                            │
│                                                                     │
│  L2: Web Server 限流 ───── 容器级别，与应用绑定                         │
│      ├─ Tomcat Connector 定制 (maxConnections/Acceptor线程/超时)        │
│      ├─ Tomcat 线程池动态调整 (JMX MBean 监控 + 自适应)                 │
│      ├─ Netty (Gateway) EventLoop 调优                                │
│      └─ Undertow/Jetty 对比评估（目前全 Tomcat）                       │
│                                                                     │
│  L3: Web Framework 限流 ── 框架级别，特定资源保护                      │
│      ├─ Spring MVC HandlerInterceptor 限流拦截器                       │
│      ├─ Spring WebFlux WebFilter 限流                                 │
│      ├─ @RateLimit 注解优化（当前已有但需升级）                         │
│      └─ Controller 方法级精细化限流                                    │
│                                                                     │
│  L4: 组件级限流 ───── 存储/中间件级别，防止下游被打垮                   │
│      ├─ Redis 命令级限流（连接池保护 + 热点 Key 管控）                  │
│      ├─ MyBatis SQL 执行限流（慢 SQL 熔断 + 查询频率控制）               │
│      ├─ HikariCP 连接池保护（等待队列控制 + 连接超时降级）              │
│      ├─ Feign 调用限流（Sentinel @FeignClient fallback）               │
│      └─ RocketMQ 消费限流（pullBatchSize + 消费线程数控制）            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

### L1：网关层限流深度方案

#### 当前状态

| 维度 | 状态 | 问题 |
|------|:---:|------|
| 引擎 | Sentinel (LeapArray 滑动窗口) | ✅ 可用 |
| 模式 | 单机限流 | 🔴 多网关实例各自计算，总 QPS = N×单机值 |
| 维度 | 仅按 route ID (服务级) | 🔴 无 IP/用户/API 维度 |
| 规则管理 | Nacos 动态推送兜底 | 🟡 无 Dashboard，改规则需重启 |
| 集群限流 | 未实现 | 🔴 Sentinel Token Server 未部署 |

#### P8 级别目标方案

##### 1.1 Sentinel 集群限流（Turn Server 模式）

```
架构：
┌──────────────┐    ┌──────────────┐
│  Gateway-1   │    │  Gateway-2   │
│ (embedded    │    │ (embedded    │
│  TokenClient)│    │  TokenClient)│
└──────┬───────┘    └──────┬───────┘
       │ 请求 Token         │ 请求 Token
       ▼                    ▼
┌──────────────────────────────────────┐
│        Sentinel Token Server          │
│        (独立部署进程)                   │
│  规则：整个集群 QPS 由 Token Server    │
│       统一分配，而非各实例各自计算      │
└──────────────────────────────────────┘
```

**实现要点**：
- Token Server 独立部署（可嵌入 Nacos 或独立 JVM 进程）
- Gateway 配置 `cluster-mode=true`，指定 Token Server 地址
- 所有网关实例共享同一条集群流控规则
- 降级策略：Token Server 不可用时降级为各实例本地限流

**配置示例**（`application.yml`）：

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: 21.91.124.110:18082  # Sentinel Dashboard
        port: 8719  # 每个实例不同的上报端口
      datasource:
        flow:
          nacos:
            server-addr: 21.91.124.110:18848
            data-id: my-xhs-gateway-cluster-flow-rules
            group-id: SENTINEL_GROUP
            rule-type: gw-flow  # 网关规则类型
        param-flow:
          nacos:
            server-addr: 21.91.124.110:18848
            data-id: my-xhs-gateway-param-flow-rules
            group-id: SENTINEL_GROUP
            rule-type: gw-param-flow  # 热点参数规则
```

##### 1.2 多维 KeyResolver 定制

继承当前 `RateLimitFilter`，增加多维度 KeyResolver：

```java
// 需要创建的关键类
// 1. UserKeyResolver - 按 userId 限流
// 2. IpKeyResolver - 按来源 IP 限流  
// 3. ApiKeyResolver - 按 API 路径+方法限流
// 4. CompositeKeyResolver - 组合维度（如 IP+API）
```

**为什么需要 KeyResolver？**

Sentinel 网关流控有两种模式：
- `GatewayFlowRule`（路由级）：当前 my-xhs 用的，只能按 route ID 限流
- `GatewayParamFlowRule`（参数级）：可以按请求参数（IP/User/Header）限流

**需要新增**：按用户 ID 限流（防止单用户刷接口）、按 IP 限流（防止单一 IP 攻击）

##### 1.3 网关限流规则分档设计

不再用固定 14 条规则，改为：

```yaml
# 示例：Nacos 中的 my-xhs-gateway-param-flow-rules
rules:
  # 第一档：全局总控（防止整体过载）
  - resource: ALL_ROUTES
    count: 5000        # 网关总 QPS 上限
    grade: 1           # QPS 模式
  
  # 第二档：按服务（业务隔离）
  - resource: order-service
    count: 500
  - resource: payment-service  
    count: 300
  
  # 第三档：按用户/IP 精细化（防刷）
  - resource: login-api
    paramItem:
      parseStrategy: URL_PARAM
      fieldName: userId
    count: 10           # 单用户每秒最多 10 次登录
    burstCount: 3       # 允许突发 3 次
  
  # 第四档：热点参数 (针对秒杀/热门商品)
  - resource: product-detail-api
    paramItem:
      parseStrategy: URL_PARAM
      fieldName: productId
    count: 100          # 热门商品接口限流
    durationInSec: 1
```

##### 1.4 控制台效果检测

**与当前的区别**：
- **现在**：改限流规则 → 改代码/改 Nacos 配置 → 重启 → 生效
- **P8 级别**：打开 Sentinel Dashboard → 实时看到流量曲线 → 拖拽调整阈值 → 秒级生效 → 观察效果

---

### L2：Web Server 层限流（Tomcat 深度定制）

#### 当前状态（严重不足）

```yaml
# 当前 13 个服务的 Tomcat 配置——千篇一律
server:
  tomcat:
    threads:
      max: 200
      min-spare: 20
    max-connections: 8192
    accept-count: 100
```

**问题**：
- 所有服务一样的配置 —— 下单服务和高流量的搜索服务用同一套参数？不合理
- 没有 JMX 暴露，无法实时监控线程池状态
- 没有 Tomcat Connector 定制（`maxKeepAliveRequests`、`connectionTimeout`、`maxSwallowSize` 等全是默认值）
- 没有自定义 Acceptor 线程池
- `accept-count=100` 意味着等待队列只有 100，满了直接拒绝，但你根本不知道

#### P8 级别方案

##### 2.1 按服务分级配置 Tomcat

| 服务类别 | 代表服务 | max-threads | max-connections | accept-count | 理由 |
|----------|---------|:---:|:---:|:---:|------|
| 高频读取 | search, home | 400 | 20000 | 500 | ES 查询 I/O 密集 |
| 核心交易 | order, payment, inventory | 200 | 8192 | 200 | 避免慢事务占满线程 |
| 社交互动 | content, analytics, counter | 300 | 10000 | 300 | 读写混合 |
| 实时通信 | notification, im | 500 | 20000 | 500 | SSE/WebSocket 长连接 |
| 后台运营 | user, coupon, product | 200 | 8192 | 100 | 低频操作 |
| 网关 | gateway (Netty) | 见 Netty 配置 | — | — | 响应式不需要 Tomcat |

##### 2.2 TomcatWebServerFactoryCustomizer 实现

每个服务都应该有自定义的 Tomcat 定制器：

```java
/**
 * 放在 my-xhs-common，各服务通过配置属性驱动
 */
@Configuration
public class TomcatCustomizerConfig {
    
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer(
            TomcatPoolProperties props) {
        return factory -> {
            // 1. Connector 级别定制
            factory.addConnectorCustomizers(connector -> {
                // 最大 KeepAlive 请求数（长连接复用次数，默认100）
                connector.setProperty("maxKeepAliveRequests", 
                    String.valueOf(props.getMaxKeepAliveRequests()));
                
                // 连接超时时间
                connector.setProperty("connectionTimeout", 
                    String.valueOf(props.getConnectionTimeout()));
                
                // KeepAlive 超时
                connector.setProperty("keepAliveTimeout", 
                    String.valueOf(props.getKeepAliveTimeout()));
                
                // 最大请求体大小 (防 OOM)
                connector.setProperty("maxSwallowSize", 
                    String.valueOf(props.getMaxSwallowSize()));
                
                // Acceptor 线程优先级（接收连接的线程）
                connector.setProperty("acceptorThreadPriority", "5");
            });
            
            // 2. 协议处理器定制
            factory.addConnectorCustomizers(connector -> {
                Http11NioProtocol protocol = 
                    (Http11NioProtocol) connector.getProtocolHandler();
                // 选择器超时（影响连接建立速度）
                protocol.setSelectorTimeout(1000);
                // 最大 HTTP Header 大小
                protocol.setMaxHttpHeaderSize(8192);
                // 使用 Sendfile（静态资源传输优化）
                protocol.setUseSendfile(true);
            });
            
            // 3. MBean 注册（暴露 JMX 指标）
            factory.addConnectorCustomizers(connector -> {
                connector.setProperty("jmxEnabled", "true");
            });
        };
    }
}
```

**各服务差异化配置**（`application.yml`）：

```yaml
# order-service
tomcat:
  pool:
    max-keep-alive-requests: 200      # 事务型服务复用连接更少
    connection-timeout: 5000
    keep-alive-timeout: 3000
    max-swallow-size: 2097152         # 2MB (订单请求体不大)

# search-service  
tomcat:
  pool:
    max-keep-alive-requests: 1000     # 高频读取更多复用
    connection-timeout: 10000
    keep-alive-timeout: 10000
    max-swallow-size: 10485760        # 10MB (ES 响应可能很大)
```

##### 2.3 Tomcat 线程池 JMX 监控 + 自适应调整

```java
/**
 * 实时监控 Tomcat 线程池状态，超过阈值自动告警或扩容
 */
@Component
public class TomcatThreadPoolMonitor {
    
    private final MeterRegistry meterRegistry;
    
    @Scheduled(fixedRate = 5000)
    public void monitor() {
        // 通过 JMX 获取 Tomcat 线程池 MBean
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName threadPoolName = new ObjectName(
            "Catalina:type=ThreadPool,name=*");
        
        for (ObjectName name : mBeanServer.queryNames(threadPoolName, null)) {
            int currentThreadsBusy = (int) mBeanServer.getAttribute(
                name, "currentThreadsBusy");
            int currentThreadCount = (int) mBeanServer.getAttribute(
                name, "currentThreadCount");
            int maxThreads = (int) mBeanServer.getAttribute(
                name, "maxThreads");
            int connectionCount = (int) mBeanServer.getAttribute(
                name, "connectionCount");
            int maxConnections = (int) mBeanServer.getAttribute(
                name, "maxConnections");
            
            // 暴露给 Prometheus
            meterRegistry.gauge("tomcat.threads.busy", currentThreadsBusy);
            meterRegistry.gauge("tomcat.threads.total", currentThreadCount);
            meterRegistry.gauge("tomcat.connections.active", connectionCount);
            
            // 告警：线程池使用率 > 80%
            double busyRatio = (double) currentThreadsBusy / maxThreads;
            if (busyRatio > 0.8) {
                log.warn("Tomcat 线程池繁忙率 {:.2%}, busy={}/{}, conn={}/{}", 
                    busyRatio, currentThreadsBusy, maxThreads, 
                    connectionCount, maxConnections);
            }
        }
    }
}
```

**Prometheus 告警规则追加**：

```yaml
# config/prometheus/alert_rules/myxhs_rules.yml 追加
- alert: TomcatThreadPoolExhaustion
  expr: tomcat_threads_busy / tomcat_threads_total > 0.85
  for: 2m
  labels:
    severity: P1
  annotations:
    summary: "Tomcat 线程池即将耗尽 (实例 {{ $labels.instance }})"
    
- alert: TomcatConnectionLimitReached  
  expr: tomcat_connections_active / tomcat_connections_max > 0.9
  for: 1m
  labels:
    severity: P1
  annotations:
    summary: "Tomcat 连接数接近上限"
```

##### 2.4 Netty (Gateway) 层调优

Gateway 使用 WebFlux + Netty，不适用 Tomcat 配置：

```java
@Configuration
public class NettyTuningConfig implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {
    
    @Override
    public void customize(NettyReactiveWebServerFactory factory) {
        // 每个 Acceptor 线程的 Selector 优化
        factory.addServerCustomizers(httpServer -> httpServer
            .option(ChannelOption.SO_BACKLOG, 2048)         // TCP 全连接队列
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childOption(ChannelOption.TCP_NODELAY, true)    // 禁用 Nagle
            .childOption(ChannelOption.SO_RCVBUF, 128 * 1024)
            .childOption(ChannelOption.SO_SNDBUF, 128 * 1024)
        );
        
        // 自定义 Reactor 资源（EventLoop 线程数）
        // reactor.ipc.netty.workerCount 可以通过 spring 属性配置
    }
}
```

```yaml
# gateway application.yml
reactor:
  netty:
    worker-count: ${GATEWAY_WORKER_THREADS:8}  # CPU 核数 × 2
    pool:
      max-connections: 20000                     # 上游连接池
      acquire-timeout: 10000
```

---

### L3：Web Framework 层限流

#### 当前状态

- ✅ `@RateLimit` 注解 + AOP（Redis Lua 脚本滑动窗口）
- ❌ **没有** Spring MVC `HandlerInterceptor` 级限流
- ❌ **限流维度单一**：只支持"方法级 QPS"，不支持"Controller 级"、"请求路径级"

#### P8 级别方案

##### 3.1 Spring MVC HandlerInterceptor 限流拦截器

**为什么需要？**
- `@RateLimit` AOP 需要手动在每个方法上加注解，遗漏一个就是漏洞
- HandlerInterceptor 可以对整个 Controller 或 URL Pattern 做统一限流
- 作为 AOP 注解方式的补充防线

```java
/**
 * 框架级限流拦截器 —— 对特定 Controller/URL 做统一限流
 * 与 @RateLimit AOP 互补：拦截器负责粗粒度，AOP 负责细粒度
 * 
 * 注册在 WebMvcConfigurer.addInterceptors()，
 * 优先级：@Order(5) —— 在 UserContextInterceptor (order=10) 之前执行
 */
@Component
@Order(5)
public class RateLimitInterceptor implements HandlerInterceptor {
    
    private final RedissonClient redissonClient;
    private final RateLimitProperties properties;
    
    /**
     * 使用 Redisson RRateLimiter 而非 Redis Lua 脚本
     * 原因：
     * 1. Redisson 内置 RateLimiter 实现支持令牌桶/滑动窗口
     * 2. 支持 tryAcquire(permits) 非阻塞获取
     * 3. 支持 trySetRate() 动态调整速率
     */
    @Override
    public boolean preHandle(HttpServletRequest request, 
            HttpServletResponse response, Object handler) {
        
        if (!(handler instanceof HandlerMethod)) return true;
        
        HandlerMethod hm = (HandlerMethod) handler;
        String path = request.getRequestURI();
        String method = request.getMethod();
        
        // 1. Controller 级限流
        RateLimitConfig controllerConfig = 
            properties.getController(path);
        if (controllerConfig != null && !tryAcquire("ctrl:" + path, controllerConfig)) {
            return reject(response, "Controller " + path + " 限流");
        }
        
        // 2. URL Pattern 级限流（支持 ant 风格路径匹配）
        for (Map.Entry<String, RateLimitConfig> entry : 
                properties.getPatterns().entrySet()) {
            if (pathMatcher.match(entry.getKey(), path) 
                    && !tryAcquire("pattern:" + entry.getKey(), entry.getValue())) {
                return reject(response, "Pattern " + entry.getKey() + " 限流");
            }
        }
        
        // 3. 全局兜底限流
        if (!tryAcquire("global:" + path, properties.getGlobal())) {
            return reject(response, "全局限流");
        }
        
        return true;
    }
    
    private boolean tryAcquire(String key, RateLimitConfig config) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        // 首次初始化限流规则
        limiter.trySetRate(RateType.OVERALL, 
            config.getRate(),           // 速率
            config.getRateInterval(),   // 时间窗口
            RateIntervalUnit.SECONDS);
        return limiter.tryAcquire();
    }
    
    private boolean reject(HttpServletResponse response, String reason) {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        // 返回统一 JSON 格式
        return false;
    }
}
```

**配置示例**（Nacos/本地 yml）：

```yaml
rate-limit:
  framework:
    # Controller 级
    controller:
      /api/order: { rate: 100, rate-interval: 1 }     # 下单接口 100 QPS
      /api/payment: { rate: 50, rate-interval: 1 }    # 支付接口 50 QPS
      /api/feed: { rate: 500, rate-interval: 1 }      # Feed 流 500 QPS
    
    # URL Pattern 级（Ant 风格）
    patterns:
      /api/admin/**: { rate: 10, rate-interval: 1 }   # 管理接口严格限流
      /api/search/**: { rate: 1000, rate-interval: 1 } # 搜索接口大流量
    
    # 全局兜底
    global: { rate: 1000, rate-interval: 1 }
```

##### 3.2 @RateLimit 注解升级

当前 AOP 的不足 → P8 升级点：

| 当前不足 | P8 升级 |
|---------|--------|
| 只支持 Redis Lua 滑动窗口 | 增加令牌桶/漏桶/预热模式可配置 |
| `perUser=true` 时 Key 维度固定 | 支持自定义 SpEL 表达式生成 Key |
| 限流后抛 `BizException` | 增加排队模式（block 等待而非直接拒绝） |
| 无与 Sentinel 联动 | 标记限流事件给 Sentinel Dashboard |

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    
    /** 窗口时间（秒） */
    int windowSeconds() default 1;
    
    /** 窗口内最大请求数 */
    int maxRequests() default 100;
    
    /** 是否按用户限流 */
    boolean perUser() default false;
    
    /** 限流算法 */
    RateLimitAlgorithm algorithm() default RateLimitAlgorithm.SLIDING_WINDOW;
    
    /** 是否需要排队等待（而非直接拒绝） */
    boolean blockWait() default false;
    
    /** 排队最大等待超时 (ms) */
    long blockTimeoutMs() default 500;
    
    /** 自定义 Key 生成 SpEL 表达式 */
    String keySpEL() default "";  // 如: "'product:' + #productId"
    
    /** 限流 Key 前缀 */
    String prefix() default "ratelimit";
    
    /** 限流触发后的事件类型（对接 Sentinel 告警） */
    String eventType() default "";
}

enum RateLimitAlgorithm {
    SLIDING_WINDOW,  // 滑动窗口（当前默认）
    TOKEN_BUCKET,    // 令牌桶（允许突发）
    LEAKY_BUCKET,    // 漏桶（匀速）
    WARM_UP          // 预热（令牌生成速率从 1/3 逐步提升到全速）
}
```

##### 3.3 Spring WebFlux 侧（Gateway 内部路由）

Gateway 内部如果有自定义路由到具体 Handler，可对 WebFlux 的 HandlerFunction 做限流：

```java
@Component
public class GatewayWebFluxRateLimitFilter implements WebFilter {
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 对特定 Handler 做 Reactive 限流
        // 使用 reactor-extra 的 RateLimiter 或自定义
        return Mono.just(exchange)
            .flatMap(...)
            .switchIfEmpty(chain.filter(exchange));
    }
}
```

---

### L4：组件级限流 —— 这才是 P8 的核心

#### 4.1 Redis 访问限流

**当前状态**：
- Lettuce 连接池：`max-active=16, max-idle=8, min-idle=4`
- Redisson 连接池：`connectionPoolSize=8, connectionMinimumIdleSize=1`
- **没有任何** Redis 命令级限流

**P8 级别方案**：

##### 4.1.1 Redis 连接池保护（Client Side）

```java
/**
 * 对 Redis 命令执行做连接池层面的限流保护
 * 防止热点 Key 导致连接池被耗尽
 */
@Configuration
public class RedisCommandRateLimitConfig {
    
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceCustomizer() {
        return builder -> {
            // 1. 命令超时 + 重连策略
            builder.commandTimeout(Duration.ofMillis(500))
                   .clientOptions(ClientOptions.builder()
                       .autoReconnect(true)
                       .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                       .socketOptions(SocketOptions.builder()
                           .connectTimeout(Duration.ofMillis(3000))
                           .keepAlive(true)
                           .build())
                       .build());
            
            // 2. 连接池配置（替代 application.yml 中的配置，更灵活）
            builder.poolConfig(new GenericObjectPoolConfig<>() {{
                setMaxTotal(32);           // 从 16 提升到 32
                setMaxIdle(16);
                setMinIdle(8);             // 从 4 提升到 8
                setMaxWaitMillis(2000);    // 获取连接超时 2s (从3s降低)
                setTestOnBorrow(true);     // 借出时检测连接有效性
                setTestWhileIdle(true);
                setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
                setBlockWhenExhausted(true); // 池满时阻塞等待（而非抛异常）
            }});
            
            // 3. 开启自适应拓扑刷新（集群模式）
            builder.useClusterTopologyRefresh();
        };
    }
}
```

##### 4.1.2 热点 Key 访问限流（结合 JD-hotkey）

```java
/**
 * Redis 层面：对热点 Key 做访问限流
 * 
 * 场景：秒杀商品的库存 Key 被大量并发读取
 * 方案：JD-hotkey 自动探测 + 本地内存短缓存 + 访问降级
 */
@Component
public class HotKeyInterceptor {
    
    private final Cache<String, Object> hotKeyLocalCache = 
        Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.SECONDS)  // 1秒本地缓存
            .build();
    
    /**
     * 包装 Redis GET 命令：
     * 1. 先查 JD-hotkey 是否标记为热点
     * 2. 是热点 → 查本地 Caffeine 缓存
     * 3. 非热点 → 正常查 Redis
     */
    public <T> T getWithHotKeyProtection(String key, Class<T> clazz) {
        // 如果 JD-hotkey Worker 标记此 Key 为热点
        if (HotKeyDetector.isHot(key)) {
            T cached = (T) hotKeyLocalCache.getIfPresent(key);
            if (cached != null) {
                return cached;  // 返回本地缓存，不访问 Redis
            }
        }
        
        // 正常访问 Redis
        T result = redisTemplate.opsForValue().get(key);
        
        // 如果是热点，缓存到本地
        if (HotKeyDetector.isHot(key) && result != null) {
            hotKeyLocalCache.put(key, result);
        }
        return result;
    }
    
    /**
     * Redis Lua 脚本批量操作时也做保护
     */
    public <T> T executeWithHotKeyProtection(
            RedisScript<T> script, List<String> keys, Object... args) {
        // 检查所有 Key 是否有热点
        boolean hasHotKey = keys.stream().anyMatch(HotKeyDetector::isHot);
        if (hasHotKey) {
            // 有热点 Key 时：降低批量大小 or 异步处理
            log.warn("Lua 脚本包含热点 Key: {}", keys);
        }
        return redisTemplate.execute(script, keys, args);
    }
}
```

##### 4.1.3 Redis 连接池耗尽降级

```java
@Aspect
@Component
@Order(20)
public class RedisConnectionProtectionAspect {
    
    /**
     * 当 Redis 连接池已满（blockWhenExhausted=true 时等待超时），
     * 不再无限阻塞，而是降级处理
     */
    @Around("@annotation(org.springframework.cache.annotation.Cacheable) || " +
            "@annotation(org.springframework.data.redis.core)")
    public Object protect(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            return joinPoint.proceed();
        } catch (RedisConnectionFailureException | 
                 RedisCommandTimeoutException e) {
            // 连接池耗尽、Redis 不可用 → 降级
            log.error("Redis 访问异常，触发降级", e);
            
            // 查询类：返回 null/空集合（业务侧自行处理）
            // 写入类：记录到本地缓冲，等恢复后补写
            return handleRedisFallback(joinPoint, e);
        }
    }
}
```

#### 4.2 MyBatis SQL 执行限流

**当前状态**：
- ❌ 只有分页插件 `maxLimit=500`
- ❌ 没有慢 SQL 检测
- ❌ 没有 SQL 执行频率控制
- ❌ 没有 MyBatis Interceptor 做流控

**P8 级别方案**：

##### 4.2.1 慢 SQL 检测 + 熔断

```java
/**
 * MyBatis Interceptor —— SQL 执行时间检测 + 自动熔断
 * 
 * 核心思路：
 * 1. 拦截所有 SQL 执行
 * 2. 记录执行时间
 * 3. 超过阈值 → 记录 WARN 日志 + 上报 Prometheus
 * 4. 同一条 SQL 连续超时 → 触发熔断（短路返回，不再执行）
 */
@Intercepts({
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update",
        args = {MappedStatement.class, Object.class})
})
@Component
public class SqlGuardInterceptor implements Interceptor {
    
    // 熔断器：Key = SQL ID (namespace + statementId)
    private final Cache<String, CircuitBreakerState> circuitBreakers = 
        Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();
    
    // 慢 SQL 阈值
    private static final long SLOW_SQL_THRESHOLD_MS = 200;
    // 熔断触发次数（连续慢 SQL 次数）
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
    // 熔断恢复时间
    private static final long CIRCUIT_RECOVERY_MS = 30_000;
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String sqlId = getSqlId(invocation);
        
        // 1. 检查熔断状态
        CircuitBreakerState state = circuitBreakers.getIfPresent(sqlId);
        if (state != null && state.isOpen()) {
            // 熔断已打开 → 短接，直接抛异常
            throw new BizException(ResultCode.SYSTEM_BUSY, 
                "SQL 执行熔断中: " + sqlId);
        }
        
        // 2. 执行 SQL 并计时
        long start = System.currentTimeMillis();
        try {
            Object result = invocation.proceed();
            long elapsed = System.currentTimeMillis() - start;
            
            // 3. 慢 SQL 记录
            if (elapsed > SLOW_SQL_THRESHOLD_MS) {
                state = circuitBreakers.get(sqlId, k -> new CircuitBreakerState());
                int consecutiveSlowCount = state.recordSlow(elapsed);
                
                log.warn("慢 SQL 检测: sqlId={}, 耗时={}ms, 连续慢次数={}/{}",
                    sqlId, elapsed, consecutiveSlowCount, CIRCUIT_BREAKER_THRESHOLD);
                
                // 4. 达到阈值 → 打开熔断
                if (consecutiveSlowCount >= CIRCUIT_BREAKER_THRESHOLD) {
                    state.open(CIRCUIT_RECOVERY_MS);
                    log.error("SQL 熔断触发: sqlId={}, 恢复时间={}ms", 
                        sqlId, CIRCUIT_RECOVERY_MS);
                    // 发送告警到 Prometheus
                    meterRegistry.counter("mybatis.circuit_breaker.open", 
                        "sqlId", sqlId).increment();
                }
            } else {
                // SQL 恢复正常 → 重置计数器
                if (state != null) state.reset();
            }
            
            return result;
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("SQL 执行异常: sqlId={}, 耗时={}ms", sqlId, elapsed, e);
            
            // SQL 执行异常也计入慢 SQL 统计
            if (state != null) state.recordSlow(elapsed);
            throw e;
        }
    }
    
    // 内部类：熔断状态
    static class CircuitBreakerState {
        private final AtomicInteger consecutiveSlowCount = new AtomicInteger(0);
        private volatile long openUntil = 0;
        
        int recordSlow(long elapsedMs) {
            return consecutiveSlowCount.incrementAndGet();
        }
        
        void reset() {
            consecutiveSlowCount.set(0);
        }
        
        void open(long recoveryMs) {
            this.openUntil = System.currentTimeMillis() + recoveryMs;
        }
        
        boolean isOpen() {
            return System.currentTimeMillis() < this.openUntil;
        }
    }
}
```

##### 4.2.2 SQL 执行频率限流（按 SQL ID 限流）

```java
/**
 * 对特定 Mapper 方法做 QPS 限流
 * 
 * 场景：防止某些高频查询（如全表扫描）打垮数据库
 * 实现：基于 Redisson RRateLimiter
 */
@Component
@Intercepts({
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
// 注意：与 SqlGuardInterceptor 共用同一个 InterceptorChain 会导致拦截顺序问题
// 应该合并为一个拦截器，或者通过 @Order 控制
public class SqlRateLimitInterceptor implements Interceptor {
    
    private final RedissonClient redissonClient;
    
    // 需要限流的 SQL ID 配置（可以从 Nacos 动态加载）
    private final Set<String> limitedSqlIds = Set.of(
        // 示例：商品全量查询（危险操作）
        "com.myxhs.product.mapper.SpuMapper.selectAll",
        // 示例：订单全量扫描（危险操作）
        "com.myxhs.order.mapper.OrderMapper.selectAll"
    );
    
    private static final double MAX_QPS = 5.0;  // 最多每秒 5 次
    
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String sqlId = getSqlId(invocation);
        
        if (limitedSqlIds.contains(sqlId)) {
            RRateLimiter limiter = redissonClient.getRateLimiter("sql:ratelimit:" + sqlId);
            limiter.trySetRate(RateType.OVERALL, (long) MAX_QPS, 1, RateIntervalUnit.SECONDS);
            
            if (!limiter.tryAcquire()) {
                throw new BizException(ResultCode.TOO_MANY_REQUESTS, 
                    "SQL 查询频率超限: " + sqlId);
            }
        }
        
        return invocation.proceed();
    }
}
```

**注意**：上面两个拦截器必须合并，因为 MyBatis 的 InterceptorChain 对同一接口类型（Executor.query）只支持一个拦截路径。可以用**责任链模式**在同一个拦截器内部串联。

##### 4.2.3 HikariCP 连接池监控 + 保护

```java
/**
 * HikariCP 连接池实时监控 + 过载保护
 * 
 * 当连接池使用率超过阈值时：
 * 1. 新请求直接拒绝（而非排队等待导致雪崩）
 * 2. 已有的慢事务主动中断
 */
@Component
public class HikariPoolGuard {
    
    private final MeterRegistry meterRegistry;
    private final Map<String, HikariDataSource> dataSources;
    
    @PostConstruct
    public void init() {
        // 通过 Spring 上下文获取所有 HikariCP 数据源
        Map<String, DataSource> beans = applicationContext.getBeansOfType(DataSource.class);
        beans.forEach((name, ds) -> {
            if (ds instanceof HikariDataSource) {
                dataSources.put(name, (HikariDataSource) ds);
            }
        });
    }
    
    @Scheduled(fixedRate = 5000)
    public void monitor() {
        for (Map.Entry<String, HikariDataSource> entry : dataSources.entrySet()) {
            HikariPoolMXBean pool = entry.getValue().getHikariPoolMXBean();
            if (pool == null) continue;
            
            int active = pool.getActiveConnections();
            int total = pool.getTotalConnections();
            int max = entry.getValue().getMaximumPoolSize();
            int idle = pool.getIdleConnections();
            int pending = pool.getThreadsAwaitingConnection();
            
            // 暴露 Prometheus 指标
            String name = entry.getKey();
            meterRegistry.gauge("hikari.active", name, active);
            meterRegistry.gauge("hikari.idle", name, idle);
            meterRegistry.gauge("hikari.pending", name, pending);
            meterRegistry.gauge("hikari.total", name, total);
            
            // 告警
            double usage = (double) active / max;
            if (usage > 0.85) {
                log.warn("HikariCP[{}] 使用率 {:.1%}, active={}/{}, pending={}", 
                    name, usage, active, max, pending);
            }
            
            // 过载保护：pending 线程数 > 阈值时发出告警
            if (pending > 5) {
                log.error("HikariCP[{}] 连接池排队线程过多: {}", name, pending);
                meterRegistry.counter("hikari.pool_exhausted", 
                    "datasource", name).increment();
            }
        }
    }
}

// 配置类
@Configuration
public class HikariPoolConfig {
    
    /**
     * 自定义 HikariCP 连接池配置
     * 不同服务用不同的池大小
     */
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.order.hikari")
    public HikariConfig orderHikariConfig() {
        HikariConfig config = new HikariConfig();
        // 核心交易：保守配置
        config.setMaximumPoolSize(15);           // 从 20 降到 15
        config.setMinimumIdle(5);
        config.setConnectionTimeout(3000);       // 从 10s 降到 3s
        config.setIdleTimeout(600000);           // 10 分钟
        config.setMaxLifetime(1800000);          // 30 分钟
        config.setLeakDetectionThreshold(30000); // 30 秒泄露检测
        config.setPoolName("OrderHikariPool");
        return config;
    }
    
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.search.hikari")
    public HikariConfig searchHikariConfig() {
        HikariConfig config = new HikariConfig();
        // 搜索服务：高频读取，需要更大池
        config.setMaximumPoolSize(30);           // 提升到 30
        config.setMinimumIdle(10);
        config.setConnectionTimeout(5000);
        config.setIdleTimeout(300000);
        config.setMaxLifetime(1800000);
        config.setPoolName("SearchHikariPool");
        return config;
    }
}
```

#### 4.3 Feign 调用限流（已有 Sentinel fallback，需增强）

```java
/**
 * Feign + Sentinel 完整流控方案
 * 
 * 当前：仅 order/payment 两个服务启用了 feign.sentinel.enabled=true
 * P8：所有包含 FeignClient 的服务都应启用
 */
@FeignClient(
    name = "inventory-service",
    fallbackFactory = InventoryFeignFallbackFactory.class  // 用 FallbackFactory 而非 Fallback
)
public interface InventoryFeignClient {
    
    @GetMapping("/api/inventory/stock/{skuId}")
    R<InventoryVO> getStock(@PathVariable Long skuId);
}

@Component
public class InventoryFeignFallbackFactory 
        implements FallbackFactory<InventoryFeignClient> {
    
    @Override
    public InventoryFeignClient create(Throwable cause) {
        return new InventoryFeignClient() {
            @Override
            public R<InventoryVO> getStock(Long skuId) {
                // 区分异常类型：
                if (cause instanceof DegradeException) {
                    log.warn("库存服务熔断降级: skuId={}", skuId);
                } else if (cause instanceof BlockException) {
                    log.warn("库存服务限流: skuId={}", skuId);
                } else {
                    log.error("库存服务调用异常: skuId={}", skuId, cause);
                }
                // 返回降级数据（库存未知）
                return R.fail(ResultCode.SERVICE_DEGRADE, "库存服务暂时不可用");
            }
        };
    }
}
```

**Sentinel Feign 规则（Nacos 动态配置）**：

```json
[
  {
    "resource": "GET:http://inventory-service/api/inventory/stock/{skuId}",
    "count": 200,
    "grade": 1,
    "limitApp": "default",
    "strategy": 0,
    "controlBehavior": 0
  },
  {
    "resource": "GET:http://inventory-service/api/inventory/stock/{skuId}",
    "count": 500,
    "grade": 2,        // 线程数模式（而非 QPS）
    "limitApp": "default"
  }
]
```

#### 4.4 RocketMQ 消费限流

```java
/**
 * RocketMQ 消费端限流
 * 
 * 当前：默认配置，无显式控制
 * P8：根据业务场景控制消费速率
 */
@Component
@RocketMQMessageListener(
    topic = "ORDER_CLOSE_TOPIC",
    consumerGroup = "order-close-consumer-group",
    consumeMode = ConsumeMode.CONCURRENTLY,
    consumeThreadMax = 4,             // 最大消费线程（默认 64，太大）
    consumeThreadMin = 2,
    pullBatchSize = 32,               // 每批拉取消息数
    maxReconsumeTimes = 3             // 最大重试次数
)
public class OrderCloseConsumer implements RocketMQListener<MessageExt> {
    
    @Override
    public void onMessage(MessageExt message) {
        // 消费逻辑
    }
}
```

```yaml
# application.yml 额外配置
rocketmq:
  consumer:
    # 消费端线程池大小（统一配置）
    consume-thread-min: 2
    consume-thread-max: 8
    # 批量消费大小（控制每次从 Broker 拉取的消息数）
    pull-batch-size: 32
    # 消息拉取间隔（ms），控制消费速率
    pull-interval: 0   # 0 = 实时拉取
    # 消费超时时间（分钟）
    consume-timeout: 15
```

---

## 二、完整实施路线图

### 阶段一：紧急修复 — 第 1~2 周

| # | 任务 | 模块 | 工时 | 优先级 |
|---|------|------|:---:|:---:|
| 1.1 | `ORDER_COMPENSATION_TOPIC` 消费者实现 | Order | 4h | 🔴 P0 |
| 1.2 | 推荐精排补全（接入简单 ML 模型 or 加权规则，不再纯透传） | Search | 8h | 🔴 P0 |
| 1.3 | Geo 召回真实计算（GeoHash 编码 + MySQL/ES 距离查询） | Search | 8h | 🔴 P0 |
| 1.4 | 特征提取改为从 ES note_index 读取（不再 Random） | Search | 4h | 🔴 P0 |
| 1.5 | OrderService.createOrder 单元测试 | Order | 4h | 🔴 P0 |
| 1.6 | PaymentService.pay/handlePayCallback 单元测试 | Payment | 4h | 🔴 P0 |
| 1.7 | InventoryService.preDeduct/releaseStock 单元测试 | Inventory | 4h | 🔴 P0 |
| 1.8 | GatewayAuthFilter + HmacSignatureFilter 单元测试 | Gateway | 4h | 🔴 P0 |

**阶段一验收标准**：
- ✅ 推荐系统 5 路召回全部真实可用
- ✅ 订单/支付/库存核心链路有测试覆盖
- ✅ 关单补偿链路完整

### 阶段二：外部服务搭建 — 第 1~2 周同步进行

| # | 任务 | 说明 | 工时 | 优先级 |
|---|------|------|:---:|:---:|
| 2.1 | Sentinel Dashboard 部署 | docker-compose 追加，端口 18082 | 1h | 🔴 P0 |
| 2.2 | JD-hotkey Worker + Dashboard 部署 | docker-compose 追加，端口 9900/9901 | 2h | 🔴 P0 |
| 2.3 | Jenkins 部署 + 初始化配置 | docker-compose 追加，端口 18083 | 2h | 🔴 P0 |
| 2.4 | SkyWalking Agent 挂载 | 修改所有微服务 JVM 启动参数 | 2h | 🔴 P0 |
| 2.5 | Nacos Config 功能启用 | 各服务 application.yml 打开配置 | 2h | 🟡 P1 |
| 2.6 | ES IK 分词器安装 | ES 容器内安装插件 | 1h | 🟡 P1 |
| 2.7 | Grafana Dashboard 导入 | JVM/业务/DB/Redis 面板 | 4h | 🟡 P1 |

### 阶段三：分层限流体系 — 第 3~4 周（重点）

| # | 任务 | 层次 | 工时 | 优先级 |
|---|------|------|:---:|:---:|
| **L1: 网关层** |
| 3.1 | Sentinel 集群限流（Token Server 部署） | L1-Gateway | 4h | 🔴 P0 |
| 3.2 | 多维 KeyResolver 实现（IP/User/API） | L1-Gateway | 4h | 🔴 P0 |
| 3.3 | GatewayParamFlowRule 按用户限流实现 | L1-Gateway | 4h | 🔴 P0 |
| 3.4 | 限流规则从 Nacos 动态加载（去掉硬编码） | L1-Gateway | 2h | 🔴 P0 |
| **L2: Web Server 层** |
| 3.5 | TomcatWebServerFactoryCustomizer 实现 | L2-Server | 4h | 🔴 P0 |
| 3.6 | 各服务差异化 Tomcat 配置 | L2-Server | 2h | 🔴 P0 |
| 3.7 | Tomcat JMX MBean 监控 + Prometheus 指标 | L2-Server | 4h | 🟡 P1 |
| 3.8 | Netty (Gateway) Reactor 调优 | L2-Server | 2h | 🟡 P1 |
| **L3: Framework 层** |
| 3.9 | Spring MVC RateLimitInterceptor 实现 | L3-Framework | 4h | 🔴 P0 |
| 3.10 | @RateLimit 注解升级（令牌桶/排队/SpEL） | L3-Framework | 4h | 🟡 P1 |
| 3.11 | WebFlux WebFilter 限流 | L3-Framework | 2h | 🟡 P1 |
| **L4: 组件层** |
| 3.12 | Redis Lettuce 连接池定制 + 保护 | L4-Component | 4h | 🔴 P0 |
| 3.13 | JD-hotkey 集成到 Redis 查询流程 | L4-Component | 4h | 🔴 P0 |
| 3.14 | MyBatis SqlGuardInterceptor（慢SQL检测+熔断） | L4-Component | 6h | 🔴 P0 |
| 3.15 | MyBatis SqlRateLimitInterceptor（SQL频率限流） | L4-Component | 4h | 🟡 P1 |
| 3.16 | HikariCP 连接池监控 + Prometheus 告警 | L4-Component | 4h | 🟡 P1 |
| 3.17 | Feign Sentinel fallback 全量启用 | L4-Component | 2h | 🟡 P1 |
| 3.18 | RocketMQ 消费端线程池 + pullBatchSize 控制 | L4-Component | 2h | 🟡 P1 |

**阶段三验收标准**：
- ✅ 四层限流全部可观测（Prometheus 指标 + Grafana 面板）
- ✅ 任意一层限流触发都有日志 + 指标输出
- ✅ Sentinel Dashboard 可实时查看 + 调整规则

### 阶段四：分布式能力加固 — 第 5~6 周

| # | 任务 | 工时 |
|---|------|:---:|
| 4.1 | RocketMQ 顺序消息实现（评论排序按时间序） | 4h |
| 4.2 | Content 模块 MQ 通知接入（笔记发布→Feed推送+评论通知） | 4h |
| 4.3 | 优惠券 RocketMQ 延时消息（替代 XXL-Job 每小时扫描） | 2h |
| 4.4 | 数据库读写分离（ProxySQL 代理） | 4h |
| 4.5 | Redis 大 Key 检测脚本（redis-rdb-tools + 定时扫描） | 4h |
| 4.6 | 缓存雪崩自动探测（监控 Redis 命中率突变） | 4h |
| 4.7 | JD-hotkey 完整集成（Worker + Dashboard + 业务代码） | 4h |

### 阶段五：工程化 + 高可用 — 第 7~8 周

| # | 任务 | 工时 |
|---|------|:---:|
| 5.1 | Jenkins Pipeline（checkout→compile→test→build→deploy） | 8h |
| 5.2 | 所有微服务 Dockerfile 编写 | 4h |
| 5.3 | Kubernetes Deployment YAML（多实例部署） | 4h |
| 5.4 | 多实例部署验证（Nacos 服务列表确认） | 2h |
| 5.5 | Grafana 业务仪表盘（下单漏斗/支付成功率/QPS热力图） | 8h |
| 5.6 | GoReplay 流量录制 + JMeter 压测脚本 | 8h |
| 5.7 | Nacos Config 全量迁移（各服务配置上云） | 4h |
| 5.8 | JVM GC 调优（G1GC + MaxGCPauseMillis=200） | 4h |
| 5.9 | 全链路压测 + 瓶颈定位 | 8h |

---

## 三、P8 级别 vs 当前状态对比

| 维度 | 当前 my-xhs | P8 级别 |
|------|------------|--------|
| **网关限流** | Sentinel 单机 14 条规则 | 集群模式 + 多维 KeyResolver + Dashboard 可视化 |
| **Tomcat** | yml 静态配置 200线程/8192连接 | 分级配置 + Customizer + JMX监控 + 自适应调整 |
| **Spring MVC** | 无框架级限流 | HandlerInterceptor + Redisson RRateLimiter |
| **Redis 保护** | 连接池 16-8-4，无超限保护 | LettuceClient定制 + JD-hotkey + 连接池耗尽降级 |
| **MyBatis** | 分页 500 上限 | 慢SQL检测+熔断 + SQL频率限流 + HikariCP监控 |
| **Feign** | 仅 2 服务启用 Sentinel | 全量启用 + FallbackFactory + 异常类型区分 |
| **RocketMQ** | 默认配置 | 消费线程控制 + pullBatchSize + 消息积压告警 |
| **故障演练** | chaos-drill.sh | 限流/熔断/降级/连接池耗尽 全场景覆盖 |
| **可观测** | Prometheus 9 规则 | 每层限流都有Prometheus指标 + Grafana面板 |

---

## 四、docker-compose.yml 需要新增的内容

```yaml
# 追加到现有 docker-compose.yml 中

  # ========== 新增外部服务 ==========
  
  sentinel-dashboard:
    image: bladex/sentinel-dashboard:1.8.8
    container_name: myxhs-sentinel-dashboard
    network_mode: host
    environment:
      - SERVER_PORT=18082
      - SENTINEL_DASHBOARD_USERNAME=admin
      - SENTINEL_DASHBOARD_PASSWORD=Xhs@2026
    restart: unless-stopped

  jd-hotkey-worker:
    image: jd-hotkey/jd-hotkey-worker:1.0.0  # 需确认实际镜像
    container_name: myxhs-hotkey-worker
    network_mode: host
    ports:
      - "9900:9900"
    environment:
      - REDIS_HOST=127.0.0.1
      - REDIS_PORT=16379
    restart: unless-stopped

  jd-hotkey-dashboard:
    image: jd-hotkey/jd-hotkey-dashboard:1.0.0
    container_name: myxhs-hotkey-dashboard
    network_mode: host
    ports:
      - "9901:9901"
    environment:
      - WORKER_ADDRESS=127.0.0.1:9900
    restart: unless-stopped

  jenkins:
    image: jenkins/jenkins:lts-jdk17
    container_name: myxhs-jenkins
    network_mode: host
    ports:
      - "18083:8080"
    volumes:
      - ./jenkins_home:/var/jenkins_home
      - /var/run/docker.sock:/var/run/docker.sock
      - /usr/bin/docker:/usr/bin/docker
    restart: unless-stopped

  proxysql:
    image: proxysql/proxysql:2.6.3
    container_name: myxhs-proxysql
    network_mode: host
    ports:
      - "16033:6033"   # MySQL 代理端口
      - "16032:6032"   # 管理端口
    volumes:
      - ./config/proxysql/proxysql.cnf:/etc/proxysql.cnf
    restart: unless-stopped
    depends_on:
      - mysql-user
      - mysql-content
      - mysql-order
      - mysql-inventory

  elasticsearch:
    # 现有 ES 配置追加 IK 插件安装
    # 方式：修改 Dockerfile 或在 entrypoint 中安装
    #   如：elasticsearch-plugin install https://github.com/medcl/elasticsearch-analysis-ik/releases/download/v8.12.2/elasticsearch-analysis-ik-8.12.2.zip
    environment:
      - "ES_JAVA_OPTS=-Xms2g -Xmx2g"
```

---

## 五、JVM 启动参数规范（SkyWalking Agent + GC）

```bash
# 所有微服务统一 JVM 参数
JAVA_OPTS="
  -javaagent:/opt/skywalking/agent/skywalking-agent.jar
  -Dskywalking.agent.service_name=my-xhs-{module}
  -Dskywalking.collector.backend_service=21.91.124.110:11800
  -Xms512m -Xmx512m
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:+PrintGCDetails
  -XX:+PrintGCDateStamps
  -Xloggc:/data/logs/my-xhs/{module}/gc-%t.log
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/data/logs/my-xhs/{module}/
  -Dcom.sun.management.jmxremote
  -Dcom.sun.management.jmxremote.port={jmx_port}
  -Dcom.sun.management.jmxremote.ssl=false
  -Dcom.sun.management.jmxremote.authenticate=false
"
```

---

*本文档将持续更新，作为 my-xhs 项目 P8 级别技术深化的唯一参考来源。*
