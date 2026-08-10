# product 对外 API 面：三个 Feign 调用方 + 安全 + 降级

> **源码**: ProductController(164行) + cart ProductFeignClient(52行) + home ProductFeignClient(30行) + search ProductFeignClient(26行)  
> **调用链路**: cart→batchGetSkuDetails / home→getSpuDetail(2层并行聚合) / search→getSpuDetail(ES补全)  
> **关键修复**: S1 @RateLimit perUser / S2 X-User-Id / DCd3 CartAggService死注入 / CC2 cart过期注释 / R6fix ProductAggService key "2:"→"4:"

---

## 1. 10 个端点全景

```
POST   /api/product/spu                  — 创建 SPU [@RateLimit 5/60s perUser]
PUT    /api/product/spu/{spuId}          — 更新 SPU [@RateLimit 5/60s perUser]
GET    /api/product/spu/{spuId}          — SPU 详情（Bloom→Redis→MySQL）
GET    /api/product/spu/list             — SPU 列表（分页≤50, 仅上架）
PUT    /api/product/spu/{spuId}/status   — 上架/下架

POST   /api/product/sku                  — 创建 SKU [@RateLimit 5/60s perUser]
GET    /api/product/sku/{skuId}          — SKU 详情（直查DB）
GET    /api/product/sku/list/{spuId}     — 按 SPU 查 SKU
GET    /api/product/sku/batch            — 批量 SKU [需 X-Internal-Call]

GET    /api/product/category/tree        — 三级分类树（Redis 2h）
```

---

## 2. cart → product：批量 SKU 查询

### 2.1 调用链路（完整）

```
cart CartService.getCartList(userId)
  │
  ├─ 1. Pipeline 读 Redis 三结构（1 次 RTT 替代 3 次）
  │     itemsKey:    myxhs:cart:items:{userId}    (Hash: skuId→quantity)
  │     checkedKey:  myxhs:cart:checked:{userId}  (Set: checked skuIds)
  │     sortKey:     myxhs:cart:sort:{userId}     (ZSet: skuId→timestamp)
  │
  ├─ 2. 校验商品状态: batchGetSkuInfo(skuIds)
  │     │
  │     │  ProductFeignClient.batchGetSkuDetails(skuIds)
  │     │  │  @FeignClient(name = "my-xhs-product")
  │     │  │  X-Internal-Call 自动注入（InternalCallFeignConfig RequestInterceptor）
  │     │  │
  │     │  ▼  product ProductController.batchGetSkuDetails
  │     │     校验 X-Internal-Call → SkuService → WHERE id IN (...) AND status=ON_SHELF
  │     │     返回 List<SkuVO>（只含上架 SKU）
  │     │
  │     │  ▼  购物车逐项判: skuMap.get(skuId) == null → 商品已下架 → 标失效
  │     │      stock <= 0 → 库存不足 → 标失效
  │     │      仅有效选中项计入 checkedAmount
  │     │
  │     │  降级: Feign 异常 → 空 skuMap → 所有项标"商品信息获取失败"，金额为 0
  │
  ├─ 3. 按 sortKey(ZSet) 排序
  │
  └─ 4. 组装 CartListVO（items + checkedCount + checkedAmount + disabledItems）
```

### 2.2 batchGetSkuInfo 源码级分析 (`CartService.java:522-544`)

```java
private Map<Long, ProductFeignClient.SkuDTO> batchGetSkuInfo(List<Long> skuIds) {
    try {
        R<List<ProductFeignClient.SkuDTO>> response = productFeignClient.batchGetSkuDetails(skuIds);
        if (response != null && response.isSuccess() && response.getData() != null) {
            Map<Long, ProductFeignClient.SkuDTO> result = new HashMap<>();
            for (ProductFeignClient.SkuDTO sku : response.getData()) {
                if (sku.getId() != null) {
                    result.put(sku.getId(), sku);
                }
            }
            return result;
        }
    } catch (Exception e) {
        log.warn("[购物车] 批量获取SKU信息失败, 降级跳过");
    }
    return new HashMap<>();
}
```

