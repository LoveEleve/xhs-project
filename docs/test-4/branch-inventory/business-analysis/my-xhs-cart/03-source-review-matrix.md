# my-xhs-cart 文件级 Review 与状态

## 文件基线

- 实际非 `target/` 文件：34 个
- 顶层 `Dockerfile`、`pom.xml`：2 个
- `src/main/java`：21 个
- `src/main/resources`：9 个（3 配置 + 6 Lua）
- `src/test/java`：2 个
- 原基线曾误写为 33 个/Java 22 个，已修正

## 逐文件覆盖

| 文件范围 | 数量 | 状态 |
|---|---:|---|
| `Dockerfile`、`pom.xml` | 2 | 已读取并完成构建契约/依赖审查 |
| `src/main/java` 全部 Java | 21 | 已读取并完成业务/组件契约审查；核心 Service/Consumer/Job 深度审查 |
| `src/main/resources` 3 个配置 | 3 | 已读取并完成 Redis/MQ/数据源/日志审查 |
| `src/main/resources/lua` 6 个脚本 | 6 | 已读取并完成 key、返回值、原子操作和边界审查 |
| `src/test/java` 2 个测试 | 2 | 已读取并真实执行 |

## 关键已确认问题

- 清空三结构与 cleared marker 不是一个 Lua 原子操作
- 事件时间戳跨实例不提供全序，消费者先查后写存在 TOCTOU
- 对账锁固定值 + 无条件删除，锁过期后可能误删新锁
- 对账读取 Redis/MySQL 无用户级写屏障，存在旧读覆盖
- Product Feign 异常 fallback 放行，可能写入幽灵 SKU
- 合并逐 SKU 串行 Feign/Lua/MQ，存在放大
- Redis 恢复与 30 天 cleared marker 生命周期不一致
- null/缺失 updatedAt 的事件状态条件需核实
- 配置含固定 IP/敏感 fallback；Dockerfile 依赖外部基础镜像

## 测试结果

- `CartSyncConsumerTest`：2 个通过
- `CartServiceTest`：13 个通过
- 总计：15 个通过，0 failures，0 errors
- 本轮从仓库根目录执行 `mvn -pl my-xhs-cart -am test`，15 个测试全部通过；测试只覆盖 Mockito 主路径，6 个 Lua 尚未通过真实 Redis 集成验证

## 暂不修改

- 事件版本/全序、清空屏障、对账锁和恢复语义需要结合 CartEvent、消费者、MySQL schema 和业务契约统一设计后再改
- 本轮不纳入 AI；鉴权只做基础检查
- 真实 Redis、MySQL、RocketMQ、Feign 和对账并发尚未联调
