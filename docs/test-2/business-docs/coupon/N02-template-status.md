# N02: 修改券模板状态 — PUT /api/coupon/template/{id}/status

## § 源码分析

- **Controller**: `CouponController.java:61` → `@PutMapping("/template/{id}/status")`, 参数 `@Positive @PathVariable Long id` + `@NotNull @RequestParam Integer status` + `X-Admin-Call`
- **Service**: `CouponService.java:128` → `updateTemplateStatus()`
  - `UPDATE t_coupon_template SET status=? WHERE id=?`
  - **关键**: 必须调用 `evictTemplateCache(id)` → `DEL myxhs:coupon:template:{id}` 清缓存
- **鉴权**: `isAdminCall` → 403
- **下游**: MySQL + Redis(DEL缓存)

## § 业务逻辑

AdminToken校验 → UPDATE t_coupon_template 状态字段 → **必须** DEL 模板缓存(否则旧状态30min不失效) → 返回 ok

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Admin Token | Header `X-Admin-Call` | 403 |
| 模板存在 | `SELECT id FROM t_coupon_template WHERE id=?` | 失败 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X PUT /api/coupon/template/1/status?status=2` | 200 |
| MySQL | `SELECT status FROM t_coupon_template WHERE id=1` | =2 |
| Redis | `r.get('myxhs:coupon:template:1')` | nil (已清缓存) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | AdminToken校验 | ✅ |
| 可维护 | 必须清缓存 evictTemplateCache | ✅ |

## § curl

```bash
curl -s -X PUT "http://localhost:19000/api/coupon/template/$TEMPLATE_ID/status?status=2" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Admin-Call: my-xhs-admin-token-2026"
```

## § ASCII流转图

```
Admin修改状态 → curl PUT /api/coupon/template/{id}/status?status=2 + X-Admin-Call
  → CouponController.updateTemplateStatus()
    → AdminToken校验 → UPDATE t_coupon_template SET status=? WHERE id=?
    → evictTemplateCache → DEL myxhs:coupon:template:{id}
    → 返回 ok
```
