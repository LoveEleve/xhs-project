# my-xhs-cart 购物车服务模块

## 模块概览

| 项目 | 内容 |
|------|------|
| 模块路径 | `my-xhs-cart/` |
| 端口 | 19008 |
| 服务名 | `my-xhs-cart`（Nacos） |
| 数据库 | `my_xhs_cart`（MySQL 13307，独立数据库） |
| Java 源文件 | 18 个 |
| 启动类 | `CartApplication.java` |

**职责边界**：用户购物车 CRUD、勾选/全选管理、匿名购物车合并。Redis 为权威数据源（三结构协同），MQ 异步持久化到 MySQL，Feign 调用 product 获取 SKU 详情。

**跨服务调用**：`@FeignClient(name = "my-xhs-product")` — cart 是第一个依赖其他服务的模块。

---

## 1. 数据模型

### 1.1 Redis 三结构（权威数据源）

每个用户的三组 Redis Key 使用 `{userId}` hash tag 保证同 slot：

```
myxhs:cart:{userId}:items   — Hash   field=skuId, value=quantity
myxhs:cart:{userId}:checked — Set    member=skuId
myxhs:cart:{userId}:sort    — ZSet   member=skuId, score=timestamp
```

**Hash Tag 设计**：`{userId}` 是 Redis Cluster 的 hash tag——只对 `{}` 内的部分计算 slot。同一用户的三 Key 必定落在同一个 Redis 节点上，Lua 脚本操作多 Key 时不会报 `CROSSSLOT` 错误。

### 1.2 MySQL 持久化（t_cart_item）

```sql
id            BIGINT PRIMARY KEY        -- 雪花 ID
user_id       BIGINT                    -- 用户 ID
sku_id        BIGINT                    -- SKU ID
quantity      INT                       -- 数量
checked       TINYINT                   -- 0=未勾选 1=勾选
deleted       TINYINT DEFAULT 0         -- 不适用：购物车使用物理删除，不继承 BaseEntity
created_at    DATETIME
updated_at    DATETIME
```
> 购物车不继承 BaseEntity（MyBatis-Plus 不会追加 `deleted=0` 条件）。

唯一索引：`uk_user_sku(user_id, sku_id)`

---

## 2. 接口清单（9 个 REST 端点）

| 方法 | 路径 | 说明 | 限流 |
|:----:|------|------|------|
| POST | `/api/cart/add` | 加入购物车（Lua 原子） | @RateLimit(20/60s, perUser) |
| PUT | `/api/cart/quantity` | 修改数量 | — |
| DELETE | `/api/cart/{skuId}` | 删除商品（Lua 原子） | — |
| PUT | `/api/cart/check` | 勾选/取消勾选 | — |
| PUT | `/api/cart/check-all` | 全选/取消全选（Lua 原子） | — |
| GET | `/api/cart/list` | 购物车列表（Pipeline+Feign） | — |
| POST | `/api/cart/merge` | 匿名购物车合并 | — |
| DELETE | `/api/cart/clear` | 清空购物车 | — |
| GET | `/api/cart/count` | 商品品种数（角标用） | — |

所有接口需 `X-User-Id` Header。

### 2.1 POST /api/cart/add

```json
// 请求
POST /api/cart/add
Header: X-User-Id: 10001
{ "skuId": 2081544572120371202, "quantity": 2 }

// 成功
→ 200  {"code":200}
// 品种超上限（购物车已有 50 种不同商品）
→ 500  {"message":"购物车最多添加50种商品"}
// 单品数量超上限也不报错——Lua 脚本自动截断到 99
```

### 2.2 GET /api/cart/list

```json
{
    "code": 200,
    "data": {
        "items": [{
            "skuId": 2081544572120371202,
            "spuId": 2081302094884671490,
            "name": "测试SKU-黑色-L",
            "price": 99.00,
            "quantity": 2,
            "checked": true,
            "valid": true,
            "addedAt": 1738387200000
        }],
        "checkedCount": 2,
        "checkedAmount": 198.00,
        "totalCount": 1,
        "allChecked": true
    }
}
```

