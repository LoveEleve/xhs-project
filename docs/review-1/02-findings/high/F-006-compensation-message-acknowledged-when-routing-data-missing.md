# F-006 补偿消息在路由映射缺失时被正常确认，失败不会重试

## 严重度

High

## 涉及文件

- `my-xhs-order/src/main/java/com/myxhs/order/consumer/OrderCompensationConsumer.java:92-107`
- `my-xhs-order/src/main/java/com/myxhs/order/consumer/OrderCompensationConsumer.java:128-131`

## 现象

订单补偿消费者无法通过消息属性获取 `userId` 时，会从 `OrderNoMapping` 反查。映射不存在时方法直接 `return`，没有抛异常，因此 RocketMQ 会把消息视为消费成功，不再重试。

## 触发条件

1. 订单取消/释放库存/退券失败，补偿消息已经发出
2. 消费时 `userId` 消息属性缺失或无效
3. `OrderNoMapping` 尚未写入、查询暂时失败、数据损坏或映射被清理

## 证据

- 消费者先从消息属性读取 userId：`OrderCompensationConsumer.java:92-100`。
- 取不到时查映射表：`:101-107`。
- 映射为空时只记录错误并 `return`：`:102-105`。
- 只有抛出异常才会进入 `:128-131` 的 RocketMQ 重试路径。

## 影响

1. 释放库存或退还优惠券的补偿任务会静默丢失。
2. 订单状态可能已经取消，但库存/优惠券长期不收敛。
3. 这类问题不会进入 DLQ，也不会触发运营告警中的“重试失败”指标。

## 修复建议

1. 对映射缺失、临时查询异常统一抛出可重试异常，不要正常 return。
2. 区分永久坏消息与暂时路由数据未就绪：格式错误可进入人工队列，映射暂缺应重试。
3. 补偿消息应直接携带可信且不可变的 `userId`，同时保留 mapping 作为校验，不要把关键路由信息完全寄托在二次查询上。
4. 为补偿消息增加业务幂等 ID，避免只依赖 RocketMQ msgId。

## 是否需要补充验证

需要模拟“补偿消息先到、mapping 后写入”的顺序，确认第一次消费失败会重试，mapping 出现后能够完成释放/退券。