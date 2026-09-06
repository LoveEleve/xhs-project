# my-xhs-inventory 模块总览

## 1. 当前模块定位

`my-xhs-inventory` 是库存域核心服务，位于订单、支付、取消、退款链路中间。它以 Redis 分桶库存承接高并发实时扣减，以 MySQL 保存异步持久化状态，并通过 RocketMQ、Outbox、补偿任务和对账任务处理最终一致性。

## 2. 核心业务能力

- 库存初始化与分桶重建
- Redis Lua 原子预扣、确认、释放、退款回补
- 订单事务消息消费
- MySQL available/locked 库存异步落库
- TCC Try/Confirm/Cancel 库存冻结
- 预扣超时、TCC 超时、异常补偿和库存对账
- Canal 库存缓存失效
- Product Feign SKU 校验
- 热门 SKU 访问检测

## 3. 业务链路

```text
Order
  -> OrderTransactionConsumer
  -> Redis total/bucket + prededuct Hash/ZSet
  -> INVENTORY_TOPIC / Outbox
  -> InventoryDeductConsumer
  -> MySQL available_stock/locked_stock
  -> Reconcile/Compensation/Timeout Jobs
```

独立 TCC 链路：

```text
TCC Try -> freezing_stock
       -> Confirm: freezing_stock -> confirmed
       -> Cancel: freezing_stock -> available_stock
```

## 4. 当前关键事实

- Redis 分桶和 Lua 是实时库存扣减核心，MySQL 是异步持久化层
- TCC 库存账本与 Redis 分桶账本没有统一协调机制，混用时存在严重一致性风险
- PRE_DEDUCT 发送失败且回滚 Redis 时会取消对应 Outbox；Outbox 唯一键已按 order_id+sku_id+action 区分，避免动作互相覆盖
- reinit/resize 与预扣、超时补偿之间缺少统一原子屏障
- Canal UPDATE/INSERT 缓存失效当前实际跳过，代码与文档设计不一致
- 测试主要是 Mockito 路径，Lua、MQ、锁、TCC 和真实 Redis/MySQL 尚未完成集成验证

## 5. 范围

本模块非 `target` 文件共 40 个：
- 顶层文件/模块文档：3 个
- `src/main/java`：26 个
- `src/main/resources`：7 个
- `src/test/java`：4 个

AI 不属于本模块范围；鉴权只做基础管理/内部调用边界检查。