**注意**：cart 用的是自己的内部 DTO（`ProductFeignClient.SkuDTO`），不是 product 的 `SkuVO`。Feign 序列化/反序列化由 Jackson 自动完成——cart 的 SkuDTO 字段只要与 product 的 SkuVO JSON 字段名一致即可正确映射。这是一种 **DTO 影子模式**——避免跨模块 import。

### 2.3 购物车失效判定（cart 侧，非 product 侧）

```java
// CartService.java — 购物车组装逻辑
for (CartItem cartItem : cartItems) {
    ProductFeignClient.SkuDTO sku = skuMap.get(cartItem.getSkuId());
    if (sku == null) {
        item.setStatus("disabled");       // 商品已下架
    } else if (sku.getStock() <= 0) {
        item.setStatus("insufficient");   // 库存不足
    } else {
        checkedAmount += sku.getPrice() * quantity;  // 计入总价
    }
}
```

product 只负责返回 SKU 数据——是否失效、是否计入金额是 **cart 的业务逻辑**，由 cart 自行判断。这是正确的分层边界。

### 2.4 InternalCallFeignConfig：自动注入机制

```java
// cart 模块 InternalCallFeignConfig.java
public class InternalCallFeignConfig implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        template.header("X-Internal-Call", "myxhs-internal-2026");
    }
}

// 绑定到 FeignClient
@FeignClient(name = "my-xhs-product",
        fallbackFactory = ProductFeignFallbackFactory.class,
        configuration = InternalCallFeignConfig.class)  // ← 自动注入
public interface ProductFeignClient { ... }
```

每个 Feign 请求的 RequestTemplate 构建时自动调用 `apply()`——向 Header 注入内部令牌。对调用方完全透明。这是硬编码共享密钥模式——安全级别有限但在内网环境下够用。

### 2.5 降级深度

| 层级 | 故障 | cart 行为 |
|:--:|------|------|
| Feign 连接超时 | connectTimeout=500ms | FallbackFactory → `R.fail("商品服务暂不可用")` |
| Feign 读超时 | readTimeout=2000ms | 同上 |
| product 500 | 业务异常 | response.isSuccess()=false → 空 skuMap |
| product 返回 403 | X-Internal-Call 缺失 | FallbackFactory 不触发（HTTP 403 是成功响应） |

**FallbackFactory vs catch Exception**：`batchGetSkuInfo` 最外层有 `try-catch(Exception)`，与 FallbackFactory 双保险——Fallback 处理 HTTP 层面的失败，catch 处理 Spring 层面的异常。

---

## 3. home → product：2 层并行聚合

### 3.1 架构

home 的 `ProductAggService.getProductDetail()` 是整个 **BFF（Backend For Frontend）** 层——它将多个微服务（product/inventory/counter）的数据聚合成一个前端友好的 VO。

```
home ProductAggService.getProductDetail(spuId) — 全局超时 4000ms（4s）
  │
  ├── 第 1 层并行（2 个 CompletableFuture，aggregatorPool 线程池）
  │   ├─ SPU 详情: ProductFeignClient.getSpuDetail(spuId)
  │   │   → R<Map<String,Object>>（松散类型，规避跨模块 DTO 依赖）
  │   │
  │   └─ 计数器: CounterFeignClient.batchGetCounts({targetType:4, targetId:spuId, countTypes:[2,5]})
  │       → 取出收藏数(collectCount) 和 浏览数(viewCount)
  │       → response key = "4:{spuId}"（修复 R6fix: 原本 key 用了错误的 "2:{spuId}"）
  │
  │   allOf().get(3s timeout)
  │   SPU 不存在 → spuData 空 → return null（商品 404）
  │
  ├── 提取 skuListRaw
  │
  └── 第 2 层并行（逐 SKU，batchFeignPool 线程池）
      └─ 各 SKU 库存: InventoryFeignClient.getStock(skuId)
         动态超时 = max(500ms, 4000ms - 第1层已耗时)
         逐个组装 SkuWithStockVO {skuId, price, availableStock, hasStock}
```

### 3.2 双线程池设计

```java
// ProductAggService 注入两个独立线程池
private final ExecutorService aggregatorPool;   // 第 1 层: SPU+计数器
private final ExecutorService batchFeignPool;    // 第 2 层: 各 SKU 库存
```

