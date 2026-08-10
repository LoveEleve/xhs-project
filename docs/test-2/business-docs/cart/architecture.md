# my-xhs-cart 架构分析

## 一、服务拓扑

```
端口: 19008
JVM:  -Xms512m -Xmx512m (BASE)
日志: /tmp/r_cart.log
SkyWalking: my-xhs-cart → OAP 21.130.247.89:11800
Nacos:     namespace=my-xhs, server-addr=21.130.247.89:18848
```

## 二、依赖图

```
my-xhs-cart
├── Redis   myxhs:cart:{userId}:items/checked/sort (主数据源，三结构同slot)
├── MySQL   my_xhs_cart.t_cart_item (MQ异步持久化，uk_user_sku UPSERT)
├── Feign   my-xhs-product GET /api/product/sku/batch (查询商品详情)
├── MQ      CART_TOPIC (生产:ADD/UPDATE/DELETE/CHECK/CHECK_ALL/CLEAR)
│           └── CartSyncConsumer (消费:MySQL异步落库)
└── Job     CartReconcileJob @XxlJob 每天4点对账
```

## 三、Controller 与安全

| Controller | 路径前缀 | 端点数 | 认证 |
|------|------|:--:|------|
| CartController | `/api/cart` | 9 (用户) + 1 (管理) | C01-C09: JWT + X-User-Id, C10: X-Admin-Call |

| 安全层 | 机制 | 适用 |
|------|------|------|
| 认证 | Gateway AuthFilter → X-User-Id Header | C01-C09 |
| 限流 | @RateLimit per user (Redis计数器 `myxhs:cart:*`) | C01-C09 |
| 并发 | Redis单线程+Lua原子 (无Redisson锁依赖) | C01-C05 |
| 管理 | X-Admin-Call header校验 | C10 |

## 四、数据流

### 加入购物车 (C01)
```
POST /api/cart/add {skuId, quantity=1} + X-User-Id
  → CartService.addToCart()
    → cart_add.lua 原子执行:
        HEXISTS查重复 → HINCRBY更新(items Hash)
        HLEN>=50 → 返回-1 (上限50种)
        HINCRBY>99 → 截断99 (单sku上限)
        SADD选中 (checked Set, 默认选中)
        ZADD NX排序 (sort ZSet, score=时间戳)
    → refreshTTL (items/checked/sort各expire 30天)
    → asyncSend CART_TOPIC:ADD → CartSyncConsumer → UPSERT t_cart_item(uk_user_sku)
```

### 勾选商品 (C04)
```
PUT /api/cart/check {skuId, checked} + X-User-Id
  → cart_check_item.lua:
      HEXISTS验证存在 → SADD(勾选) / SREM(取消)
      防TOCTOU: HEXISTS=0 → 跳过(并发删除场景, 不抛异常)
    → asyncSend CART_TOPIC:CHECK
```

### 购物车列表 (C06)
```
GET /api/cart/list + X-User-Id
  → CartService.getCartList()
    → Pipeline 1次RTT取三结构:
        HGETALL items → {skuId→quantity}
        SMEMBERS checked → Set<skuId>
        ZREVRANGE sort → 按addedAt倒序
    → Feign my-xhs-product GET /api/product/sku/batch?skuIds=
      → 失败降级: valid=false (不阻塞返回)
      → 下架商品: valid=false (前端灰色展示)
    → 计算: checkedCount/checkedAmount (仅valid=true)
    → allChecked = 全部valid商品都在checked中
    → 返回 CartListVO(items/checkedCount/checkedAmount/allChecked)
```

### 匿名购物车合并 (C07)
```
POST /api/cart/merge {items:[{skuId,quantity}]} + X-User-Id
  → CartService.mergeAnonymousCart()
    → cart_merge_item.lua 逐匿名商品:
        登录购物车已有 → HGET取现有 → max(匿名qty, 登录qty) → HSET
        登录购物车无 → HINCRBY添加
        已有quantity>=10000 → 发CART_TOPIC:UPDATE事件(量大变提醒)
    → 幂等: 取max而非accumulate (防重复合并数量翻倍)
```

### 数量更新 (C02) — 防复活
```
PUT /api/cart/quantity {skuId, quantity} + X-User-Id
  → cart_update_quantity.lua:
      HEXISTS验证存在 → HSET更新
      不存在 → 抛CART_ITEM_NOT_FOUND (防并发删除后MQ乱序"复活"已删商品)
    → asyncSend CART_TOPIC:UPDATE
```

## 五、三结构协同

```
购物车操作以 Redis 为权威数据源，设计三结构保证原子性：

Hash   myxhs:cart:{userId}:items       skuId → quantity
Set    myxhs:cart:{userId}:checked     {skuId1, skuId2, ...}
ZSet   myxhs:cart:{userId}:sort        skuId score=addedAt

三者通过 {userId} hash tag 强制同slot，Lua脚本一次RTT原子操作三结构。
```

## 六、Lua原子操作

| 脚本 | 操作 | 保证 |
|------|------|------|
| cart_add.lua | HEXISTS+HLEN+HINCRBY+SADD+ZADD NX | 上限50/默认选中/排序 |
| cart_remove.lua | HDEL+SREM+ZREM | 三结构原子删除 |
| cart_check_item.lua | HEXISTS+SADD/SREM | 防TOCTOU幽灵条目 |
| cart_check_all.lua | HKEYS+DEL+SADD | 全选原子重建 |
| cart_update_quantity.lua | HEXISTS+HSET | 防并发删除后"复活" |
| cart_merge_item.lua | HGET/HSET取max | 匿名→登录幂等合并 |

## 七、MQ异步持久化

```
写Redis(Lua原子) → asyncSend CART_TOPIC:{action}
  → CartSyncConsumer
    → UPSERT (uk_user_sku + try/catch)
    → eventTime乱序保护 (NOT isBefore)
```

## 八、Feign降级

CartService.getCartList() 通过 Feign 批量取SKU信息。失败时标记 `valid=false`，不阻塞返回——用户至少能看到数量和skuId。

## 九、对账机制

CartReconcileJob 每天4点扫描三场景：
1. Redis有MySQL无 → INSERT
2. 数量不一致 → MySQL UPDATE为Redis值
3. Redis无MySQL有 → DELETE（但itemsKey不存在时跳过，防全量误删）

## 十、Redis Key 完整清单

| Key | 类型 | TTL | 用途 |
|------|------|:--:|------|
| `myxhs:cart:{userId}:items` | Hash | 30天 | skuId→quantity, HLEN查数量上限 |
| `myxhs:cart:{userId}:checked` | Set | 30天 | 已选中的skuId集合, SADD/SREM/SMEMBERS |
| `myxhs:cart:{userId}:sort` | ZSet | 30天 | skuId→添加时间戳(score), ZADD NX+ZREVRANGE排序 |

> `{userId}` 花括号为 Redis hash tag——三结构强制同 slot，Lua脚本可跨 key 原子操作。TTL 30天，每次操作 refreshTTL 续期。
