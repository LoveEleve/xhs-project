# D11: 支付失败回调 — POST /api/order/pay-fail

## § 源码分析

- **Controller**: `OrderController.java:210` → `@PostMapping("/pay-fail")`, 参数 `@RequestParam Long orderId` + `X-Internal-Call`
- **Service**: `OrderService.onPaymentFailed(orderId)`
  - 乐观锁取消: `UPDATE t_order SET status=4 WHERE id=? AND status=0`
  - 库存释放: InventoryFeign.releaseStock(pseudoOrderId)
  - 退券: CouponFeign.returnCoupon(couponId)
- **鉴权**: `isInternalCall` → 403
- **下游**: MySQL t_order + Feign inventory + Feign coupon

## § 业务逻辑

支付失败→自动取消订单→释放预扣库存→退还优惠券→返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call` | 403 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| MySQL | `SELECT status FROM t_order WHERE id=?` | 4(CANCELLED) |
| Feign | 库存释放 InventoryFeign | stock+1 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken | ✅ |
| 一致性 | 释放库存+退券 | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/order/pay-fail?orderId=$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026"
```

## § ASCII流转图

```
Payment → POST /api/order/pay-fail?orderId={id}
  → isInternalCall → onPaymentFailed(orderId)
  → UPDATE t_order SET status=4 WHERE id=? AND status=0
  → releaseStock + returnCoupon
```