---

## 3. Redis 三结构协同设计

```
addToCart:
  Lua: HEXISTS(skuId) → HLEN ≤ 50 → HINCRBY + 截断(≤99) → SADD(checked) → ZADD(NX, sort)

removeFromCart:
  Lua: HDEL(skuId) + SREM(checked) + ZREM(sort)  ← 三结构原子删除

checkAll:
  Lua: HKEYS → DEL(checked) → SADD(check all)  ← 消除竞态窗口

getCartList:
  Pipeline: HGETALL + SMEMBERS + ZREVRANGE  ← 1次往返获取三结构
  Feign: batchGetSkuDetails(skuIds)          ← 调用 product 获取 SKU 详情
```

### 为什么勾选状态用 Set？为什么排序用 ZSet？

| 数据结构 | 用途 | 为什么不是其他结构 |
|------|------|------|
| Hash(items) | 商品+数量 | `HSET`/`HGET`/`HDEL` O(1)，`HGETALL` 一次取全部 |
| Set(checked) | 选中状态 | `SADD`/`SREM` O(1)，`SISMEMBER` 判断勾选 O(1) |
| ZSet(sort) | 加购时间排序 | `ZADD score=timestamp`，`ZREVRANGE` 按时间倒序 |

三结构分离避免了"一个 Key 存所有信息"导致的串行化问题——勾选状态不需要变更为量，排序只需要时间戳，互不影响。

---

## 4. Lua 脚本原子性

购物车有 3 个 Lua 脚本（`RedisScriptConfig.java` 中定义为 Bean）：

| 脚本 | 操作 | 原子性保证 |
|------|------|------|
| `cartAddScript` | HEXISTS + HLEN + HINCRBY + 截断 + SADD + ZADD | 5 命令原子——防并发超上限 |
| `cartRemoveScript` | HDEL + SREM + ZREM | 三结构原子删除——防幽灵商品 |
| `cartCheckAllScript` | HKEYS + DEL + SADD | 全选原子——防并发 addToCart 丢失选中 |

**为什么 add 操作需要 Lua？** 并发场景下，两个线程同时 `addToCart`，如果先检查 HLEN 再 HINCRBY，两者都看到 count=49，都执行 HINCRBY → count=51，超出上限。Lua 脚本中 `HEXISTS + HLEN + HINCRBY` 在 Redis 单线程中原子执行，消除了检查-执行的窗口。

**为什么 remove 操作需要 Lua？** 如果分三次命令删除 HDEL + SREM + ZREM，中途线程切换可能导致"Hash 中已删除但 Set 中仍存在"的幽灵商品。

**哪些操作不需要 Lua？**
- `updateQuantity`：只有 HSET，单命令原子
- `checkItem`：只有 SADD/SREM，幂等操作，并发无脏数据

---

## 5. Feign 跨服务调用

```java
@FeignClient(name = "my-xhs-product", fallbackFactory = ProductFeignFallbackFactory.class)
public interface ProductFeignClient {
    @GetMapping("/api/product/sku/batch")
    R<List<SkuDTO>> batchGetSkuDetails(@RequestParam("skuIds") List<Long> skuIds);
}
```

**调用场景**：`getCartList` 获取购物车列表时，通过 Feign 调用 product 的 `/api/product/sku/batch` 获取每个 SKU 的详情（名称、价格、库存状态、上下架状态）。

**降级策略**：`ProductFeignFallbackFactory` 在 product 服务不可用时返回空列表——购物车仍可展示，但所有商品标记为 `valid=false, invalidReason="商品信息获取失败"`。

**批量优化**：product 已提供 `batchGetSkuDetails`（`WHERE id IN (...)`），cart 侧一次调用获取全部 SKU 信息，而非循环单查。

---

## 6. MQ 异步持久化

