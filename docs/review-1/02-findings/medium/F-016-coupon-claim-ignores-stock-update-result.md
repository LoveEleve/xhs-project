# F-016 领券消费者未检查模板库存扣减影响行数，可能提交无库存的用户券

## 严重度

Medium

## 涉及文件

- `my-xhs-coupon/src/main/java/com/myxhs/coupon/consumer/CouponClaimConsumer.java:67-86`
- `my-xhs-coupon/src/main/java/com/myxhs/coupon/mapper/CouponTemplateMapper.java:16-24`

## 现象

领券消费者先插入用户券，再调用 `decrementRemainCount()` 扣减模板剩余数量，但没有检查返回的影响行数。

当 `remain_count <= 0`、模板已删除或更新条件未命中时，UPDATE 返回 0，事务仍然正常提交，用户券记录已经落库。

## 证据

1. `CouponClaimConsumer.onMessage()` 在 `:75-82` 插入用户券，重复 claim 只按 claimNo 处理。
2. `:84-85` 调用 `templateMapper.decrementRemainCount()`，忽略其返回值。
3. SQL 条件包含 `remain_count > 0 AND deleted = 0`：`CouponTemplateMapper.java:22-24`。
4. 因为方法有 `@Transactional`，没有抛异常时用户券插入与“扣减 0 行”会一起提交。

## 触发条件

1. Redis 领券状态与 MySQL 模板库存暂时不一致。
2. MQ 重放、Outbox 延迟、人工修数或 Redis 重建后，消息到达时 MySQL remain_count 已为 0。

## 影响

1. MySQL 出现用户已持有优惠券但模板剩余数量未扣减的状态。
2. 领券数量、模板库存和用户券表无法对账。
3. 后续退券可能再次增加模板库存，放大漂移。

## 修复建议

1. 检查 `decrementRemainCount()` 返回值，必须为 1，否则抛异常触发 MQ 重试或进入补偿。
2. 明确 Redis 与 MySQL 的权威关系，并为库存漂移提供可重放的 claim 事件。
3. 增加“用户券已插入但模板扣减失败”的回滚测试和对账修复。

## 是否需要补充验证

需要构造 remain_count=0 但发送领券消息的场景，确认当前是否仍提交用户券记录。