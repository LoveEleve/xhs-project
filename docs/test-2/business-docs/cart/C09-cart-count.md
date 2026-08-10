# C09: 购物车数量 — GET /api/cart/count

## § 源码分析

- **Controller**: `CartController.java:129` → `@GetMapping("/count")`, 参数 `X-User-Id`
- **Service**: `CartService.java:509` → `getCartCount()`
  - `redisTemplate.opsForHash().size(itemsKey)` → HLEN
- **下游**: Redis Hash

## § 业务逻辑

HLEN 获取购物车商品种类数量 → 返回 {count: N} (角标展示用)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/cart/count` | 200, {"data":{"count":N}} |
| Redis | `r.hlen('myxhs:cart:{userId}:items')` | = count值 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | HLEN O(1) | ✅ |
| 安全 | RateLimit 120次/60秒(高频率角标接口) | ✅ |

## § curl

```bash
curl -s http://localhost:19000/api/cart/count \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
curl GET /api/cart/count
  → Gateway → CartController.getCartCount(X-User-Id)
    → HLEN myxhs:cart:{userId}:items
    → 返回 {count: N}
```
