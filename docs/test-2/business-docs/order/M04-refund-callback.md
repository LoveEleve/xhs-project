# M04: 退款回调 — POST /api/payment/refund-callback/{payType}

## § 源码分析

- **Controller**: `PaymentController.java:108` → `@PostMapping("/refund-callback/{payType}")`, 参数 `@PathVariable Integer payType` + `@RequestBody String callbackData` + `X-Internal-Call`
- **Service**: `PaymentService.handleRefundCallback(refundNo, success)`
  - 解析退款回调: extractRefundNo/RefundResult
  - `UPDATE t_refund SET status=? WHERE refund_no=?`
  - MQ: REFUND_RESULT_TOPIC → Order回调
  - 返回 "success"
- **鉴权**: `isInternalCall` → 返回 "fail"

## § 业务逻辑

第三方回调→解析退款结果→UPDATE t_refund→MQ广播→Order回调refund-success/refund-fail→返回"success"

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call` | 返回"fail" |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/payment/refund-callback/1 -d '{...}'` | "success" |
| MySQL | `SELECT status FROM t_refund WHERE refund_no=?` | SUCCESS |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken | ✅ |

## § curl

```bash
curl -s -X POST http://localhost:19009/api/payment/refund-callback/1 \
  -H "X-Internal-Call: my-xhs-internal-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"refund_no":"REFUND_202608","status":"success"}'
```

## § ASCII流转图

```
第三方 → POST /api/payment/refund-callback/{payType}
  → isInternalCall → extractRefundNo/Result
  → UPDATE t_refund SET status=? WHERE refund_no=?
  → MQ REFUND_RESULT_TOPIC
  → return "success"
```