| 池 | 职责 | 并发度 |
|:--:|------|:--:|
| aggregatorPool | 2 个并行任务 | 固定 2 个 Future |
| batchFeignPool | N 个 SKU（N 可能 20+） | N 个并行 Future |

**为什么分两个池？** 第 1 层和延迟 SLA（3s）不同（动态），分池避免第 1 层的 SPU 查询被阻塞在第 2 层的 20 个库存查询排队的线程后面。

### 3.3 动态超时

```java
long elapsedMs = System.currentTimeMillis() - startTime;
long layer2TimeoutMs = Math.max(500, globalTimeoutMs - elapsedMs);
// 第 1 层耗时 2s → 第 2 层有 2s
// 第 1 层耗时 3.5s → 第 2 层只有 500ms（保底）
```

### 3.4 SkuWithStockVO 组装

```java
// aggregateSkuStock — 逐 SKU 并发查 inventory
for (Map<String, Object> sku : skuListRaw) {
    CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(
        () -> {
            R<Map<String, Object>> r = inventoryFeignClient.getStock(skuId);
            return r.isSuccess() ? r.getData() : emptyMap();
        }, batchFeignPool);
    stockFutures.put(skuId, future);
}
CompletableFuture.allOf(...).get(timeoutMs, MILLISECONDS);

// 逐 SKU 组装
sku.get("price")       → price (BigDecimal 类型兼容处理)
stockData.get("availableStock") → availableStock (Integer)
availableStock > 0     → hasStock (boolean)
```

**注意**：SKU 库存查询是 N 个独立的 Feign 调用，不是 batch。这是因为 inventory 只提供单查接口——按设计，每个 SKU 的库存独立。这是架构层面的取舍。

### 3.5 降级矩阵

| 层 | 故障 | 产品页行为 |
|:--:|------|------|
| SPU 详情 | Feign 失败/超时 | spuData 空 → 整页返回 null（商品不存在） |
| 计数器 | Feign 失败 | collectCount/viewCount 显示 0 |
| 库存(单个SKU) | Feign 失败 | 该 SKU availableStock=null, hasStock=false |
| 第 1 层超时 | allOf(3s) | spuData 可能为空 → 404 |
| 第 2 层超时 | 动态超时触发 | 超时的 SKU 显示"暂无库存" |

**关键**：产品页是读多写少的场景——降级策略以 "可用性优先" 为原则，不因单个下游服务故障而整页崩溃。

---

## 4. search → product：ES 索引补全

### 4.1 调用链路

```
Canal → PRODUCT_INDEX_TOPIC → ProductIndexSyncConsumer
  │
  ├─ Canal 消息解析 spuId + 基础字段（name/category_id/status）
  │
  ├─ ProductFeignClient.getSpuDetail(spuId) ← Feign 补全
  │     @FeignClient(name = "my-xhs-product")
  │     无 X-Internal-Call（Consumer 是内部服务，直接调用公开接口）
  │
  └─ ES index 写入 product_index
```

### 4.2 补全字段映射

| 字段 | 来源 | 不可用时默认 |
|------|------|------|
| categoryName | SpuDetailVO.categoryName | "" |
| price | skuList[0].price | 0 |
| image | images[0] | "" |
| brandName | 无数据源 | null |

product 服务不可用时 → Feign 抛异常 → Consumer catch → log.warn → **不阻塞消费**，字段用默认值，Canal 下次重试。

### 4.3 为什么 search 不调 X-Internal-Call？

search 的 ProductFeignClient 是最简单的 Feign 声明——没有 `configuration`、没有 `fallbackFactory`。因为：
- 调的是公开接口 `/api/product/spu/{spuId}`（不需要内部令牌）
- 异常在 Consumer 层 catch（不需要 Fallback）
- search 是最终消费者——不需要降级逻辑，失败就跳过

---

## 5. Feign 基础设施

### 5.1 连接池配置（product 侧 application.yml）

```yaml
spring.cloud.openfeign:
  compression.request.enabled: true        # GZIP 压缩请求体
  compression.response.enabled: true       # GZIP 解压响应体
  httpclient.hc5.enabled: true             # HttpClient 5 替代默认 URLConnection
  httpclient.hc5.max-connections: 200      # 全服务最大连接数
  httpclient.hc5.max-connections-per-route: 50  # 单服务最大连接数
  client.config.default:
    connect-timeout: 500                   # 建连超时 500ms
    read-timeout: 2000                     # 读超时 2s
```

