# my-xhs-coupon 文件覆盖基线

## 顶层盘点

依据：`docs/test-4/branch-inventory/project-directories/my-xhs-coupon/inventory.md`

- `Dockerfile`
- `pom.xml`
- `src/`
- `target/`（构建生成物，不纳入源码逻辑分析）

## 候选文件总数

当前非 `target/` 文件共 35 个：
- 顶层构建文件：2 个
- `src/main/java`：24 个
- `src/main/resources`：5 个（3 配置 + 2 Lua）
- `src/test/java`：4 个

## 分析重点

- 优惠券模板创建、领券、用券、退券、过期和回收
- Redis Lua 原子领券、超卖防护、限领、模板库存
- RocketMQ 领券异步落库、退券回补、Outbox 和消费者
- 优惠券对账、过期任务和补偿
- 校验器、状态机、分布式锁、幂等、缓存
- 金额折扣计算、User 身份、Order 使用券链路
- DTO、Mapper、配置、Lua、测试实现一致性

## 覆盖要求

- 35 个候选文件逐一阅读并记录准确 `file:line`
- Lua、配置、校验器和测试不能只做文件名盘点
- `target/` 不纳入源码逻辑分析
- 外部 common、SQL、User/Order 只作为跨模块证据，单独标记边界
- 分析完成后必须与实际目录扫描和 `project-directories` inventory 对账
