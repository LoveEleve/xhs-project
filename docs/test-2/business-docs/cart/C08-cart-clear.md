# C08: 清空购物车 — DELETE /api/cart/clear

## § 源码分析

- **Controller**: `CartController.java:119` → `@DeleteMapping("/clear")`, 参数 `X-User-Id`
- **Service**: `CartService.java:524` → `clearCart()`
  - `redisTemplate.delete(itemsKey, checkedKey, sortKey)` 直接删除三个Redis Key
  - MQ CLEAR事件
- **下游**: Redis三结构 + MQ CART_TOPIC:CLEAR

## § 业务逻辑

直接删除Redis三Key → MQ异步CLEAR事件 → Consumer清空MySQL该用户的t_cart_item

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X DELETE /api/cart/clear` | 200 |
| Redis | `r.exists('myxhs:cart:{userId}:items')` | 0 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | RateLimit 3次/60秒 | ✅ |

## § curl

```bash
curl -s -X DELETE http://localhost:19000/api/cart/clear \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
curl DELETE /api/cart/clear
  → Gateway → CartController.clearCart(X-User-Id)
    → redis.delete(items, checked, sort) → 三Key同时删除
    → asyncSend CART_TOPIC:CLEAR
```
