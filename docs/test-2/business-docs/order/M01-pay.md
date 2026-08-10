# M01: 发起支付 — POST /api/payment/pay

## § 源码分析

- **Controller**: `PaymentController.java:48` → `@PostMapping("/pay")`, 参数 `@Valid @RequestBody PayCreateRequest` + `X-User-Id` + `X-Internal-Call`
- **Service**: `PaymentService.java:135` → `pay()`
  - 幂等: `SELECT * FROM t_payment WHERE order_id=?` — 已存在直接返回
  - Fetch order amount: Feign Order GET /api/order/pay-amount (金额校验)
  - INSERT t_payment(payment_no, order_id, amount, status=PENDING)
  - 调用第三方支付API(支付宝/微信, 当前Mock实现)
  - 处理结果: status=SUCCESS/FAILED
  - MQ: PAY_RESULT_TOPIC (支付结果广播)
- **鉴权**: `isInternalCall` → 403 "支付请通过订单服务发起"
- **下游**: MySQL t_payment + Feign Order + MQ PAY_RESULT_TOPIC

## § 业务逻辑

InternalToken+幂等→Feign Order校验金额→INSERT Payment记录→调用支付API→更新状态→MQ广播结果

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call` | 403 |
| 订单未重复支付 | `SELECT * FROM t_payment WHERE order_id=?` | 幂等返回已有Payment |
| RateLimit | 10次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/payment/pay -d '{...}'` | 200, PaymentVO |
| MySQL | `SELECT * FROM t_payment WHERE order_id=?` | 1行, status |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken+金额校验 | ✅ |
| 幂等 | 已存在支付单返回 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19009/api/payment/pay \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"orderId":1,"amount":"100.00","payType":1}' | python3 -m json.tool
```

## § ASCII流转图

```
Order Feign → POST /api/payment/pay + {orderId, amount, payType}
  → PaymentController.pay()
    → isInternalCall
    → 幂等: SELECT t_payment WHERE order_id=?
    → Feign Order GET /api/order/pay-amount → 金额校验
    → INSERT t_payment(payment_no, PENDING)
    → 第三方支付API(支付宝/微信, Mock)
    → UPDATE status=SUCCESS/FAILED
    → MQ PAY_RESULT_TOPIC → Order回调
```
