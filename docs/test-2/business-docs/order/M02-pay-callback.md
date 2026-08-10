# M02: 支付回调 — POST /api/payment/callback/{payType}

## § 源码分析

- **Controller**: `PaymentController.java:72` → `@PostMapping("/callback/{payType}")`, 参数 `@PathVariable Integer payType` + `@RequestBody String callbackData` + `X-Internal-Call`
- **Service**: `PaymentService.java:264` → `handlePayCallback()`
  - 解析回调数据: extractPaymentNo/TradeNo/PayResult
  - `SELECT * FROM t_payment WHERE payment_no=?`
  - `UPDATE t_payment SET status=SUCCESS/FAILED WHERE payment_no=?`
  - MQ: PAY_RESULT_TOPIC (支付结果通知Order服务)
  - 返回 "success" (支付宝/微信要求)
- **鉴权**: `isInternalCall` → 返回 "fail" (生产环境应为第三方签名验签)

## § 业务逻辑

第三方支付宝/微信服务器POST通知→解析回调数据(paymentNo/tradeNo/payResult)→UPDATE Payment状态→MQ广播→Order回调→返回"success"

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call` | 返回"fail" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/payment/callback/1 -d '{...}'` | 返回 "success" |
| MySQL | `SELECT status FROM t_payment WHERE payment_no=?` | SUCCESS/FAILED |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken(生产=验签) | ✅ |
| 幂等 | 重复回调不重复处理 | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19009/api/payment/callback/1 \
  -H "X-Internal-Call: my-xhs-internal-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"out_trade_no":"PAY_202608","trade_no":"TRADE_202608","status":"success"}'
```

## § ASCII流转图

```
支付宝/微信 → POST /api/payment/callback/{payType}
  → PaymentController.payCallback(payType, callbackData)
    → isInternalCall(生产:第三方签名验签)
    → extractPaymentNo/TradeNo/PayResult
    → UPDATE t_payment SET status=? WHERE payment_no=?
    → MQ PAY_RESULT_TOPIC
    → return "success"
```
