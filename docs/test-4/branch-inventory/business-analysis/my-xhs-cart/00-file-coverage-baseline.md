# my-xhs-cart 文件覆盖基线

## 顶层盘点

依据：`docs/test-4/branch-inventory/project-directories/my-xhs-cart/inventory.md`

- `Dockerfile`
- `pom.xml`
- `src/`
- `target/`（构建生成物，不纳入源码逻辑分析）

## 候选文件总数

当前非 `target/` 文件共 34 个：
- 顶层构建文件：2 个
- `src/main/java`：21 个
- `src/main/resources`：9 个（3 个配置 + 6 个 Lua）
- `src/test/java`：2 个

## 分析重点

- Redis 三结构购物车：items Hash、checked Set、sort ZSet
- Lua 原子操作、购物车上限、数量更新、删除、勾选和合并
- Redis 权威数据与 MySQL 异步持久化
- Product Feign、Fallback、RocketMQ 消费、对账任务
- Redis 丢失恢复、清空标记、缓存 TTL、MQ 重试和最终一致性
- DTO/Entity/Mapper、配置、Lua 脚本、测试实现一致性

## 覆盖要求

- 34 个候选文件逐一阅读并记录准确 `file:line`
- Lua 脚本、配置和测试文件不能仅列文件名
- `target/` 不纳入源码逻辑分析
- 分析完成后必须和实际目录扫描及 `project-directories` 清单对账
