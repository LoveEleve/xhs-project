# M05: 支付状态查询 — GET /api/payment/status/{orderId}

## § 源码分析

- **Controller**: `PaymentController.java:128` → `@GetMapping("/status/{orderId}")`, 参数 `@PathVariable Long orderId` + optional `X-User-Id`
- **Service**: `PaymentService.java:758` → `getPaymentStatus()`
  - `SELECT * FROM t_payment WHERE order_id=?`
  - 关联退款信息: LEFT JOIN t_refund WHERE payment_id=?
  - 返回 PaymentVO(status, amount, tradeNo, refundInfo)
- **用途**: 主要为 order 服务内部 Feign 调用(getPaymentStatus for remote mode pay.status endpoint)，用户面走 /api/order/pay/status/{orderId}(有归属校验)

## § 业务逻辑

按orderId查支付单→关联退款信息→返回完整Payment状态

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 支付单存在 | `SELECT * FROM t_payment WHERE order_id=?` | 返回null |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/payment/status/{orderId}` | 200, status/amount/tradeNo |
| MySQL | `SELECT status FROM t_payment WHERE order_id=?` | = 返回status |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 单表查询+索引 | ✅ |

## § curl

```bash
curl -s "http://localhost:19009/api/payment/status/$ORDER_ID" \
  -H "X-User-Id: 123456"
```

## § ASCII流转图

```
Order Feign → GET /api/payment/status/{orderId}
  → PaymentController.getPaymentStatus(orderId)
    → SELECT * FROM t_payment WHERE order_id=?
    → LEFT JOIN t_refund ON payment_id=?
    → 返回 PaymentVO{status, amount, tradeNo}
```
