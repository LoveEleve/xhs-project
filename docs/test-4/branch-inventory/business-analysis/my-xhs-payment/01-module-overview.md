# my-xhs-payment 模块总览

## 1. 当前模块定位

支付服务（端口 19012），负责支付单创建、支付回调、退款、退款回调、支付/退款超时检查与对账。是交易链路的资金域，与 order 强耦合（支付前回查订单状态、支付/退款结果 Feign 通知订单）。独立数据源 `my_xhs_payment`（不走 ShardingSphere），表 t_payment / t_refund / t_payment_event。

## 2. 核心设计

```text
支付:
  order Feign 发起(payType) → Redisson锁+订单状态回查(P1-1) → 建支付单
  → 策略支付(Mock同步成功/支付宝微信异步回调) → 乐观锁置1
  → Feign通知order + MQ(预留) → order拒绝则自动退款

退款:
  order Feign 发起 → Redisson锁+refunding幂等键 → 金额校验(累计已退)
  → 建退款单 → 策略退款(Mock同步/异步回调模拟) → 全额才通知order(Feign+MQ)

兜底: XXL-Job(超时检查/通知补偿) + @Scheduled回调模拟器(5s) + 对账(reconcile)
状态机: t_payment 0待付→1成功→3已退 | 0→2失败; t_refund 0中→1成功|2失败|3关闭
幂等: Redisson锁 + Redis SETNX键 + DB乐观锁(WHERE status=当前) 三重
```

## 3. 当前关键事实

- 独立数据源：payment 模块自建 Hikari 数据源直连 `my_xhs_payment`，JdbcTemplate 手写 SQL（MyBatis-Plus 仅用于 t_payment_event）
- 支付前回查订单状态（P1-1）：仅待付款(0)订单可支付，防止钱货两空
- 支付成功 Feign 通知 order，order 返回业务失败则自动退款
- 部分退款支持：仅"累计退款=支付金额"才置 t_payment=3 并通知订单；部分退款保持已支付
- PAY_RESULT_TOPIC/REFUND_RESULT_TOPIC 当前无消费端（order 侧走 Feign 同步），MQ 仅为预留；真实兜底为 XXL-Job 补偿任务
- **运行态修复**：部署时 xxl-job 未初始化任何执行器组/任务，支付超时检查、退款超时、通知补偿全部不运行；已补全 19 个任务注册并验证执行
- 测试已修复：`PaymentServiceTest` 构造器缺 `paymentEventMapper`（编译失败）已补；P1-1 新增 `getOrderStatus` 回查未 mock 导致 5 个测试全 error 已补；本轮 5 个测试全部通过

## 4. 范围

非 `target` 文件 33 个：顶层/文档 3、主源码 25、资源 3、测试 1、Dockerfile 1。
AI 排除；鉴权仅基础检查。
