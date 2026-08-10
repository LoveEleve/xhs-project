# D14: 订单应付金额 — GET /api/order/pay-amount

## § 源码分析

- **Controller**: `OrderController.java:267` → `@GetMapping("/pay-amount")`, 参数 `@RequestParam Long orderId` + `X-Internal-Call`
- **Service**: `OrderService.getOrderPayAmount(orderId)`
  - `SELECT pay_amount FROM t_order WHERE id=?`
- **鉴权**: `isInternalCall` → 403
- **用途**: 供 Payment 服务校验支付金额

## § 业务逻辑

Payment服务发起支付前调用→校验orderId对应的应付金额→在支付侧做金额一致性验证

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call` | 403 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "/api/order/pay-amount?orderId={id}"` | 200, BigDecimal |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/order/pay-amount?orderId=$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026"
```

## § ASCII流转图

```
PaymentService → GET /api/order/pay-amount?orderId={id} + X-Internal-Call
  → isInternalCall → SELECT pay_amount FROM t_order WHERE id=?
  → 返回 payAmount → Payment侧金额校验
```
