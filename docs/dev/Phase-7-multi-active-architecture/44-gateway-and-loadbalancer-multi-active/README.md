# 网关与负载均衡多活

> 跨服务专题 | 开发阶段：Phase-7 | 核心组件：RegionRouteFilter + RegionLoadBalancer

## 专题概要

本专题包含三个核心设计：Gateway 多活路由（RegionRouteFilter 注入区域标记 + 故障自动切换）、Spring Cloud LoadBalancer 区域感知负载均衡（同区域优先选择实例）、Feign 多活拦截器（透传 `X-Region-Tag` 到下游服务）。

## 涉及服务

- my-xhs-gateway（多活路由入口：RegionRouteFilter + RegionHealthChecker + RegionFailoverHandler）
- 所有微服务（区域感知消费方：LoadBalancer 区域感知 + Feign 透传区域标记）
- my-xhs-common（组件提供方：RegionLoadBalancer、RegionFeignInterceptor 等）

## 核心内容

| 维度 | 内容 |
|------|------|
| Gateway 路由 | RegionRouteFilter（优先级最高）：注入 X-Region-Tag → 路由决策 → 故障切换 |
| 负载均衡 | RegionLoadBalancer：PREFER_LOCAL / FORCE_LOCAL / FORCE_REMOTE 三种策略 |
| Feign 拦截 | RegionFeignInterceptor：透传 X-Region-Tag + X-Region-Route + X-Region-Failover |
| 故障检测 | RegionHealthChecker：10秒间隔、3次连续失败标记不可用 |
| 故障切换 | 自动路由到对端 + Nginx 权重调整 + 灰度恢复 |

## 重点覆盖

- Gateway 多活路由 Filter 链设计（与 GrayRouteFilter / AuthFilter / RateLimitFilter 兼容）
- RegionLoadBalancer 基于 ServiceInstanceListSupplier 的区域过滤逻辑
- Feign 拦截器链（Region + Trace 叠加透传）
- 故障自动切换流程（检测 → 标记 → 路由切换 → 通知 → 灰度恢复）
- 机房健康检查与 Nacos 实例健康检测的差异

## 补充一：RegionRouteFilter 核心流程设计

> Gateway 作为多活流量入口，RegionRouteFilter 是最核心的组件。需要明确其 Filter 链顺序、路由决策逻辑、故障切换行为。

### Filter 链顺序

```
请求进入 Gateway
  │
  ├─ 1. RegionRouteFilter（优先级最高，order=-1000）
  │     注入 X-Region-Tag → 判断路由策略 → 故障自动切换
  │
  ├─ 2. GrayRouteFilter（灰度路由，order=-900）
  │     灰度用户标记 → 灰度实例路由
  │
  ├─ 3. AuthFilter（鉴权，order=-800）
  │     Token 校验 → 用户信息注入
  │
  ├─ 4. RateLimitFilter（限流，order=-700）
  │     区域级限流 → 全局限流
  │
  └─ 5. RouteToRequestUrlFilter（Spring Cloud Gateway 内置）
        实际路由转发
```

**设计原则**：RegionRouteFilter 必须在所有业务 Filter 之前执行，确保区域标记尽早注入，后续 Filter 可基于区域标记做差异化处理（如灰度路由的灰度比例可按区域不同配置）。

### RegionRouteFilter 核心逻辑

```java
/**
 * 多活路由过滤器
 * 职责：注入区域标记 → 路由决策 → 故障切换
 */
@Component
public class RegionRouteFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 1. 确定当前机房区域（从部署环境变量读取）
        String currentRegion = RegionConstant.getCurrentRegion(); // region-a 或 region-b

        // 2. 注入区域标记到请求头（如果客户端未传）
        String regionTag = request.getHeaders().getFirst("X-Region-Tag");
        if (StringUtils.isBlank(regionTag)) {
            regionTag = currentRegion;
            request = request.mutate()
                .header("X-Region-Tag", regionTag)
                .build();
        }

        // 3. 读取路由策略（默认 prefer-local）
        String routeStrategy = request.getHeaders().getFirst("X-Region-Route");
        if (StringUtils.isBlank(routeStrategy)) {
            routeStrategy = "prefer-local";
        }

        // 4. 故障检测：如果目标机房不可用，自动切换
        String targetRegion = regionTag;
        if (!regionHealthChecker.isHealthy(targetRegion)
                && !"force-local".equals(routeStrategy)) {
            // 目标机房不可用，切换到对端
            targetRegion = RegionConstant.getOppositeRegion(targetRegion);
            request = request.mutate()
                .header("X-Region-Tag", targetRegion)
                .header("X-Region-Failover", "true")  // 标记发生了故障切换
                .build();
            log.warn("机房 {} 不可用，自动切换到 {}", regionTag, targetRegion);
        }

        // 5. 将路由策略写入请求属性，供 LoadBalancer 使用
        exchange.getAttributes().put("regionRoute", routeStrategy);
        exchange.getAttributes().put("targetRegion", targetRegion);

        // 6. MDC 透传（日志中可看到区域信息）
        MDC.put("regionTag", targetRegion);
        MDC.put("regionRoute", routeStrategy);

        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return -1000; // 最高优先级
    }
}
```

