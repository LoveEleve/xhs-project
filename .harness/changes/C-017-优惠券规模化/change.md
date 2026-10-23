---
id: C-017
slug: 优惠券规模化
status: done
created: 2026-10-23
updated: 2026-10-23
iteration: ITER-017
relations:
  relates_to:
    - slug: docs/学习/优惠券-讲解/06-规模化改造规划（C-017）
      reason: 规划来源（P1 分片 / P2 延时过期 / P3 推送设计）
  depends_on:
    - slug: C-007
      reason: 优惠券 Design B/Outbox/DLQ 基线
---

# C-017 优惠券规模化

> 目标：补齐"32 分片 + 延时消息过期"（P3 推送先设计），保持默认单库与 726 基线不变。
> 决策（2026-10-23 用户"开始吧"）：P1 2 库 × 16 表；默认 profile 单库、`coupon-sharding` 显式启用；P2 必做；P3 先设计。

## 范围

- **P1**：`t_user_coupon` 按 `user_id` 分片（2 库 × 16 表）；模板/Outbox 全局表；Mapper 按 id 读写补 `user_id`；路由单测。
- **P2**：模板结束时发 RocketMQ 定时消息（`setDeliverTimeMs`）→ 消费端批量 0→2；XXL-Job 保留兜底。
- **P3**：推送引擎设计文档（不实现）。

## 非目标

- 不做运行时/压测验证；不声称 5 万 QPS/10 万 msg/s；不改订单/支付/库存主链路。

## 验收

- 默认 profile：`mvn -o -pl my-xhs-coupon -am test` 绿，15 模块 726 基线不变；
- `coupon-sharding` profile 编译通过 + 路由单测通过；
- P2 producer/consumer 单测通过；过期 Job 兜底不变。

## 影响面

- `my-xhs-coupon`（主体）、`sql/init-all.sql`、`sql/migration/coupon/`、`sql/mysql-content-init.sql`。

## 执行结果（2026-10-23）

### P1 分片（已完成，默认不启用）

- 新增 `my-xhs-coupon/src/main/resources/sharding-config.yaml`（2 库 × 16 表，`user_id % 2` / `(user_id DIV 2) % 16`）；
- 新增 `config/ShardingSphereDataSourceConfig`（`@Profile("coupon-sharding")`，默认 profile 不加载）；
- 新增 `application-coupon-sharding.yml`（关闭读写分离/自动数据源）；
- 新增 `sql/mysql-coupon-sharding-init.sql`（DDL + 32 分片 + 存量迁移）；
- Mapper 适配：`selectByIdAndUserId`、`markUsed/returnCoupon` 补 `user_id`、过期改为"按模板单表 UPDATE"（避免跨分片 JOIN）；
- 新增 `CouponShardingRouteTest`（路由规格）。

### P2 延时过期（已完成）

- `CouponService.sendExpireDelay`：模板创建/上线时 `syncSendDeliverTimeMills(valid_end)`；
- `CouponExpireDelayConsumer`：到点批量 0→2；提前/模板缺失忽略；
- `CouponExpireJob` 保留兜底（改为按模板循环）；
- 新增 `CouponExpireDelayConsumerTest` + 创建模板发送断言。

### P3 推送引擎（设计完成，不实现）

- `docs/学习/优惠券-讲解/07-推送引擎设计（可选）.md`。

### 验证

- coupon 测试：31 全绿（24 基线 + 7 新增）；
- 全仓测试：747 全绿（15 模块基线 733 + user 14）；
- 运行时（分片路由/延时消息/推送）按项目口径后置，不在本次验证范围。
