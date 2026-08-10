# D05: 取消订单 — POST /api/order/cancel

## § 源码分析

- **Controller**: `OrderController.java:86` → `@PostMapping("/cancel")`, 参数 `X-User-Id` + `@RequestParam Long orderId`
- **Service**: `OrderService.java:350` → `cancelOrder()`
  - EventSourcing: INSERT t_order_event(CANCELLED)
  - 乐观锁: `UPDATE t_order SET status=4 WHERE id=? AND status=0`
  - 库存释放: InventoryFeign.releaseStock(pseudoOrderId)
  - 退券: CouponFeign.returnCoupon(couponId)
- **下游**: 分库MySQL t_order + Feign inventory + Feign coupon

## § 业务逻辑

EventSourcing记录事件 → 乐观锁UPDATE(仅CANCELLED from 0) → 释放库存 → 退优惠券 → 返回

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录+订单归属 | userId匹配 | 403 |
| 订单状态=0(待付) | status检查 | 无法取消 |
| RateLimit | 10次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/order/cancel?orderId={id}` | 200 |
| MySQL | `SELECT status FROM t_order WHERE id=?` | 4(CANCELLED) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 一致性 | EventSourcing+乐观锁 | ✅ |
| 幂等 | 乐观锁WHERE status=0 | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/order/cancel?orderId=$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN"
```

## § ASCII流转图

```
curl POST /api/order/cancel?orderId={id}
  → OrderController.cancelOrder(X-User-Id, orderId)
    → INSERT t_order_event(ORDER_CANCELLED)
    → UPDATE t_order SET status=4 WHERE id=? AND status=0
    → releaseStock(pseudoOrderId) → 释放库存
    → returnCoupon(couponId) → 退券
```
