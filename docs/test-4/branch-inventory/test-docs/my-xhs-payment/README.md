# my-xhs-payment 测试重点

## 当前状态

- payment 非 `target` 文件基线：33 个
- 本轮修复测试编译（构造器缺 paymentEventMapper）与 P1-1 回查 mock 缺失，payment 5 个测试全部通过；common 53 个通过
- 运行态修复：XXL-Job 全量初始化（19 任务），payment 超时/补偿任务已确认按 cron 执行（handle_code=200）
- payment 服务（19012）已重启 UP，executor（9992）已重新注册
- AI 排除；鉴权仅基础检查

## L1 业务

- 创建支付单：幂等（重复支付拦截）、订单状态回查（非待付款拒绝）、渠道策略分发
- 支付回调：乐观锁幂等、成功/失败分支、订单业务拒绝自动退款
- 退款：幂等、归属校验、部分退款/全额退款、金额上限校验
- 退款回调：乐观锁幂等、累计退款=支付金额才置已退款
- 支付状态查询

## L2 数据

- 独立库 my_xhs_payment：t_payment / t_refund / t_payment_event
- Redis：paying/status/refunding 幂等键、回调 pending、补偿计数
- MQ：PAY_RESULT_TOPIC / REFUND_RESULT_TOPIC（预留，无消费端）
- XXL-Job：paymentTimeoutCheckJob、refundTimeoutCheckJob、paymentNotifyCompensateJob、refundNotifyCompensateJob

## L3 质量

- 支付/退款幂等三重保障（Redisson 锁 + Redis 键 + 乐观锁）
- 支付成功 Feign 失败 → 补偿 Job 重发；业务拒绝 → 自动退款
- 回调模拟器 90% 成功率 + 分布式锁防重复回调
- 对账 reconcile 游标分页

## 运行级待验证（后续统一联调）

- 完整链路：下单 → 支付(Mock/异步渠道) → 订单已支付 → 退款 → 订单已退款/库存释放
- 部分退款：两次部分退款后订单保持已支付，第三次全额退款才置已退款
- 支付失败通知订单自动取消
- XXL-Job 补偿：模拟 Feign 失败后 paymentNotifyCompensateJob 收敛订单状态
