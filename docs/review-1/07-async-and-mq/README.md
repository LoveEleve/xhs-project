# 07-async-and-mq 首轮审查记录

## 已确认

### F-015：坏消息被正常确认

`OrderTransactionConsumer` 对缺少 `orderNo/userId` 的订单事务消息直接 return，不重试、不进 DLQ。该消息对应的订单本地事务已经提交时，会造成订单存在但库存预扣未执行。

见：`02-findings/medium/F-015-order-transaction-malformed-message-acknowledged.md`

### 已复核为正常的路径

1. `OrderTransactionListener` 的本地事务把订单、订单明细、本地消息表放在同一事务中。
2. `LocalMessageRetryJob` 有指数退避、死信扫描和按消息重发机制。
3. `OrderTransactionConsumer` 对正常 payload 使用 msgId 幂等，并在失败时清除幂等标记后抛异常重试。
4. `CouponClaimConsumer` 对正常领券消息使用 msgId + claimNo 双重幂等。
5. `CounterBuffer` 使用双 Buffer、跨代写保护、重试和优雅停机刷盘，未发现新的确定性丢失点。

## 仍需审查

1. 各消费者对“格式错误/业务拒绝/依赖失败”的 return 与 throw 是否统一。
2. DLQ 重新投递后是否有业务级幂等，而不是只依赖 RocketMQ msgId。
3. 订阅同一 Topic 的多个 consumer group 是否存在事件语义不一致。
4. Outbox 标记 sent 与消息真正被消费之间的故障窗口。
5. Redis 幂等标记 TTL 是否覆盖最大补偿窗口。
