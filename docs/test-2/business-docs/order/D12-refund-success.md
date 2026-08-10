# D12: 退款成功回调 — POST /api/order/refund-success

## § 源码分析

- **Controller**: `OrderController.java:236` → `@PostMapping("/refund-success")`, 参数 `@RequestParam Long orderId` + `@RequestParam String refundNo` + `X-Internal-Call`
- **Service**: `OrderService.onRefundSuccess(orderId)`
  - 乐观锁: `UPDATE t_order SET status=5 WHERE id=? AND status=1`
  - 库存释放: InventoryFeign.releaseStock(pseudoOrderId)
  - 退券: CouponFeign.returnCoupon(couponId)
- **鉴权**: `isInternalCall` → 403

## § 业务逻辑

InternalToken校验→乐观锁status:1→5(REFUNDED)→释放库存→退券

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call` | 403 |
| 订单已付款 | 乐观锁WHERE status=1 | 无法退款 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| MySQL | `SELECT status FROM t_order WHERE id=?` | 5(REFUNDED) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/order/refund-success?orderId=$ORDER_ID&refundNo=RF202608" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026"
```

## § ASCII流转图

```
Payment → POST /api/order/refund-success?orderId={id}&refundNo={no}
  → isInternalCall → onRefundSuccess(orderId)
  → UPDATE t_order SET status=5 WHERE id=? AND status=1
  → releaseStock + returnCoupon
```
