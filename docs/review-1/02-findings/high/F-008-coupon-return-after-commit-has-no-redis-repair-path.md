# F-008 退券在数据库提交后更新 Redis，失败时没有补偿路径

## 严重度

High

## 涉及文件

- `my-xhs-coupon/src/main/java/com/myxhs/coupon/service/CouponService.java:371-409`
- `my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java:551-572`

## 现象

退券流程先在 MySQL 事务中把用户券恢复为未使用、把模板剩余数量加一，然后通过 `afterCommit()` 更新 Redis 库存和用户领取计数。

数据库提交成功后，Redis 更新失败不会回滚数据库，也没有写入退券补偿记录或可靠重试事件。

## 证据

1. `CouponService.returnCoupon()` 使用 `@Transactional`，在 `:379-390` 更新用户券和模板库存。
2. Redis 操作被注册到 `TransactionSynchronization.afterCommit()`：`:397-407`。
3. `afterCommit()` 内直接执行 Lua 脚本并记录日志，没有 try/catch、重试、Outbox 或 compensation 写入：`:400-406`。
4. order 侧 `returnCouponIfUsed()` 在 Feign 返回成功后认为退券完成：`OrderService.java:560-567`；它无法知道 afterCommit 的 Redis 更新是否成功。

## 触发条件

1. MySQL 事务成功提交
2. Redis 短暂不可用、主从切换、脚本失败或 key 数据异常
3. afterCommit Redis 更新失败

## 影响

1. MySQL 显示用户券已恢复、模板剩余量已增加。
2. Redis 仍显示库存未恢复、用户领取次数未减少。
3. 后续领券可能出现“数据库可领、Redis 不可领”或反向超发。
4. order 不会再次发送退券补偿，因为同步调用已经返回成功。

## 修复建议

1. 把 Redis 侧退券动作写入可靠 outbox，afterCommit 只负责触发，不负责承担唯一执行机会。
2. 或在 afterCommit 失败时记录 `coupon return compensation`，由 Job 重试并按 userCouponId/orderId 幂等。
3. 统一 MySQL/Redis 的权威关系，补充对账任务能识别“券状态已恢复但 Redis 未恢复”的差异。
4. 不要仅依赖日志表示 afterCommit 成功；需要可查询、可重试的状态。

## 是否需要补充验证

需要注入 Redis 失败，确认 MySQL 已提交后是否存在可发现、可重试的退券记录。目前从代码看没有。
