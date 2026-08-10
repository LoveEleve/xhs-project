# C03: 删除商品 — DELETE /api/cart/{skuId}

## § 源码分析

- **Controller**: `CartController.java:60` → `@DeleteMapping("/{skuId}")`, 参数 `X-User-Id` + `@PathVariable Long skuId`
- **Service**: `CartService.java:189` → `removeFromCart()`
  - `cart_remove.lua`: 三结构(HDEL+SREM+ZREM)原子删除
  - MQ DELETE事件
- **下游**: Redis三结构 + MQ CART_TOPIC:DELETE

## § 业务逻辑

Lua原子删除Hash/Set/ZSet三结构中的skuId → MQ异步DELETE事件 → Consumer执行MySQL物理删除

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/cart/{skuId}` | 200 |
| Redis | `r.hexists('myxhs:cart:{userId}:items','{skuId}')` | 0(已删除) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | Lua三结构原子删除 | ✅ |

## § curl

```bash
curl -s -X DELETE http://localhost:19000/api/cart/$SKU_ID \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
curl DELETE /api/cart/{skuId}
  → Gateway → CartController.removeFromCart(X-User-Id, skuId)
    → cart_remove.lua: HDEL+HSREM+ZREM (原子)
    → asyncSend CART_TOPIC:DELETE → Consumer → MySQL DELETE
```