```
写操作（add/update/delete/check/clear）:
  1. Redis 立即写入（Lua 原子 / 单命令）
  2. 返回 200
  3. 异步发送 CartSyncEvent → CART_TOPIC → CartSyncConsumer → MySQL

MQ 发送失败策略:
  - 使用 RocketMQ asyncSend + callback
  - 发送失败只记录日志，不影响购物车操作
  - Redis 为权威数据源，MQ 只是持久化通道
```

**对账修复**：`CartReconcileJob`（XXL-Job）将 Redis 和 MySQL 的购物车数据逐条比对——Redis 有 MySQL 无 → INSERT MySQL；Redis 无 MySQL 有 → DELETE MySQL。

---

## 7. 匿名购物车合并

```
mergeAnonymousCart(userId, anonymousItems):
  for each anonymous item:
    if exists(user cart):
      mergedQty = min(max(currentQty, anonymousQty), 99)  // 取较大但不超过上限
    else if cart < 50:
      add new item
    SADD(checkedKey)
    ZADD(sortKey, NX)  // NX: 已存在不更新时间戳
```

**合并策略**：匿名购物车与登录购物车合并——同一 SKU 取较大数量，新 SKU 直接加入（上限 50）。幂等合并（取 max 值），多次合并结果一致。

---

## 8. 已知问题与改进

| # | 问题 | 严重性 | 分类 |
|:--:|------|:------:|:--:|
| c1 | `mergeAnonymousCart` 非 Lua 原子——`HLEN` 检查超限 + `HSET` 写入之间，并发 `addToCart`（Lua 原子）可能使购物车超过 50 上限。合并是登录时的低频操作，实际概率极低 | 低 | 设计权衡（低频操作不值得 Lua 脚本复杂度） |
| c2 | Feign 调 product 无本地缓存——每次 `getCartList` 都远程调用 `/sku/batch`，SKU 详情（名称、价格）变更频率低 | 中 | 可优化（加 Redis 缓存 TTL 5-10min，购物车本身已是 Redis 读写，加一层 SKU info 缓存开销小） |
| c3 | `getCartList` Pipeline + Feign 非原子——Pipeline 读取三结构后、Feign 返回前，购物车可能被其他操作变更（如商品被秒杀光了）。返回的 `checkedAmount` 可能不等于实际 | 低 | 设计权衡（购物车数据允许瞬时不一致，下单时由 order 模块做最终校验） |

---

## 9. 模块文件清单

```
my-xhs-cart/src/main/java/com/myxhs/cart/
├── CartApplication.java              # 启动类
├── controller/
│   └── CartController.java           # 9 个 REST 端点
├── service/
│   └── CartService.java              # Redis三结构 + Lua + Feign + MQ
├── config/
│   └── RedisScriptConfig.java        # Lua 脚本 Bean 注册
├── consumer/
│   └── CartSyncConsumer.java         # MQ 消费者（Redis→MySQL）
├── job/
│   └── CartReconcileJob.java         # XXL-Job 对账修复
├── feign/
│   ├── ProductFeignClient.java       # Feign 调用 product
│   └── ProductFeignFallbackFactory.java # product 不可用降级
├── entity/
│   └── CartItem.java                 # t_cart_item 实体
├── mapper/
│   └── CartItemMapper.java           # MyBatis-Plus BaseMapper
├── dto/
│   ├── event/CartSyncEvent.java      # MQ 事件消息体
│   ├── request/
│   │   ├── CartAddRequest.java
│   │   ├── CartCheckRequest.java
│   │   ├── CartMergeRequest.java
│   │   └── CartUpdateQuantityRequest.java
│   └── response/
│       ├── CartItemVO.java
│       └── CartListVO.java
```

---

## 关联文档

- `02-cart-test.md` — curl 测试用例与结果记录
- `03-redis-structure.md` — Redis 三结构协同设计深度分析
- `04-lua-scripts.md` — Lua 脚本原子性详解
- `05-feign-cross-service.md` — Feign 跨服务调用与降级
- `06-mq-persistence.md` — MQ 异步持久化与对账
- `07-anonymous-merge.md` — 匿名购物车合并算法
