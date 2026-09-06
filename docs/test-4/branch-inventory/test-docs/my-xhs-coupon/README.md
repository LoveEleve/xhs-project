# my-xhs-coupon 测试重点

## 当前状态

- coupon 非 `target` 文件基线：35 个
- 本轮已执行 `mvn -pl my-xhs-coupon -am test`，coupon 13 个、common 53 个测试通过
- coupon 尚未重新打包/重启，19010 运行态待后续验证
- AI 排除；鉴权仅基础检查

## L1 业务

- 模板创建、上下架、列表、领取窗口
- 领券、用券核销、退券
- 金额门槛/折扣计算
- 用户券列表、可用券、过期
- 校验器边界（金额、状态、有效期、使用条件）

## L2 数据

- Redis `myxhs:coupon:{templateId}:stock`、`claimed:{userId}`、模板缓存
- MySQL `t_coupon_template`/`t_user_coupon`/`t_coupon_outbox`
- RocketMQ `COUPON_CLAIM_TOPIC`/`COUPON_RETURN_REDIS_REPAIR_TOPIC` 消费/重试/DLQ
- Outbox 补发与唯一键幂等
- 对账任务 Redis↔MySQL 修复

## L3 质量

- 并发领券超卖
- perUserLimit 限领绕过（领→用→退→再领）
- 幽灵券（MQ 超时但 broker 已投递/Outbox 补发）
- 对账与异步消费双扣源
- 陈旧缓存初始化 Redis 库存
- Outbox Job 锁租约与并发补发
- 用券乐观锁并发核销

## L4 可观测性

- 领券成功率、扣减失败、重试/DLQ、Outbox 积压指标
- 对账差异和幽灵券日志
- 模板缓存命中/回源/失效
- Actuator/Prometheus 实际暴露

## 当前未修改/待确认

- 退券回补导致超发和限领绕过（业务规则待产品确认）
- 陈旧缓存初始化 Redis 库存
- Outbox 无清理
- 死代码 deleteByClaimNo
- Lua、并发、对账、幽灵券真实环境验证
