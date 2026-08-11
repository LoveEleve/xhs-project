# Redis 排障（内存 / 连接 / Sentinel / 热点 / 缓存一致性）

> Redis 是 my-xhs 的**高依赖中间件**：缓存、分布式锁、权威存储（cart/inventory/counter）、Feed、社交关系。

## 业务问题（AI 能回答）
- **内存**：used/maxmemory、LRU 淘汰、OOM 命令拒绝。
- **连接/时延**：命令延迟、连接数、阻塞。
- **Sentinel 切换**：选举窗口内的读写中断、故障转移。
- **热点 key / 大 key**：某 key 流量集中、大对象。
- **缓存一致性**：延迟双删/逻辑过期是否生效、脏读。

## 可用的数据资产与就绪度
| 排障目标 | 数据来源 | 就绪度 |
|---------|---------|:---:|
| 命令延迟 | Prometheus `lettuce_command_seconds` | ✅ |
| 内存/淘汰 | Redis INFO(需 exporter) | ⚠️ **无 redis-exporter** |
| Sentinel 状态 | `sentinel masters` 查询 | ⚠️ 需采集 |
| 热点 key | Redis 慢日志/Monitor(需开启) | ⚠️ 需观测 |
| 缓存命中 | 应用指标(需自定义) | ⚠️ 需观测 |

> **已知缺口**：无 `redis-exporter` 容器，Redis 内存/命中/淘汰指标当前未采集。maxmemory=256mb allkeys-lru，极端热数据超限会写拒绝。

## 关键诊断点
1. **依赖分级**：不同服务对 Redis 依赖不同——cart/inventory 是**权威存储(不可降级)**，user/product/coupon 是可降级缓存。排障必须区分。
2. **OOM**：maxmemory 触发 LRU 淘汰冷数据；全热数据>256mb 则写拒绝 → 缓存/锁/验证码不可写。
3. **Sentinel 切换窗口(30-60s)**：Lettuce 自动跟随，但大部分服务**无 Redis 操作重试**，窗口内操作直接失败（已知缺陷）。
4. **缓存一致性**：延迟双删(用户) vs 逻辑过期(商品) 取舍不同；写后读走主库。

## 关联
- Redis 不可用对各服务的降级行为差异极大 → 见 `failover-scenarios.md` §三。
- 缓存脏读/穿透常是**业务数字异常**的根因 → 关联 ops-health 对账。
