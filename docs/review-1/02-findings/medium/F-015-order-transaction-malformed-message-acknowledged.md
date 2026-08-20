# F-015 订单事务消息字段异常时被确认消费，订单已提交但库存预扣被静默跳过

## 严重度

Medium

## 涉及文件

- `my-xhs-inventory/src/main/java/com/myxhs/inventory/consumer/OrderTransactionConsumer.java:63-73`
- `my-xhs-order/src/main/java/com/myxhs/order/listener/OrderTransactionListener.java:57-82`
- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderTransactionService.java:52-104`

## 现象

库存事务消息消费者遇到缺少 `orderNo` 或 `userId` 的消息时，记录日志后直接 `return`，RocketMQ 将其视为消费成功，不重试、不进 DLQ。

但订单本地事务已经提交后，库存预扣依赖这条消息完成；消息被静默确认会留下“订单已创建、库存未预扣”的状态。

## 证据

1. order 的事务本地提交同时写入订单、明细和本地消息表：`OrderTransactionService.java:52-104`。
2. inventory 消费者解析消息后，缺少 `orderNo/userId` 直接 return：`OrderTransactionConsumer.java:66-73`。
3. 该 return 不会触发 `removeMark`、异常或 RocketMQ 重试。
4. 正常库存预扣只发生在后续 SKU 循环 `OrderTransactionConsumer.java:100-124`，被前置 return 完全跳过。

## 触发条件

1. 生产端 payload 缺字段、字段类型异常、序列化格式变化，或消息被错误构造。
2. 消费者收到消息后进入字段校验分支。

## 影响

1. 订单已对外可见，但没有 Redis 预扣和库存持久层预扣。
2. 后续支付可能继续成功，形成订单/支付成功但库存从未锁定的超卖风险。
3. 因为消息已被确认，不会进入 RocketMQ 重试或 DLQ，运营侧难以发现。

## 修复建议

1. 区分“不可重试坏消息”和“业务关键字段缺失”：订单事务消息不应静默确认。
2. 缺字段时抛异常进入重试；超过次数进入 DLQ，并由人工/工具修复后重投。
3. 增加订单侧定时扫描：订单已创建但在规定窗口内缺少库存预扣记录时，生成补偿任务。
4. 消息 schema 应版本化并在生产端、消费端做严格校验。

## 残余风险

即使消息进入 DLQ，必须确认 DLQ 重投工具使用正确的原始消息 ID、保留完整 payload，并能按订单/SKU检查补偿结果。

## 是否需要补充验证

需要发送一条缺少 `userId` 的事务消息，验证当前是否被直接确认；同时验证订单已提交但库存预扣记录不存在时是否有自动补偿。