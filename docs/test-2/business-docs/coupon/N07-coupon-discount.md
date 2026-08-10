# N07: 查询折扣 — GET /api/coupon/discount/{id}

## § 源码分析

- **Controller**: `CouponController.java:118` → `@GetMapping("/discount/{id}")`, 参数 `X-User-Id` + `@RequestParam BigDecimal orderAmount` + `X-Internal-Call`
- **Service**: `CouponService.java:248` → `getCouponDiscount()`
  - 责任链校验: AmountValidator(满减门槛) + ExpireValidator(有效期) + StatusValidator(可用状态)
  - **不核销** — 仅计算折扣金额，不 UPDATE status
  - 满减: discount = discountValue (固定金额)
  - 折扣: discount = orderAmount * discountValue / 100.0 (百分比)
- **鉴权**: `isInternalCall(X-Internal-Call)` → 403
- **下游**: MySQL t_user_coupon + t_coupon_template

## § 业务逻辑

InternalToken校验 → 查券信息 → 责任链:满减门槛/有效期/状态 → 计算折扣(满减固定/折扣百分比) → 返回折扣金额(不核销)

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | Header `X-Internal-Call` | 403 |
| 券属于该用户 | SELECT ... WHERE id=? AND user_id=? | 券不存在/不属于用户 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/coupon/discount/{id}?orderAmount=100` | 200, 折扣金额 |
| MySQL | `SELECT status FROM t_user_coupon WHERE id=?` | 未变(不核销) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken+不核销 | ✅ |
| 正确性 | 满减=discountValue, 折扣=orderAmount*discount/100 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/coupon/directly/$COUPON_ID?orderAmount=100.00" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026"
```

## § ASCII流转图

```
Order Feign → GET /api/coupon/discount/{id}?orderAmount=100
  → CouponController.getCouponDiscount(id, orderAmount)
    → InternalToken校验
    → 查券信息 + 模板
    → AmountValidator: 100 >= 50(满减)? ✅
    → ExpireValidator: now <= validEnd? ✅
    → StatusValidator: status == AVAILABLE? ✅
    → discount = 10.00 (满减)
    → 返回 10.00 (不UPDATE status)
```
