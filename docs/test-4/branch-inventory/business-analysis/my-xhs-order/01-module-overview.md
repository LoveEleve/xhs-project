# my-xhs-order 模块总览

## 1. 当前模块定位

订单服务（端口 19011），负责下单、支付、发货、收货、取消、退款、关单全生命周期。是交易链路核心，Feign 依赖 Product/Inventory/Coupon/Payment/User 五个服务（无 Cart Client；User 无 FallbackFactory）。

## 2. 核心设计

```text
下单:
  Redis幂等+用户锁 → Feign校验(product/inventory) → 地址快照 → 金额/优惠
  → RocketMQ事务消息(本地消息表同事务) → 优惠券核销 → 延时关单

支付: MockPay/独立payment服务 → onPaymentSuccess → 状态0→1 → 发关单
取消/关单/退款: 释放库存+退券 → 补偿MQ/Redis set兜底 → Job重放

分片: ShardingSphere user_id 4库×4表; 订单号映射表反查; payment独立库
状态机: 0待付→1已付→2已发→3完成; 0→4取消; 1→5退款
事件流: t_order_event append-only + 乐观锁状态收敛
```

## 3. 当前关键事实

- RocketMQ 事务消息 + 本地消息表（Outbox）保证下单原子性
- 状态变更全部经乐观锁（WHERE status=current）防并发
- 分片订单号映射表解决非分片键查询
- 补偿 = MQ + Redis set 兜底 + Job 重放
- RocketMQ broker 当前运行中；order 服务（19011）未启动，分布式行为需启动后运行确认
- 测试已修复：`OrderControllerTest` 补 `AccessTokenGuard` 构造器、`getOrderPayAmount_notFound` 断言对齐；本轮 58 个测试全部通过

## 4. 范围

非 `target` 文件 56 个：顶层/文档 3、主源码 45、资源 3、测试 5。
AI 排除；鉴权仅基础检查。
