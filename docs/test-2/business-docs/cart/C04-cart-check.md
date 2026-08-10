# C04: 勾选 — PUT /api/cart/check

## § 源码分析

- **Controller**: `CartController.java:72` → `@PutMapping("/check")`, 参数 `X-User-Id` + `@Valid @RequestBody CartCheckRequest{skuId, checked}`
- **Service**: `CartService.java:219` → `checkItem()`
  - `cart_check_item.lua`: HEXISTS验证skuId存在 → checked=true则SADD checked, false则SREM
  - HEXISTS不存在抛 CART_ITEM_NOT_FOUND — 防TOCTOU幽灵条目
  - MQ CHECK事件
- **下游**: Redis Set(checked) + MQ CART_TOPIC:CHECK

## § 业务逻辑

Lua校验skuId存在 → checked=true加SADD选中、false加SREM取消 → TOCTOU防并发删除 → MQ异步事件

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| skuId在购物车中 | `r.hexists('myxhs:cart:{userId}:items','{skuId}')` | CART_ITEM_NOT_FOUND |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X PUT /api/cart/check -d '{...}'` | 200 |
| Redis | `r.sismember('myxhs:cart:{userId}:checked','{skuId}')` | 1(checked=true) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | HEXISTS防TOCTOU幽灵条目 | ✅ |

## § curl

```bash
curl -s -X PUT http://localhost:19000/api/cart/check \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"skuId\":$SKU_ID,\"checked\":true}"
```

## § ASCII流转图

```
curl PUT /api/cart/check + body{skuId, checked}
  → Gateway → CartController.checkItem()
    → cart_check_item.lua: HEXISTS → SADD/SREM
    → asyncSend CART_TOPIC:CHECK
```
