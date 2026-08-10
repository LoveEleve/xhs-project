# N09: 退回优惠券 — POST /api/coupon/return

## § 源码分析

- **Controller**: `CouponController.java:144` → `@PostMapping("/return")`, 参数 `X-User-Id` + `@Valid @RequestBody ReturnCouponRequest{id}` + `X-Internal-Call`
- **Service**: `CouponService.java:340` → `returnCoupon()`
  - `@Transactional` 包裹
  - 乐观锁退券: `UPDATE t_user_coupon SET status=AVAILABLE WHERE id=? AND status=USED`
    - affected=0: 并发已退 → 抛异常
  - 回退Redis库存: `INCR myxhs:coupon:stock:{templateId}`
- **鉴权**: `isInternalCall` → 403
- **下游**: MySQL t_user_coupon + Redis stock

## § 业务逻辑

InternalToken校验 → @Transactional → 乐观锁退券(status: USED→AVAILABLE) → INCR Redis库存(+1) → 返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | Header `X-Internal-Call` | 403 |
| 券状态为USED | `SELECT status FROM t_user_coupon WHERE id=?` 须=2 | 乐观锁affected=0 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/coupon/return -d '{...}'` | 200 |
| MySQL | `SELECT status FROM t_user_coupon WHERE id=?` | 1(AVAILABLE) |
| Redis | `r.incr('myxhs:coupon:stock:{id}')` | stock+1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 并发 | 乐观锁 UPDATE WHERE status=USED | ✅ |
| 一致性 | @Transactional+INCR库存 | ✅ |
| 安全 | InternalToken校验 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19000/api/coupon/return \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"id":1}'
```

## § ASCII流转图

```
Order Feign → POST /api/coupon/return + body{id}
  → CouponController.returnCoupon(X-User-Id, request)
    → InternalToken校验 → @Transactional
    → 乐观锁: UPDATE t_user_coupon SET status=AVAILABLE WHERE id=? AND status=USED
      → affected=0 → 抛异常(并发已退)
    → INCR myxhs:coupon:stock:{templateId} (+1)
    → 返回 ok
```
