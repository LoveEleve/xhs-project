# N08: 使用优惠券 — POST /api/coupon/use

## § 源码分析

- **Controller**: `CouponController.java:131` → `@PostMapping("/use")`, 参数 `X-User-Id` + `@Valid @RequestBody UseCouponRequest{id, orderAmount}` + `X-Internal-Call`
- **Service**: `CouponService.java:279` → `useCoupon()`
  - 责任链校验: AmountValidator/ExpireValidator/StatusValidator
  - 计算折扣: 满减=discountValue / 折扣=orderAmount*discountValue/100
  - 乐观锁 markUsed: `UPDATE t_user_coupon SET status=USED WHERE id=? AND status=AVAILABLE`
    - affected=0: 并发在用 → 抛 CouponAlreadyUsedException
  - 不操作 Redis 库存（已在领券时 DECR）
- **鉴权**: `isInternalCall` → 403
- **下游**: MySQL t_user_coupon(status→USED)

## § 业务逻辑

InternalToken校验 → 责任链(门槛/有效期/状态) → 计算折扣 → 乐观锁 UPDATE status=USED WHERE status=AVAILABLE → affected=0抛异常(并发保护) → 返回折扣金额

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | Header `X-Internal-Call` | 403 |
| orderAmount >= minAmount | 满减券须满足门槛 | AmountValidator抛异常 |
| 券未过期 | validStart <= now <= validEnd | ExpireValidator抛异常 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/coupon/use -d '{...}'` | 200, discount |
| MySQL | `SELECT status FROM t_user_coupon WHERE id=?` | 2(USED) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken+乐观锁防并发 | ✅ |
| 正确性 | 责任链校验三关 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/coupon/use \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"id":1,"orderAmount":"100.00"}'
```

## § ASCII流转图

```
Order Feign → POST /api/coupon/use + body{id, orderAmount}
  → CouponController.useCoupon(X-User-Id, request)
    → InternalToken校验
    → AmountValidator: orderAmount >= minAmount
    → ExpireValidator: validStart <= now <= validEnd
    → StatusValidator: status == AVAILABLE
    → discount = 10.00 (满减)
    → 乐观锁: UPDATE t_user_coupon SET status=USED WHERE id=? AND status=AVAILABLE
      → affected=0 → 抛 CouponAlreadyUsedException
    → 返回 10.00
```