### 故障切换时 Gateway 层行为

```
正常请求流程：
  Client → Nginx → Gateway(A) → Service-A(region-a) → MySQL-A

机房 A 故障时：
  Client → Nginx → Gateway(A) → 检测到 A 不可用
                              → RegionRouteFilter 自动切换 X-Region-Tag=region-b
                              → Service-B(region-b) → MySQL-B

  同时：
  - RegionHealthChecker 每10秒探测，3次连续失败标记不可用
  - 通知 Nginx 调整权重（通过 TrafficScheduler）
  - 告警通知运维人员确认
```

---

## 补充二：RegionLoadBalancer 算法详细设计

> 负载均衡器需要根据区域标记选择实例，同时要避免同区域实例全部打满导致单点过载。

### 区域感知选择算法

```java
/**
 * 区域感知负载均衡器
 * 策略：PREFER_LOCAL / FORCE_LOCAL / FORCE_REMOTE
 */
@Component
public class RegionLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        String targetRegion = (String) request.getContext().get("targetRegion");
        String routeStrategy = (String) request.getContext().get("regionRoute");

        List<ServiceInstance> instances = serviceInstanceListSupplier.get();
        List<ServiceInstance> localInstances = instances.stream()
            .filter(i -> targetRegion.equals(i.getMetadata().get("region")))
            .collect(Collectors.toList());
        List<ServiceInstance> remoteInstances = instances.stream()
            .filter(i -> !targetRegion.equals(i.getMetadata().get("region")))
            .collect(Collectors.toList());

        List<ServiceInstance> candidates;
        switch (routeStrategy) {
            case "force-local":
                // 强制本地：只选本机房实例（适用于写操作）
                candidates = localInstances;
                break;
            case "force-remote":
                // 强制远端：只选对端机房实例（适用于管理操作）
                candidates = remoteInstances;
                break;
            case "prefer-local":
            default:
                // 优先本地：本机房实例优先，无可用实例时降级到对端
                candidates = localInstances.isEmpty() ? remoteInstances : localInstances;
                break;
        }

        if (candidates.isEmpty()) {
            return Mono.just(new EmptyResponse());
        }

        // 加权随机选择（基于实例权重，避免单点过载）
        ServiceInstance selected = weightedRandomChoose(candidates);
        return Mono.just(new DefaultResponse(selected));
    }

    /**
     * 加权随机选择
     * Nacos 实例的 weight 元数据用于控制流量比例
     * 默认 weight=1，可通过 Nacos 控制台动态调整
     */
    private ServiceInstance weightedRandomChoose(List<ServiceInstance> instances) {
        List<Double> weights = instances.stream()
            .map(i -> {
                String w = i.getMetadata().getOrDefault("weight", "1");
                return Double.parseDouble(w);
            })
            .collect(Collectors.toList());

        double totalWeight = weights.stream().mapToDouble(Double::doubleValue).sum();
        double random = ThreadLocalRandom.current().nextDouble(totalWeight);

        double cumulative = 0;
        for (int i = 0; i < instances.size(); i++) {
            cumulative += weights.get(i);
            if (random < cumulative) {
                return instances.get(i);
            }
        }
        return instances.get(instances.size() - 1);
    }
}
```

### 同区域优先 + 避免单点过载

| 场景 | 行为 | 说明 |
|------|------|------|
| 本机房有3个实例 | 加权随机选择 | 每个实例权重默认1，均匀分布 |
| 本机房1个实例，对端2个 | 优先本机房，但可配权重 | 本机房实例权重调低（如0.5），对端权重调高 |
| 本机房0个实例 | 自动降级到对端 | prefer-local 策略的兜底 |
| 写操作请求 | force-local，只路由本机房 | 避免跨机房写，保证数据一致性 |
| 本机房实例全部不健康 | 降级到对端 | LoadBalancer 过滤掉不健康实例后选择 |

---

## 补充三：Feign 多活拦截器与 MDC 透传

> Feign 调用需要透传区域标记，同时需要配合 SLF4j MDC 在日志中输出区域信息，便于问题排查。

### RegionFeignInterceptor

