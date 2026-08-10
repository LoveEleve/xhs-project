# D13: 退款失败回调 — POST /api/order/refund-fail

## § 源码分析

- **Controller**: `OrderController.java:252` → `@PostMapping("/refund-fail")`, 参数 `@RequestParam Long orderId` + `@RequestParam String refundNo` + `X-Internal-Call`
- **鉴权**: `isInternalCall` → 403
- **逻辑**: 记录日志，不做状态变更（退款失败不影响订单）

## § 业务逻辑

记录退款失败日志→不修改订单状态（订单仍为已付/已完成）→人工处理

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Internal Token | `X-Internal-Call` | 403 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/order/refund-fail...` | 200 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | InternalToken | ✅ |

## § curl

```bash
curl -s -X POST "http://localhost:19000/api/order/refund-fail?orderId=$ORDER_ID&refundNo=RF202608" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Internal-Call: my-xhs-internal-token-2026"
```

## § ASCII流转图

```
Payment → POST /api/order/refund-fail?orderId={id}&refundNo={no}
  → isInternalCall → log.warn("[退款失败]") → 不修改订单状态
  → 人工处理
```
