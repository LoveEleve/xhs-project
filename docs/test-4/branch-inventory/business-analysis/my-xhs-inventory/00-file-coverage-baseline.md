# my-xhs-inventory 文件覆盖基线

## 顶层盘点

依据：`docs/test-4/branch-inventory/project-directories/my-xhs-inventory/inventory.md`

- `Dockerfile`
- `docs/`
- `pom.xml`
- `src/`
- `target/`（构建生成物，不纳入源码逻辑分析）

## 候选文件总数

当前非 `target/` 文件共 40 个：
- 顶层构建文件：2 个
- `docs/`：1 个
- `src/main/java`：26 个
- `src/main/resources`：7 个（3 配置 + 4 Lua）
- `src/test/java`：4 个

## 分析重点

- Redis 分桶库存、total/bucket/bucket-count
- 预扣、确认、释放、退款回补和 TCC Fence
- RocketMQ 事务消息、Outbox、消费者、重试和补偿
- Redis 原子 Lua、幂等键、事件版本、库存对账
- Canal 缓存失效、Product Feign、热 SKU 检测
- TCC 超时、预扣超时、库存补偿和重建任务
- MySQL available/locked 库存一致性、分布式锁、并发超卖
- DTO、Mapper、配置、Lua、测试实现一致性

## 覆盖要求

- 40 个候选文件逐一阅读并记录准确 `file:line`
- 资源 Lua、SQL/配置契约和测试不能只做文件名盘点
- `target/` 不纳入源码逻辑分析
- 外部 common、SQL、Order/Cart/Product 只作为跨模块证据，单独标记边界
- 分析完成后必须与实际目录扫描和 `project-directories` inventory 对账
