# F-011 支付金额未对订单真实金额二次校验，且 getOrderPayAmount 语义与注释不符

## 严重度

Medium

## 涉及文件

- `my-xhs-payment/src/main/java/com/myxhs/payment/service/PaymentService.java:147-243`
- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java:1190-1204`

## 现象

1. `PaymentService.pay()` 创建支付单时，直接使用调用方传入的 `request.getAmount()`，从未通过订单服务的真实支付金额做二次校验。
2. `OrderService.getOrderPayAmount()` 的注释声称"返回非空金额 → 订单仍为待支付；返回 data 为 null → 订单已不是待支付"，但实现对所有存在的订单都返回 `payAmount`，与注释不符。

## 证据

1. `PaymentService.pay()` 只在 `:178-189` 通过 `getOrderStatus` 校验订单是否待付款，未校验金额：`PaymentService.java:178-189`。
2. 支付金额直接来自请求：`PaymentService.java:150`、`:203`、`:232`。
3. `getOrderPayAmount` 实现无条件返回 `order.getPayAmount()`，不判断订单状态：`OrderService.java:1190-1204`。
4. 全代码库中 `getOrderPayAmount` 只在 `RefundNotifyCompensateJob` 与 `PaymentNotifyCompensateJob` 中被调用，`pay()` 并未使用：`rg "getOrderPayAmount"` 结果。
5. 补偿任务依赖注释声称的状态语义判断"订单是否仍为待支付"：`RefundNotifyCompensateJob.java:147-155`，实际会误判。

## 触发条件

1. 持有内部调用令牌的调用方（或内部服务异常）传入与订单真实金额不一致的 `amount`
2. `getOrderPayAmount` 被补偿任务用于判断订单状态时，遇到非待支付订单

## 影响

1. 支付金额缺少服务端权威校验，依赖内部信任边界；一旦内部令牌边界被突破（见 F-002/F-003），可直接造成金额错误。
2. `getOrderPayAmount` 的语义偏差会让退款/支付补偿任务把"已支付订单"误判为"待支付订单"，产生错误告警与错误分支。
3. 注释与实际实现的不一致会持续误导后续维护者。

## 修复建议

1. 在 `pay()` 中增加对订单真实 `payAmount` 的二次校验，金额不一致则拒绝支付。
2. 修正 `getOrderPayAmount`：要么按注释语义只对"待支付"订单返回金额，要么修改注释并让补偿任务改用 `getOrderStatus` 明确判断状态。
3. 对内部调用也采用"最小信任"，关键资金字段应由权威服务侧重新确认，而不是完全相信调用方。

## 残余风险

支付金额校验属于防御纵深，主风险仍在于内部令牌边界（F-002/F-003）与回调语义丢失（F-010）。

## 是否需要补充验证

需要确认 `pay()` 是否存在任何金额校验分支，以及 `getOrderPayAmount` 在已支付/已退款订单上返回非空金额这一行为是否会触发补偿任务的错误告警。