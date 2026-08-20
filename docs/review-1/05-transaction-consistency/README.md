# 05-transaction-consistency

## 目标

审查订单、支付、库存、购物车、优惠券等交易链路是否真正收敛。

## 重点服务

- `my-xhs-order`
- `my-xhs-payment`
- `my-xhs-inventory`
- `my-xhs-cart`
- `my-xhs-coupon`

## 重点问题

1. 订单状态机是否允许非法跳转
2. 支付回调、退款、补偿是否存在重放风险
3. 库存预扣、释放、确认是否有超卖或悬挂库存
4. cart/coupon 是否在并发与重试下破坏业务约束
5. outbox、事件表、本地事务是否真正绑定
6. 分库分表下的读写、查询、补偿是否会错路由

## 预期证据

- 状态机实现
- 消息与本地事务绑定点
- 幂等 key 与唯一约束
- 分片路由规则
- 回调与补偿逻辑
- 对账任务与 repair 逻辑

## 初步产出建议

- `order-state-machine.md`
- `payment-callbacks.md`
- `inventory-reservation.md`
- `idempotency-and-replay.md`
- `sharding-risk.md`