```java
/**
 * Feign 多活拦截器
 * 透传 X-Region-Tag / X-Region-Route / X-Region-Failover 到下游
 * 同时透传 MDC 中的 traceId（全链路追踪）
 */
@Component
public class RegionFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attributes =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();

            // 1. 透传区域标记
            String regionTag = request.getHeader("X-Region-Tag");
            if (StringUtils.isNotBlank(regionTag)) {
                template.header("X-Region-Tag", regionTag);
            }

            // 2. 透传路由策略
            String regionRoute = request.getHeader("X-Region-Route");
            if (StringUtils.isNotBlank(regionRoute)) {
                template.header("X-Region-Route", regionRoute);
            }

            // 3. 透传故障切换标记
            String failover = request.getHeader("X-Region-Failover");
            if (StringUtils.isNotBlank(failover)) {
                template.header("X-Region-Failover", failover);
            }

            // 4. 透传 MDC traceId（全链路追踪）
            String traceId = MDC.get("traceId");
            if (StringUtils.isNotBlank(traceId)) {
                template.header("X-Trace-Id", traceId);
            }
        }
    }
}
```

### MDC 透传增强（RegionContext 与 MDC 集成）

```java
/**
 * RegionContext 与 MDC 集成
 * 每次区域上下文变更时，同步更新 MDC，日志自动输出区域信息
 */
public class RegionContext {

    private static final ThreadLocal<String> REGION_TAG = new ThreadLocal<>();
    private static final ThreadLocal<String> REGION_ROUTE = new ThreadLocal<>();

    public static void setRegion(String regionTag, String routeStrategy) {
        REGION_TAG.set(regionTag);
        REGION_ROUTE.set(routeStrategy);
        // 同步到 MDC，日志 pattern 中使用 %X{regionTag} 输出
        MDC.put("regionTag", regionTag);
        MDC.put("regionRoute", routeStrategy);
    }

    public static void clear() {
        REGION_TAG.remove();
        REGION_ROUTE.remove();
        MDC.remove("regionTag");
        MDC.remove("regionRoute");
    }
}
```

**logback-spring.xml 配置**：

```xml
<!-- 日志格式中包含区域标记 -->
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}] [%X{regionTag}] %-5level %logger{36} - %msg%n</pattern>
```

### 拦截器链叠加顺序

```
Feign 请求拦截器链：
  1. RegionFeignInterceptor   → 透传区域标记
  2. TraceFeignInterceptor    → 透传链路追踪 traceId
  3. AuthFeignInterceptor     → 透传认证 Token
  （顺序：区域 → 追踪 → 认证，区域最优先确保路由正确）
```

---

## 补充四：机房健康检查 vs Nacos 实例健康检测

> 两种健康检测的粒度和用途不同，需要明确区分。

| 维度 | RegionHealthChecker（机房级） | Nacos 健康检测（实例级） |
|------|------|------|
| 检测粒度 | 整个机房 | 单个服务实例 |
| 检测方式 | HTTP 探测机房核心服务 + 数据库连通性 | Nacos 心跳 / TCP 探活 |
| 检测间隔 | 10秒 | 5秒（临时实例） |
| 不可用判定 | 3次连续失败 | 3次心跳丢失 |
| 触发动作 | 整机房流量切换 | 摘除单个实例 |
| 恢复方式 | 人工确认 + 灰度恢复 | 实例心跳恢复后自动注册 |
| 存储位置 | Redis（跨机房共享状态） | Nacos 内存 |

**关键区别**：Nacos 检测到单个实例不健康只会摘除该实例，其他同机房实例仍可服务；RegionHealthChecker 检测到机房不健康会触发整机房流量切换。

---

## 面试高频问题

- Gateway 多活路由如何实现故障自动切换？
  - RegionRouteFilter 在每次请求时检查目标机房健康状态，不可用时自动修改 X-Region-Tag 到对端机房，同时标记 X-Region-Failover=true
- 负载均衡的同区域优先策略如何避免请求全部打到同一个实例？
  - 使用加权随机算法，Nacos 实例的 weight 元数据控制流量比例；同区域多实例时均匀分布，单实例时可配权重降级
- Feign 调用如何保证区域标记透传？
  - RegionFeignInterceptor 在 Feign 发起请求前，从当前 HttpServletRequest 读取 X-Region-Tag 等头部并注入到 Feign 请求模板；同时透传 MDC 中的 traceId
- 多活路由和灰度路由如何兼容？
  - Filter 链顺序：RegionRouteFilter（order=-1000）→ GrayRouteFilter（order=-900），先确定区域再确定灰度；灰度比例可按区域不同配置
- 机房健康检查和 Nacos 实例健康检测有什么区别？
  - 机房级检测粒度更粗，检测核心服务+数据库连通性，触发整机房切换；实例级检测粒度更细，只摘除单个不健康实例
- MDC 透传在多活场景有什么用？
  - 日志中自动输出区域标记和链路 ID，排查跨机房问题时可快速定位请求经过的机房路径

---

**相关专题**：
- ⬅️ [43-注册中心与发现多活](../43-registry-and-discovery-multi-active/README.md)
- ➡️ [45-数据层多活](../45-data-layer-multi-active/README.md)
- 🔗 [42-多活架构概述与选型](../42-multi-active-overview-and-selection/README.md)

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容