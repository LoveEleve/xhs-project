# M03: 发起退款 — POST /api/payment/refund

## § 源码分析

- **Controller**: `PaymentController.java:96` → `@PostMapping("/refund")`, 参数 `@Valid @RequestBody RefundRequest` + `X-User-Id`
- **Service**: `PaymentService.java:367` → `refund()`
  - `SELECT * FROM t_payment WHERE order_id=? AND status=SUCCESS`
  - INSERT t_refund(refund_no, payment_id, amount, status=PENDING)
  - 调用第三方退款API(当前Mock实现)
  - UPDATE t_refund status=SUCCESS/FAILED
  - MQ: REFUND_RESULT_TOPIC → Order回调
- **下游**: MySQL t_payment + t_refund + MQ

## § 业务逻辑

查原支付单→INSERT退款记录→调用第三方退款API→更新状态→MQ广播→Order回调

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 支付已成功 | `SELECT status FROM t_payment WHERE order_id=?` 须=SUCCESS | 退款失败 |
| RateLimit | 5次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/payment/refund -d '{...}'` | 200 |
| MySQL | `SELECT * FROM t_refund WHERE refund_no=?` | 1行 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | RateLimit | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19009/api/payment/refund \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"orderId":1,"amount":"100.00","reason":"用户申请退款"}'
```

## § ASCII流转图

```
curl POST /api/payment/refund + {orderId, amount, reason}
  → PaymentController.refund()
    → SELECT t_payment WHERE order_id=? AND status=SUCCESS
    → INSERT t_refund(refund_no, PENDING)
    → 第三方退款API(Mock)
    → UPDATE t_refund status=SUCCESS/FAILED
    → MQ REFUND_RESULT_TOPIC
    → Feign Order /api/order/refund-{success/fail}
```
