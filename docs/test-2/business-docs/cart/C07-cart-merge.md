# C07: 合并购物车 — POST /api/cart/merge

## § 源码分析

- **Controller**: `CartController.java:106` → `@PostMapping("/merge")`, 参数 `X-User-Id` + `@Valid @RequestBody CartMergeRequest`
- **Service**: `CartService.java:455` → `mergeAnonymousCart()`
  - `cart_merge_item.lua`: 遍历匿名商品 → 已有取max(quantity)→无则HINCRBY→默认选中→ZADD
  - 幂等: 取max防止重复合并翻倍
  - 已存在商品quantity>=10000 发UPDATE事件
- **下游**: Redis三结构 + MQ CART_TOPIC:ADD/UPDATE

## § 业务逻辑

登录后合并匿名购物车 → Lua遍历→已有取max（幂等）、无则新增 → 默认选中 → MQ事件 → 返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |
| 有匿名商品 | 请求体包含 items[{skuId,quantity}] | 空请求NOP |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/cart/merge -d '{...}'` | 200 |
| Redis | `r.hget('myxhs:cart:{userId}:items','{skuId}')` | 合并后quantity |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | Lua取max幂等 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/cart/merge \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"items":[{"skuId":2085989641275572226,"quantity":2}]}'
```

## § ASCII流转图

```
curl POST /api/cart/merge + body{items[{skuId,quantity}]}
  → Gateway → CartController.mergeAnonymousCart()
    → cart_merge_item.lua: for each item
      → HEXISTS? → HGET取max / HINCRBY新增
      → SADD checked + ZADD NX sort
    → asyncSend CART_TOPIC:ADD/UPDATE
```
