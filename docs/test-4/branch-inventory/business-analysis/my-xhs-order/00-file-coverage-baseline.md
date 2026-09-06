# my-xhs-order 文件覆盖基线

## 顶层盘点

依据：`docs/test-4/branch-inventory/project-directories/my-xhs-order/inventory.md`

- `Dockerfile`
- `docs/`
- `pom.xml`
- `src/`
- `target/`（构建生成物，不纳入源码逻辑分析）

## 候选文件总数

当前非 `target/` 文件共 56 个：
- 顶层构建文件：2 个
- `docs/`：1 个
- `src/main/java`：45 个
- `src/main/resources`：3 个
- `src/test/java`：5 个

## 分析重点

- 下单创建、支付、发货、收货、取消、退款和关单
- 分库分表（Order sharding）、订单号映射、Payment 独立数据源
- RocketMQ 事务消息、本地消息表、Outbox、补偿和重试
- 多个 Feign（Product/Cart/Coupon/Inventory/Payment/User）及 Fallback
- 库存预扣、优惠券折扣、支付回调、关单/补偿任务
- 分布式锁、幂等、状态机、分库路由、性能与工程
- MySQL 分片、快照、事件、DTO、Mapper、配置和测试

## 覆盖要求

- 56 个候选文件逐一阅读并记录准确 `file:line`
- 配置、sharding、事务消息和测试不能只做文件名盘点
- `target/` 不纳入源码逻辑分析
- 外部 common、SQL、其他服务只作为跨模块证据，单独标记边界
- 分析完成后必须与实际目录扫描和 `project-directories` inventory 对账
