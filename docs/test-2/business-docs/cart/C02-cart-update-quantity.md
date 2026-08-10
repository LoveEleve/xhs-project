# C02: 修改数量 — PUT /api/cart/quantity

## § 源码分析

- **Controller**: `CartController.java:48` → `@PutMapping("/quantity")`, 参数 `X-User-Id` + `@Valid @RequestBody CartUpdateQuantityRequest`
- **Service**: `CartService.java:156` → `updateQuantity()`
  - `cart_update_quantity.lua`: HEXISTS校验skuId存在 → HSET quantity
  - HEXISTS不存在抛 `CART_ITEM_NOT_FOUND` — 防并发删除后"复活"
  - 成功后 MQ UPDATE事件
- **下游**: Redis Hash + MQ CART_TOPIC:UPDATE

## § 业务逻辑

Lua校验skuId存在于购物车 → 更新Hash中quantity → 失败则抛CART_ITEM_NOT_FOUND(防"复活") → MQ异步事件UPDATE

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| skuId在购物车中 | `r.hexists('myxhs:cart:{userId}:items','{skuId}')` | CART_ITEM_NOT_FOUND |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X PUT /api/cart/quantity -d '{...}'` | 200 |
| Redis | `r.hget('myxhs:cart:{userId}:items','{skuId}')` | 新quantity |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | HEXISTS防并发删除 | ✅ |

## § curl

```bash
curl -s -X PUT http://localhost:19000/api/cart/quantity \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"skuId\":$SKU_ID,\"quantity\":3}"
```

## § ASCII流转图

```
curl PUT /api/cart/quantity + body{skuId, quantity}
  → Gateway → CartController.updateQuantity()
    → cart_update_quantity.lua: HEXISTS check → HSET
    → asyncSend CART_TOPIC:UPDATE
```
