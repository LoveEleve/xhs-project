# D08: 创建支付 — POST /api/order/pay/create

## § 源码分析

- **Controller**: `OrderController.java:126` → `@PostMapping("/pay/create")`, 参数 `X-User-Id` + `@Valid @RequestBody PayRequest{orderId, payType}`
- **Service**: `MockPayService.createPayment()` (mock模式) 或 `PaymentFeignClient.pay()` (remote模式)
  - mock: 生成Payment → 成功/失败(90%/10%) → 成功→onPaymentSuccess, 失败→onPaymentFailed(取消订单)
  - remote: Feign → PaymentController(/api/payment/pay) → PaymentService
  - orderService.onPaymentFailed(): 自动取消订单+释放库存+退券
- **下游**: MockPayService/PaymentFeignClient + MySQL t_order(status变更)

## § 业务逻辑

根据pay.type选择mock/remote → 支付处理 → 成功:订单→已付+核销券+确认库存 → 失败:自动取消订单+释放库存+退券

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 已登录+订单归属 | userId+orderId | 403 |
| 订单状态=0(待付) | status检查 | 无法支付 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -X POST /api/order/pay/create -d '{"orderId":1,"payType":1}'` | 200(mock成功)或400(mock失败) |
| MySQL | `SELECT status FROM t_order WHERE id=?` | 1(PAID)或4(CANCELLED) |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 可靠性 | mock 90%成功率 | ✅ |
| 一致性 | 失败自动取消释放资源 | ✅ |

## § curl

```bash
ORDER_ID=$(curl -s http://localhost:19000/api/order/list \
  -H "Authorization: Bearer $TOKEN" | python3 -c "import json,sys;d=json.load(sys.stdin)['data'];print(d[0]['id'] if d else '')")
curl -s -X POST http://localhost:19000/api/order/pay/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"orderId\":$ORDER_ID,\"payType\":1}"
```

## § ASCII流转图

```
curl POST /api/order/pay/create + {orderId, payType}
  → OrderController.createPayment()
    → pay.type=mock: MockPayService.createPayment()
      → 90%成功 → onPaymentSuccess(order→PAID, coupon, stock)
      → 10%失败 → onPaymentFailed(取消订单, releaseStock, returnCoupon)
    → pay.type=remote: PaymentFeignClient.pay()
      → PaymentService.pay() → MQ通知
```
