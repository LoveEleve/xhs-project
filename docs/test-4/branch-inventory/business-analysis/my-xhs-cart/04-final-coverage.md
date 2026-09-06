# my-xhs-cart 最终覆盖对账

## 文件数量

- 顶层：2 个
- `src/main/java`：21 个
- `src/main/resources`：9 个（3 配置 + 6 Lua）
- `src/test/java`：2 个
- 非 `target` 总数：34 个

原基线中的 33 个和 Java 22 个是统计错误，已修正。

## 覆盖状态

- 34 个文件均已读取
- 核心 `CartService`、两个 Consumer、`CartReconcileJob`、Product Feign、Fallback、RedisScriptConfig 和 6 个 Lua 已完成逻辑审查
- DTO、Entity、Mapper、Dockerfile、配置完成契约审查
- 本轮从仓库根目录执行 `mvn -pl my-xhs-cart -am test`，`CartServiceTest` 13 个、`CartSyncConsumerTest` 2 个，全部通过
- 测试实际只验证 Mockito 主路径，不能替代真实 Redis Lua、MQ、MySQL、Feign 和对账并发验证
- `target/` 为生成物，不纳入源码覆盖
- AI 排除；鉴权只做基础边界检查

## 已确认问题

### P0/P1
- 仓库配置存在 Redis/MySQL/JWT/XXL-Job 明文凭据，应轮换并迁移到外部密钥注入
- 清空三结构、marker 和 CLEAR 事件缺少统一原子屏障，存在并发可见性风险
- 定时全量对账使用固定锁值并无条件删除，TTL 600 秒且无续租；管理端点单用户对账绕过全局锁
- Consumer DELETE/CHECK/CLEAR 采用先查后写/删，DELETE 未将时间条件下推到数据库
- 事件时间使用生产者时钟和毫秒数据库精度，不提供严格全序
- Product fallback 的明确失败与异常路径语义不一致，异常时可能放行幽灵 SKU

### P2
- 六个 Lua 未做真实 Redis 集成测试
- 合并逐 SKU 执行 Lua/MQ，事件量会放大
- 对账没有用户级写屏障，Redis/MySQL 读取不是快照
- 内存 CLEAR barrier 重启丢失，30 天 cleared marker 与 MySQL 生命周期不一致
- CartEventSink 幂等标记与数据库写入非同一事务
- Actuator/Prometheus、日志脱敏、锁续租和实际 Redis 拓扑尚未运行确认

## 不应写成已确认运行事实

- 当前实际 Redis 拓扑属于 Cluster 还是 Sentinel
- RocketMQ 一定已经按预期解析 topic/tag
- MQ 重试/DLQ 一定按注解工作
- 主从时间戳一定能提供全序
- 对账冲突一定已经发生

这些需要真实环境测试或生产数据证据。
