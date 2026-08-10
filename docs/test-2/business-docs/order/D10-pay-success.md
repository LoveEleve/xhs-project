# D10: 支付成功回调 — POST /api/order/pay-success

## § 源码分析

- **Controller**: `OrderController.java:187` → `@PostMapping("/pay-success")`, 参数 `@RequestParam Long orderId` + `@RequestParam String tradeNo` + `X-Internal-Call`
- **Service**: `OrderService.onPaymentSuccess(orderId, null)`
  - EventSourcing: INSERT t_order_event(ORDER_PAID)
  - 乐观锁: `UPDATE t_order SET status=1 WHERE id=? AND status=0`
  - 核销券: CouponFeign.useCoupon() 内部接口
  - 库存确认: InventoryFeign.confirmStock(pseudoOrderId)
- **鉴权**: `isInternalCall(X-Internal-Call)` → 403
- **下游**: MySQL t_order + Feign coupon + Feign inventory

## § 业务逻辑

InternalToken校验→EventSourcing→乐观锁status:0→1→核销优惠券→确认库存→返回ok

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call` | 403 |
| 订单已付状态 | 乐观锁WHERE status=0 | 重复支付(幂等) |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| MySQL | `SELECT status FROM t_order WHERE id=?` | 1(PAID) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken | ✅ |
| 幂等 | 乐观锁 | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/order/pay-success?orderId=$ORDER_ID&tradeNo=TRADE_202608" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026"
```

## § ASCII流转图

```
Payment Service → POST /api/order/pay-success?orderId={id}&tradeNo={no}
  → OrderController.notifyPaySuccess(orderId, tradeNo)
    → isInternalCall → EventSourcing(ORDER_PAID)
    → UPDATE t_order SET status=1 WHERE id=? AND status=0
    → useCoupon(couponId) → 核销券
    → confirmStock(pseudoOrderId) → 确认库存
```
