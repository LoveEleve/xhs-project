# D09: 支付状态 — GET /api/order/pay/status/{orderId}

## § 源码分析

- **Controller**: `OrderController.java:157` → `@GetMapping("/pay/status/{orderId}")`, 参数 `X-User-Id` + `@PathVariable Long orderId`
- **Service**: 订单归属校验 `isOrderOwner(userId, orderId)` → 403
  - mock模式: `MockPayService.getPaymentByOrderId(orderId)` 查本地Payment
  - remote模式: `paymentFeignClient.getPaymentStatus(orderId)` Feign调用
- **下游**: MySQL t_payment(mock) / PaymentFeign(remote)

## § 业务逻辑

校验订单归属→查本地Payment(mock): status(0待付/1成功/2失败)/remote: Feign Payment服务

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录+订单归属 | `isOrderOwner` | 403 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl /api/order/pay/status/{orderId}` | 200, Payment信息 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | 订单归属校验 | ✅ |

## § curl

```bash
curl -s "http://localhost:19000/api/order/pay/status/$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图

```
curl GET /api/order/pay/status/{orderId}
  → OrderController.getPaymentStatus(orderId, X-User-Id)
    → isOrderOwner(userId, orderId)? → 403 if not
    → mock: MockPayService.getPaymentByOrderId(orderId)
    → remote: paymentFeignClient.getPaymentStatus(orderId)
```
