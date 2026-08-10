# C01: 加入购物车 — POST /api/cart/add

## § 源码分析

- **Controller**: `CartController.java:36` → `@PostMapping("/add")`, 参数 `@RequestHeader("X-User-Id")` + `@Valid @RequestBody CartAddRequest`
- **Service**: `CartService.java:114` → `addToCart()`
  - `cart_add.lua` 原子执行: `HEXISTS` 存在→HINCRBY+截断99, 不存在→HLEN上限检查50→HINCRBY
  - `SADD checked` 默认选中 + `ZADD NX` 排序（不覆盖已有）
  - 返回-1=超上限、0=已有追加、1=新增
  - 成功后 refreshTTL(30天) + `asyncSend("CART_TOPIC:ADD")`
- **下游**: Redis三结构(Hash+Set+ZSet)、MQ CART_TOPIC

## § 业务逻辑

用户提交skuId+quantity → Lua脚本原子操作Redis三结构(数量/选中/排序) → 上限50种校验 → 默认选中 → 30天TTL → MQ异步事件ADD → CartSyncConsumer UPSERT MySQL (uk_user_sku幂等)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| SKU已创建 | `mysql -e "SELECT id FROM my_xhs_product.t_sku LIMIT 1"` | 商品无效 |
| 购物车未满50 | `python3 -c "r.hlen('myxhs:cart:{userId}:items')"` < 50 | 返回-1 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/cart/add -d '{...}'` | 200 |
| Redis | `r.hget('myxhs:cart:{userId}:items','{skuId}')` | quantity |
| Redis | `r.sismember('myxhs:cart:{userId}:checked','{skuId}')` | 1(默认选中) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | Lua单次RTT | ✅ |
| 并发 | Lua原子+hash tag同slot | ✅ |
| 微服务 | MQ异步落MySQL | ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
SKU_ID=2085989641275572226
curl -s -X POST http://localhost:19000/api/cart/add \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"skuId\":$SKU_ID,\"quantity\":1}"
```

## § ASCII流转图

```
curl POST /api/cart/add + Authorization + body{skuId,quantity}
  → Gateway(AuthFilter→X-User-Id)
    → my-xhs-cart:19008 CartController.addToCart(X-User-Id, skuId, quantity)
      → CartService.addToCart()
        → cart_add.lua: HEXISTS+HLEN+HINCRBY+SADD+ZADD NX (原子)
        → expire items/checked/sort 30天
        → asyncSend CART_TOPIC:ADD → CartSyncConsumer → MySQL UPSERT
      → 返回 ok
```
