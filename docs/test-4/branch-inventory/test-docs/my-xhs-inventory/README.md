# my-xhs-inventory 测试重点

## 当前状态

- inventory 非 `target` 文件基线：40 个
- 构成：顶层/模块文档 3、主源码 26、resources 7、tests 4
- inventory 测试已执行：15 个通过；common 53 个通过
- inventory 已重新打包并重启，19009 健康检查 `UP`
- AI 不属于本模块范围；鉴权只做基础管理/内部调用边界检查

## L1 业务

- 库存初始化、重复初始化、reinit、扩容
- Redis 分桶预扣、确认、释放、退款回补
- Order 事务消息按 SKU 扣减
- TCC Try/Confirm/Cancel
- 预扣超时、TCC 超时、异常补偿和库存对账

## L2 数据

- Redis total、bucket、bucket-count、prededuct Hash/ZSet
- 4 个 Lua 的返回值、分桶路由、原子性和幂等
- MySQL available/locked/freezing stock、Fence、Outbox、compensation
- RocketMQ topic、tag、重试/DLQ 和消息幂等
- Canal 库存缓存失效和 Product Feign 契约

## L3 质量

- PRE_DEDUCT MQ 失败后的 Redis/Outbox 回滚
- TCC 与普通预扣混用
- reinit/resize 与预扣/释放/超时并发
- 同毫秒事件、乱序和版本跳过
- Outbox claim/租约、补偿最终状态和锁续租
- Redis 故障、主从切换、Product 不可用、MySQL 更新失败
- 分桶超卖、重复消费、多 SKU 部分成功

## L4 可观测性

- Outbox 积压、补偿失败、TCC Cancel、版本跳过指标
- Redis 重建、对账差异和库存修复日志
- TraceId 跨 Order、Inventory、Product Feign、RocketMQ
- Actuator/Prometheus 实际暴露与日志脱敏

## 当前阻断/未验证

- 测试目前主要是 Mockito，Lua 尚未真实 Redis 集成验证
- TCC/普通预扣账本、reinit/resize 并发和锁窗口未真实验证
- MQ 重试/DLQ、Canal UPDATE/INSERT 行为未完成运行验证

## 已验证（本轮）
- preDeduct → MQ → MySQL 全链路通过，无 1146
- Outbox 唯一键改为 `(order_id,sku_id,action)`，PRE_DEDUCT/CONFIRM 独立成行，不互相覆盖
- 线上主/从库已建 `t_inventory_prededuct_idem` 并更新 outbox 索引
- RocketMQ `INVENTORY_TOPIC` 已手动创建
