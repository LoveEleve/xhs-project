# F-010 退款成功回调无条件返回成功，掩盖订单侧失败，导致补偿链断裂

## 严重度

High

## 涉及文件

- `my-xhs-order/src/main/java/com/myxhs/order/controller/OrderController.java:239-250`
- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java:1087-1124`
- `my-xhs-payment/src/main/java/com/myxhs/payment/job/RefundNotifyCompensateJob.java:160-171`

## 现象

退款成功回调端点 `POST /api/order/refund-success` 无条件返回 `R.ok()`，无论 `onRefundSuccess()` 是否真的完成了订单状态更新、库存回补、优惠券退回。

而 `onRefundSuccess()` 在以下情况只是 `return`，不抛异常：

1. 映射表查不到 orderId（`:1089-1093`）
2. 订单不存在（`:1101-1104`）
3. 订单状态不是"已支付"（`:1105-1108`）

## 证据

1. `OrderController.notifyRefundSuccess()` 调用后无条件 `return R.ok()`：`OrderController.java:248-249`。
2. `OrderService.onRefundSuccess()` 是 `void`，失败路径只 `log` 不抛异常：`OrderService.java:1087-1108`。
3. 对照支付成功回调，`notifyPaySuccess()` 会基于 `onPaymentSuccess()` 的布尔返回返回 `R.fail`，供支付侧触发自动退款：`OrderController.java:197-203`。退款回调缺少对称的错误传导。
4. `RefundNotifyCompensateJob` 依赖 `notifyResult.isSuccess()` 判断是否补偿成功：`RefundNotifyCompensateJob.java:162-170`。由于端点永远返回成功，补偿任务会误判为"已通知成功"，删除计数 key 并停止重试。

## 触发条件

1. 订单全额退款成功，支付侧同步调用订单 `refund-success`
2. 此时映射表缺失、订单数据异常或订单状态与预期不符
3. 订单侧实际未完成状态更新，但仍返回 ok

## 影响

1. 钱已退，但订单长期停留在"已支付"状态。
2. 库存回补、优惠券退回均未执行。
3. 补偿任务因误判成功而不再重试，形成"表面上收敛、实际上分叉"的资金与库存不一致。
4. 这类问题不会进入 DLQ，也不会产生"补偿失败"告警。

## 修复建议

1. 让 `onRefundSuccess()` 返回成功/失败，失败时 `notifyRefundSuccess()` 返回 `R.fail`，与 `notifyPaySuccess` 对称。
2. 区分"已幂等处理（状态已退款）"与"处理失败需要重试"，前者可返回成功，后者必须返回失败。
3. 映射缺失、订单不存在等可重试失败应抛出异常或返回非成功，让补偿任务和 MQ 重试继续工作。
4. 补充退款成功通知的失败路径测试。

## 残余风险

即使修复返回值，仍需保证 `RefundNotifyCompensateJob` 的去重键正确写入（见 F-009），否则重试逻辑仍会反复执行或产生误报。

## 是否需要补充验证

需要模拟"退款成功通知到达时映射表缺失"或"订单状态异常"，确认订单侧返回非成功、补偿任务进入重试而不是停止。