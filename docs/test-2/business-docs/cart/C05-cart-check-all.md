# C05: 全选 — PUT /api/cart/check-all?checked=true

## § 源码分析

- **Controller**: `CartController.java:84` → `@PutMapping("/check-all")`, 参数 `X-User-Id` + `@RequestParam boolean checked`
- **Service**: `CartService.java:255` → `checkAll()`
  - checked=true: `cart_check_all.lua` → HKEYS获取所有skuId → DEL checked → SADD全部 → 原子重建
  - checked=false: 直接 DEL checked
  - MQ CHECK_ALL事件
- **下游**: Redis Set + MQ CART_TOPIC:CHECK_ALL

## § 业务逻辑

checked=true → Lua HKEYS+DEL+SADD原子重建选中列表 / checked=false → DEL checked清空 → MQ异步事件

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录 | `cat /tmp/test_token.txt` | 401 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X PUT "/api/cart/check-all?checked=true"` | 200 |
| Redis | `r.scard('myxhs:cart:{userId}:checked')` | >0 (checked=true) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | Lua HKEYS+DEL+SADD原子重建 | ✅ |

## § curl

```bash
curl -s -X PUT "http://localhost:19000/api/cart/check-all?checked=true" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
curl PUT /api/cart/check-all?checked=true
  → Gateway → CartController.checkAll(X-User-Id, checked=true)
    → cart_check_all.lua: HKEYS → DEL checked → SADD all (原子)
    → asyncSend CART_TOPIC:CHECK_ALL
```
