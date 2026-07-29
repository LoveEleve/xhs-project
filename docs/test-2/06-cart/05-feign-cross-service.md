# Feign 跨服务调用与降级

> 源码：`ProductFeignClient.java` + `ProductFeignFallbackFactory.java` + `CartService.batchGetSkuInfo()`
> 验证：`02-cart-test.md` §1.2

---

## 1. 第一个依赖其他服务的模块

从 01 到 05，所有模块都是通过 MQ 异步解耦的独立服务。cart 是第一个通过 Feign 直接 HTTP 调用其他服务的模块：

```java
@FeignClient(name = "my-xhs-product", fallbackFactory = ProductFeignFallbackFactory.class)
public interface ProductFeignClient {
    @GetMapping("/api/product/sku/batch")
    R<List<SkuDTO>> batchGetSkuDetails(@RequestParam("skuIds") List<Long> skuIds);
}
```

**调用链路**：cart → Nacos 服务发现 → Spring Cloud LoadBalancer → product 实例 → `/api/product/sku/batch`。

---

## 2. 调用场景与数据流

```
getCartList(userId):
  Pipeline: HGETALL + SMEMBERS + ZREVRANGE → {skuId:qty}, {checked}, {sort}
  Feign: batchGetSkuDetails(skuIds) → [SkuDTO{name,price,stock,status,...}]
  组装: CartItemVO { skuId + qty + checked + sort + name + price + valid }
```

Redis 是购物车数据的权威源——商品 ID、数量、勾选、排序。Feign 调用只用于填充展示信息：名称、价格、库存、上下架状态。即使 product 完全不可用，购物车的基本功能（查看有哪些商品、多少数量）仍然正常。

---

## 3. 降级策略

```java
// CartService.batchGetSkuInfo():
try {
    R<List<SkuDTO>> response = productFeignClient.batchGetSkuDetails(skuIds);
    if (response != null && response.isSuccess() && response.getData() != null) {
        // 正常：构建 skuMap
        for (SkuDTO sku : response.getData()) {
            result.put(sku.getId(), sku);
        }
    }
} catch (Exception e) {
    log.warn("批量获取SKU信息失败, 降级跳过, skuIds={}, error={}", skuIds, e.getMessage());
}

return new HashMap<>();  // 降级：返回空 Map
```

降级后购物车列表中的商品标记为 `valid=false, invalidReason="商品信息获取失败"`。购物车仍然展示——用户能看到有哪些 SKU 和数量，只是看不到价格和名称。

**FallbackFactory 层**：`ProductFeignFallbackFactory` 在 Feign 层面处理 product 服务不可用（熔断/超时）。但这只是第一层——`CartService.batchGetSkuInfo` 中的 `try-catch` 是第二层兜底，覆盖了 Feign 返回 null 或序列化异常等非标准错误。

---

## 4. 批量优化 vs 循环单查

Product 服务提供了 `/api/product/sku/batch`（`SELECT * FROM t_sku WHERE id IN (...)`），cart 侧实现：

```java
// ✅ 批量调用（1 次网络往返）
Map<Long, SkuDTO> skuMap = batchGetSkuInfo(skuIds);
// → Feign GET /api/product/sku/batch?skuIds=100,200,300

// ❌ 循环单查（N 次网络往返，现未使用）
for (Long skuId : skuIds) {
    R<SkuDTO> resp = productFeignClient.getSkuDetail(skuId);
}
```

购物车 50 种商品，批量调用的延迟是 ~3ms（1 次 Feign RTT），循环单查是 ~150ms（50 次 Feign RTT）。批量接口是前端购物车页加载体验的关键——50 个循环 Feign 调用会让页面加载几秒。

---

## 5. Feign 配置与 Nacos 服务发现

```yaml
# application.yml (从 my-xhs-cart)
spring.cloud.nacos.discovery:
  enabled: true
  server-addr: 21.130.247.89:18848
  namespace: my-xhs
  group: DEFAULT_GROUP

spring.cloud.openfeign.client.config.default:
  connect-timeout: 500     # 连接超时 500ms
  read-timeout: 2000       # 读取超时 2s
```

Feign 通过 Nacos 发现 `my-xhs-product` 的实例列表，Spring Cloud LoadBalancer 做客户端负载均衡。

**实测发现**：Nacos 上 product 实例 IP 为 `21.214.97.212:19006`（healthy=true），但 cart 在同一台机器（localhost）。Feign 路由到 `21.214.97.212` 时网络不可达，触发降级。直连 `localhost:19006` 正常。

**根因**：product 注册时使用了多网卡中的外网 IP（21.214.97.212），而 cart 无法通过该 IP 访问 product。这暴露了多网卡环境下的 Nacos 注册配置问题——应该注册内网 IP 或 localhost，或者配置 `spring.cloud.nacos.discovery.ip`。

---

## 6. 发散：Feign vs Dubbo vs MQ 的适用场景

| 通信方式 | cart 的使用 | 适用场景 | 不适合场景 |
|------|:---:|------|------|
| **Feign (HTTP)** | 调用 product 获取 SKU 详情 | 同步读操作（需要立刻拿到数据组装响应） | 写操作（不应阻塞用户） |
| **MQ (RocketMQ)** | cart → CART_TOPIC → CartSyncConsumer → MySQL | 异步持久化（写完 Redis 立即返回） | 需要返回值的操作 |
| **Dubbo (RPC)** | 未使用 | 高吞吐内部调用（比 HTTP 快但需 Dubbo 依赖） | 跨部门跨语言调用 |

cart 对 product 的调用是典型的读操作——getCartList 需要 product 返回 SKU 详情来组装响应。这天然适合 Feign/REST。如果改用 MQ，需要 "发送请求 → 等待回包"，延迟不可控且实现复杂。

---

## 7. 已知局限

| 局限 | 说明 |
|------|------|
| Feign 无本地缓存 | 每次 getCartList 都远程调用，SKU 详情（名称、价格）变更频率低，可加 Redis 缓存 |
| Fallback 丢失部分信息 | product 不可用时用户看不到商品名称和价格，但仍然能操作购物车 |
| 无熔断策略 | 当前只有 Fallback，没有 Sentinel/@RateLimit 熔断——product 故障时持续尝试 |

---

## 关联文档

- `01-cart-module.md` — §5 Feign 跨服务调用
- `02-cart-test.md` — §1.2 Feign Fallback 降级验证
