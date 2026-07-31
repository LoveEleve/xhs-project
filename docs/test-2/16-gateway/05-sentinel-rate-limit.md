# Gateway Sentinel 限流策略 — 深度技术分析

> 关联源码：`RateLimitFilter.java` / `RateLimiterConfig.java` / `GatewayConfig.java`

---

## 业务背景

网关是流量的第一道关卡。限流的目的：

| 目的 | 说明 |
|---|---|
| 保护下游 | 防止突发流量压垮服务 |
| 防刷 | 拦截恶意高频请求（爬虫/撞库） |
| 成本控制 | 按服务差异化分配流量配额 |

---

## Sentinel 网关适配器

```java
// RateLimitFilter 委托给 SentinelGatewayFilter
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return sentinelGatewayFilter.filter(exchange, chain);
}
```

Sentinel 网关适配器：解析 Gateway 路由 ID，对每个路由做流控（`GatewayFlowRule` 按 routeId 匹配）。

---

## 按服务差异化限流

```java
private void initFlowRules() {
    Set<GatewayFlowRule> rules = new HashSet<>();

    // 核心交易（严格限流）
    rules.add(new GatewayFlowRule("order-service").setCount(500).setIntervalSec(1));
    rules.add(new GatewayFlowRule("payment-service").setCount(300).setIntervalSec(1));
    rules.add(new GatewayFlowRule("inventory-service").setCount(500).setIntervalSec(1));
    rules.add(new GatewayFlowRule("coupon-service").setCount(200).setIntervalSec(1));

    // 用户认证（防暴力破解）
    rules.add(new GatewayFlowRule("user-service").setCount(200).setIntervalSec(1));

    // 高读服务（高 QPS）
    rules.add(new GatewayFlowRule("search-service").setCount(1000).setIntervalSec(1));
    rules.add(new GatewayFlowRule("home-service").setCount(1000).setIntervalSec(1));
    ...
}
```

| 服务 | QPS | 原因 |
|---|---|---|
| search/home | 1000 | 高读场景，弹性大 |
| product/counter | 800-1000 | 读多 |
| content/analytics | 500 | 中等 |
| order/inventory | 500 | 写操作，防并发问题 |
| im/notification | 300-500 | 实时消息 |
| user | 200 | 防暴力破解 |
| coupon | 200 | 优惠券敏感操作 |
| payment | 300 | 支付链路，严格控制 |

---

## 规则加载策略

```java
@EventListener(ApplicationReadyEvent.class)
public void onApplicationReady() {
    boolean nacosDatasourceConfigured = isNacosDatasourceConfigured();

    if (nacosDatasourceConfigured) {
        // 等待 Nacos 推送规则（30 秒）
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
            if (GatewayRuleManager.getRules().isEmpty()) {
                initFlowRules();  // Nacos 没推 → 本地兜底
            }
        });
    } else {
        initFlowRules();  // 无 Nacos → 直接本地规则
    }
}
```

**两级规则源**：
1. **Nacos 动态规则**（生产推荐）：规则可动态调整，无需重启
2. **本地兜底规则**（代码硬编码）：Nacos 不可用时的保底

**30 秒等待窗口**：启动后先等 Nacos 推规则，30 秒内没推则用本地规则兜底。避免启动瞬间无限流保护。

---

## 限流响应

```java
GatewayCallbackManager.setBlockHandler((exchange, t) -> {
    // 429 Too Many Requests
    return ServerResponse.status(429)
        .contentType(APPLICATION_JSON)
        .body(Mono.just(bytes), byte[].class);
});
```

```
HTTP 429
{"code":429,"message":"请求过于频繁，请稍后再试","data":null}
```

---

## 与 Redis RequestRateLimiter 的关系

Gateway 路由配置中还有内置的 `RequestRateLimiter`（Redis 令牌桶）：

```yaml
filters:
  - name: RequestRateLimiter
    args:
      redis-rate-limiter.replenishRate: 50
      redis-rate-limiter.burstCapacity: 100
      key-resolver: "#{@remoteAddrKeyResolver}"
```

**两套限流并存**：

| 方案 | 维度 | 特点 |
|---|---|---|
| Sentinel 网关适配器 | 按服务（routeId） | 全局 QPS，本地内存 |
| Redis RequestRateLimiter | 按 IP | 单 IP 令牌桶，Redis 分布式 |

user-service 路由同时配了 Sentinel（200 QPS）和 Redis 令牌桶（50/100 每 IP）——双层防护：全局配额 + 单 IP 限制。

---

## 面试 Q&A

**Q: Sentinel 限流和 Redis 令牌桶的区别？**
A: Sentinel 是**计数器/滑动窗口**（本地内存，按 routeId 维度）；Redis RequestRateLimiter 是**令牌桶**（Redis 分布式，按 KeyResolver 维度如 IP）。Sentinel 保护全局，Redis 限单 IP。

**Q: 多实例部署时限流是全局还是单机？**
A: 当前 Sentinel 规则是**单机限流**（每实例各自计数）。3 实例部署时实际总 QPS = 单机阈值 × 3。全局精确限流需要 Sentinel Token Server（集群限流）或 Redis 计数。

**Q: 规则重启后丢失吗？**
A: 当前本地规则在启动时重新加载（代码硬编码），不丢失。Nacos 数据源的规则也随配置持久化。Sentinel Dashboard 上手动创建的规则**不持久化**，重启即失（HANDOFF.md 踩坑记录）。

---

## 生产实验

当前 Gateway 规则未实际触发限流（正常 QPS 远低于阈值）。限流响应格式已验证：`{"code":429,"message":"请求过于频繁，请稍后再试","data":null}`。

---

## 发散

### 集群限流

单机限流在多实例下总 QPS 会放大（× 实例数）。生产环境接入 Sentinel Token Server：

```
客户端（每实例）→ 请求 Token Server（全局配额中心）
Token Server → Redis 计数 → 全局精确限流
```

### 热点参数限流

Sentinel 支持热点参数限流（如按 userId 限流）：同一用户 1 秒最多 5 次请求。比全局 QPS 更精细，防单用户刷接口。