### 5.2 三个 Feign 客户端对比

| 特性 | cart | home | search |
|------|:--:|:--:|:--:|
| 服务发现 | `name="my-xhs-product"` | `name="my-xhs-product"` | `name="my-xhs-product"` |
| 认证 | X-Internal-Call 自动注入 | JWT+HMAC(用户请求) | 无（内部消费） |
| 响应类型 | `R<List<SkuDTO>>`（强类型） | `R<Map>`（松散） | `R<Map>`（松散） |
| Fallback | FallbackFactory → R.fail | FallbackFactory → R.ok(emptyMap) | 无（Consumer catch） |
| 批量优化 | ✅ 一次 IN 查询 | N/A | N/A |
| 在线程池中 | 主线程 | aggregatorPool | Consumer 线程 |

### 5.3 松散类型 vs 强类型的取舍

| 维度 | 强类型（cart） | 松散类型（home/search） |
|------|:--:|:--:|
| 编译时安全 | ✅ | ❌（运行时 NPE 风险） |
| 模块解耦 | ❌（需 import product DTO） | ✅（字段名级耦合） |
| DTO 演进 | product 改字段→cart 编译失败 | product 改字段→home 可能 NPE |

cart 选强类型是因为高频调用且 cart 本身就依赖 product；home 选松散类型是因为 home 已经是 5 个 Feign 的聚合层，减少依赖类型是首要考虑。

---

## 6. 安全机制

### 6.1 @RateLimit（修复 S1）

| 端点 | 限制 | 说明 |
|------|------|------|
| createSpu | 5次/60s perUser | 独立用户计数 |
| updateSpu | 5次/60s perUser | 同上 |
| createSku | 5次/60s perUser | 同上 |

修复前 `perUser=false` 全局共享——任一用户耗尽 10 次配额 = 轻微 DoS。

### 6.2 X-Internal-Call

`batchGetSkuDetails` 唯一需要内部令牌的端点。cart 通过 `InternalCallFeignConfig` RequestInterceptor 自动注入。缺失返回 403。

### 6.3 X-User-Id（修复 S2）

写操作收取 `X-User-Id`（Gateway JWT 注入），但 `t_spu` 无 `creatorUserId` 字段——当前不做所有权校验，只 log.info 记录操作者。

---

## 7. 面试 Q&A

### Q1: 为什么 home 用 `R<Map<String,Object>>` 松散类型而不是 strong-type DTO？

避免跨模块 DTO 依赖。home 如果 import `SpuDetailVO` → product 改字段 → home 编译失败。Map 的代价是运行时 NPE 风险——接受这个 tradeoff 换取模块间松耦合。

### Q2: cart 的 `InternalCallFeignConfig` 每次都注入 Header，有性能影响吗？

`RequestInterceptor.apply()` 在每次 Feign 调用的 `RequestTemplate` 构建阶段执行——这是一次 O(1) 内存操作。相比 Feign 的网络 IO（毫秒级），是完全可以忽略的。

### Q3: 第 2 层动态超时最少 500ms——如果超了怎么办？

`CompletableFuture.getNow()` 在 allOf 超时后对超时的 Future 返回 `emptyMap()` → `availableStock=null`, `hasStock=false` → 前端展示"暂无库存"。用户仍然能看到商品——库存查询失败不阻塞页面。

### Q4: 三个调用方的降级行为不一致——是不是设计缺陷？

不是——恰恰是差异化设计。cart 标失效（用户还能看价格）、home 返回 null（整页不可用）、search 用默认值（索引不完整但能搜到）。统一降级会抹杀各调用方对可用性的不同要求。

### Q5: 为什么 cart 用 batchGetSkuDetails 而不是逐 SKU 调 getSkuDetail？

批量接口 `WHERE id IN (...)` 替代 N 次循环单查——N=20 个购物车项 = 1 次 SQL 而非 20 次。这就是 product 提供批量接口的动机：**一次 I/O 替代 N 次 I/O**。
