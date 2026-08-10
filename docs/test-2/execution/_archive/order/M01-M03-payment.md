# M01 → M03 支付+退款全链路

> 🔴 本次会话最复杂的修复——5 处联动，耗时 ~1 小时

## ASCII 流转图

```
D01(Gateway) → order:19011 → 创建订单 29.90
  ↓
D08(remote, pay.type=remote)
  → order:19011 → PaymentFeignClient(InternalCallFeignConfig→注入X-Internal-Call)
    → Feign call → payment:19012 → POST /api/payment/pay(isInternalCall校验)
      → MySQL:13308 my_xhs_payment.t_payment INSERT (status=0 待支付)
      → 返回 paymentId=2085643065713782785, paymentNo=PAY20260807000004
  ↓
MySQL 手动设 status=1 (模拟支付完成)
  ↓
M03(POST /api/payment/refund)
  → payment:19012 → PaymentService.refund()
    → 校验 paymentId 存在 + status=1
    → 创建退款记录 → 200
```

## 业务逻辑

D08 `pay.type=remote` 模式下，order 服务通过 Feign 调用 payment 服务创建真实支付记录（而非 mock 模式直接在 order 内改状态）。创建成功后手动设支付状态为"已支付"（模拟真实支付回调），然后通过 M03 退款。

## 根因

`PaymentFeignClient` 是 order 服务唯一缺 `InternalCallFeignConfig` 的 Feign 客户端——`InventoryFeignClient`、`CouponFeignClient`、`ProductFeignClient` 都有 `configuration = InternalCallFeignConfig.class`，唯独 `PaymentFeignClient` 没有。

结果：D08 remote 模式的 Feign 调用不带 `X-Internal-Call` 头，payment 服务 `isInternalCall()` 永远 false → 403 "支付请通过订单服务发起"。

## 5 处联动修复

| # | 文件 | 行 | 修改 |
|:--:|------|:--:|------|
| 1 | `PaymentController.java` | 55 | `/pay` 端点 `isAdminCall(adminCall)` → `isInternalCall(internalCall)` |
| 2 | `PaymentFeignClient.java` | 19 | 加 `configuration = InternalCallFeignConfig.class` |
| 3 | `InternalCallFeignConfig.java` | 18 | `${myxhs.internal.token:}` → `my-xhs-internal-token-2026` |
| 4 | `OrderController.java` | 131 | D08 remote 补 `payRequest.setAmount(order.getPayAmount())` |
| 5 | Order `application.yml` | 131 | `pay.type: mock` → `pay.type: remote` |

## curl（M01 创建支付，直调 payment 绕过 Gateway）

```bash
TOKEN=$(cat /tmp/test_token.txt)

# M01 创建支付（直调，InternalCall）
curl -s -X POST "http://localhost:19012/api/payment/pay" \
  -H "X-User-Id: 10001" \
  -H "X-Internal-Call: my-xhs-internal-token-2026" \
  -H "Content-Type: application/json" \
  -d '{"orderId":2085632792714117122,"payType":1,"amount":29.90}'

# 返回 {"code":200,"data":{"id":2085634666351599617,"paymentNo":"PAY20260807000001",...}}
```

## curl（D08 remote，走 Gateway 完整链路）

```bash
# D01 创建
OID=$(curl -s -X POST "http://localhost:19000/api/order/create" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d '{"skuItems":[{"skuId":2085530413171785729,"quantity":1}],"addressId":1,"bizIdentifier":"test"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['orderId'])")

# D08 支付(remote→Feign→payment)
curl -s -X POST "http://localhost:19000/api/order/pay/create" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 10001" \
  -d "{\"orderId\":$OID,\"payType\":1}"
# → {"code":200,"data":{"paymentNo":"PAY20260807000004","amount":29.9,...}}
```

## curl（M03 退款）

```bash
PID=2085643065713782785

# 设 paid（模拟支付回调）
mysql -h21.130.247.89 -P13308 -uroot -p'Xhs@2026#MySQL' my_xhs_payment \
  -e "UPDATE t_payment SET status=1,paid_at=NOW() WHERE id=$PID"

# 退款
curl -s -X POST "http://localhost:19012/api/payment/refund" \
  -H "X-User-Id: 10001" \
  -H "X-Internal-Call: my-xhs-internal-token-2026" \
  -H "Content-Type: application/json" \
  -d "{\"paymentId\":$PID,\"refundAmount\":29.90,\"reason\":\"refund test\"}"
# → {"code":200,"message":"操作成功"}
```

## 验证结果

| 层 | 预期 | 实际 | 状态 |
|------|------|------|:--:|
| HTTP | D08 200 + M03 200 | 200 ✅ | PASS |
| MySQL | my_xhs_payment.t_payment INSERT + UPDATE | 记录确认 | PASS |
| MQ | Feign 调用（非 RocketMQ） | 内部链路 | PASS |
| Feign | X-Internal-Call 注入成功 | 不再 403 | PASS |

## 踩坑

| 现象 | 根因 | 解决 |
|------|------|------|
| M03 40002 "退款金额不能为空" | RefundRequest 字段名是 `refundAmount` 非 `amount` | 修正 curl body |
| M03 30009 "支付单状态不允许退款" | payment status=0(待支付)，退款要求 status=1 | MySQL UPDATE status=1 |
| M01 40002 "支付金额不能为空" | PayCreateRequest 有 amount 字段但 OrderController 未设 | 补 setAmount() |
| D08 反复 403 | `INTERNAL_TOKEN:` 空默认导致 isInternalCall 永远 false | 第 3 处修复 |
| YAML DuplicateKeyException | `spring.cloud` 和 `pay:` 重复键 | 合并到现有块 |
