# D3-7 Redis Client 多活试点报告（2026-09-18）

## 一、目标与拓扑
- 目标：Redis 客户端 zone 感知——**读就近（本 zone 副本优先）、写主库、副本故障自动兜底与回切**。
- 拓扑（单机仿真）：主 6379（zone-a）+ 只读副本 6380（zone-b）+ Sentinel 26379（mymaster）。
- 实现：`ZoneRedisReadFromResolver` 集成进既有 `RedisConfig.defaultRedisConnectionFactory`（Lettuce `ReadFrom`）：
  - 当前 zone = slave-zone（zone-b）→ `REPLICA_PREFERRED`（读走本 zone 副本，写主库，副本不可用自动回主）；
  - 其他 zone / 未开启 → `MASTER`（读写主库，行为不变）。
  - 开关：`myxhs.availability.zone.redis.enabled`、`...master-zone`、`...slave-zone`（默认关）。

## 二、实测结果（cart zone-b 实例 19028）
| 场景 | 结果 |
|---|---|
| 读走副本（10 次 cart list） | slave `hgetall +10`；master `hgetall +0` ✅ |
| 写走主库（1 次 cart add） | master `hincrby/expire/zadd` 增加；副本经复制同步 ✅ |
| 副本故障兜底（stop 6380） | 10/10 请求 200，master `hgetall +10`（自动回主库）✅ |
| 副本恢复（start 6380） | 复制 `up`，随后 10 次读 slave `hgetall +10`（**自动回切**）✅ |

## 三、过程中发现并修复
1. **双 @Primary 冲突**：新增独立自动配置与既有 `RedisConfig` 的 `@Primary` 连接工厂冲突（启动失败 `NoUniqueBeanDefinitionException`）→ 改为**集成进既有 RedisConfig**，不新增工厂。
2. **第二实例跑旧 jar（复发）**：release 脚本第二实例模式使用 `current` 软链（旧版本）→ 已修复：第二实例优先拷贝**最新构建产物**到独立目录，避免"执行旧 jar"。

## 四、边界与后续
- 单机仿真；Sentinel 模式下 Lettuce `REPLICA_PREFERRED` 依赖副本发现，跨机房延迟未模拟。
- Redis Server 侧多活（双主/CRDT/冲突解决）未实施（评估见 `remaining-plan-20260918.md`，不在本期）。
- 应用面：仅 cart 试点验证；开启开关的服务需回归验证（默认关，无影响）。
