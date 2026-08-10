# C10: 手动对账 — POST /api/cart/internal/reconcile

## § 源码分析

- **Controller**: `CartController.java:158` → `@PostMapping("/internal/reconcile")`, 需 `X-Admin-Call` 校验
- **Service**: `CartReconcileJob.java` → `reconcile()` / `reconcileUser()`
  - 全量对账: MySQL游标分页扫描t_cart_item → 对比Redis三结构 → 三场景修复
  - 单用户对账: `reconcileUser(userId)` — 手动修复Redis-only用户
- **对账三场景**: Redis有MySQL无→INSERT / 数量不一致→UPDATE / Redis无MySQL有→DELETE(itemsKey不存在则跳过)
- **下游**: Redis + MySQL

## § 业务逻辑

管理接口 → Admin Token校验 → 异步执行对账 → Redis为准修复MySQL → 返回任务已触发

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | Header `X-Admin-Call: my-xhs-admin-token-2026` | 403 |
| RateLimit | 2次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/cart/internal/reconcile -H "X-Admin-Call: ..."` | 200, "对账任务已异步触发" |
| Redis | `r.hlen('myxhs:cart:{userId}:items')` = | MySQL `SELECT COUNT(*) FROM t_cart_item WHERE user_id=?` |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | Admin Token + RateLimit | ✅ |
| 并发 | 专用单线程池 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/cart/internal/reconcile \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"

# 单用户对账
curl -s -X POST "http://localhost:19000/api/cart/internal/reconcile/user?userId=2085982901507301378" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"
```

## § ASCII流转图

```
curl POST /api/cart/internal/reconcile + X-Admin-Call
  → Gateway → CartController.reconcile()
    → AdminToken校验(403 if wrong)
    → CompletableFuture.runAsync(专用线程池)
      → CartReconcileJob.reconcile()
        → MySQL游标分页扫描 t_cart_item
        → 对比Redis三结构
        → Redis为准修复MySQL (INSERT/UPDATE/DELETE)
    → 返回 "对账任务已异步触发"
```
