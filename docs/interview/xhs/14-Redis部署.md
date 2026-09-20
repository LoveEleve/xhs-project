# 第14题 | Redis 部署：Sentinel/Cluster/slot

> 难度：★★★☆☆｜频率：★★★☆☆｜区分度：中
> 关键词：Sentinel 三节点、mymaster、quorum、业务/缓存双实例、noeviction vs allkeys-lru、Lua 多 key 与 slot、切主演练

## 问题
问题：Redis 怎么部署的？为什么用 Sentinel 不用 Cluster？切主时数据/锁会怎样？

## 面试可讲版（五段式）

**① 业界背景**
Redis HA 三条路：**主从+Sentinel**（自动故障转移、单 master、语义简单，容量受限）；**Cluster**（分片扩容，但多 key 操作受 slot 限制，事务/Lua 要用 hash tag）；**Proxy（Codis/Twemproxy）**（客户端无感但多一跳、社区弱）。选型争论核心：**你是容量瓶颈还是可用性需求**——容量 < 单机极限、需要多 key 原子操作（Lua）时，Sentinel 是更划算的选择。

**② 项目选择**
**业务/缓存双实例隔离**（代码注释原话："Cache Redis 专用于可丢失的缓存数据"）：
- **业务实例**：`noeviction`（不允许淘汰，写满报错而不是静默丢数据），承载锁/计数/幂等/MQ 位点/待处理集合；
- **缓存实例**：`allkeys-lru`（允许淘汰），承载商品/Feed 等可重建缓存，挂了不影响业务正确路径；
- 持久化统一 **AOF everysec + RDB 快照**（900/300/60 秒策略）；
- HA：**Sentinel 三节点**，`monitor mymaster`，quorum 1、`down-after 5s`、`failover-timeout 30s`、`parallel-syncs 1`；客户端 Lettuce 池（max-active 15/max-idle 8/min-idle 4/max-wait 3000ms）+ Redisson（同 Sentinel，retry 5×1000ms；watchdog 15s 配置仅对 leaseTime=-1 模式生效，业务未用）；key 用 String 序列化、value 用 Jackson JSON。
- **不选 Cluster 的理由**：业务 Lua 基本都跨 key（去重+计数、库存桶、滑动窗口），Cluster 会 CROSSSLOT，改造 hash tag 侵入大；容量（512MB 级）够用，先垂直再拆分。

**③ 坑**
- 切主窗口会丢锁（Sentinel 复制异步）——但正确性不靠锁（见 01 题），这是设计前提；
- **`noeviction` 写满直接报错**：业务实例必须容量告警，不能像缓存那样"自然淘汰"；
- 切主期间客户端要能重连到新 master：Redisson `retryAttempts=5/1000ms` 就是为此调优（窗口 5-30s）；
- 端口/地址口径漂移：真实连接参数以 Nacos `my-xhs-redis.yaml` 为准（不同环境端口不同），代码里有默认值兜底——答辩时说"以配置为准"。

**④ 兜底**
- 切主演练：RV19 实测 2.3s 完成、458 个状态键无损、会话无错乱；
- Redis 故障注入（test-4，iptables 阻断）：网关 401 fail-closed、直连 hang 超时无写入、恢复后自愈；
- 缓存实例故障：降级查 DB 且**不回填**；业务实例故障（test-4 用 iptables 阻断 6379）：网关侧鉴权/黑名单 fail-closed（401）、直连请求 hang 超时且**无脏写入**、恢复后自愈；锁侧 fail-open 由下游条件更新兜底；
- 数据可丢界：业务实例 AOF everysec 最多丢 1s 写（计数/位点可对账重建）。

**⑤ 话术**
> "业务和缓存双实例隔离：业务 noeviction 不许静默丢，缓存 LRU 允许淘汰；HA 用 Sentinel 而不是 Cluster——不是 Cluster 不好，是我们的 Lua 多 key 和容量档位注定 Sentinel 更合适，跨 key 的原子操作是我们的硬需求。"

## 追问与参考回答
**追问1：为什么不用 Cluster？** ① Lua/事务多 key 会 CROSSSLOT，改造 hash tag 侵入所有脚本；② 容量与 QPS 未到单机极限；③ Sentinel 的运维/语义更简单。等容量到瓶颈再评估 Cluster 或业务层分片（多实例）。
**追问2：Sentinel 脑裂怎么办？** minority 侧旧 master 会拒写（down-after 后副本提升）；一致性靠 `min-replicas-to-write` 类参数与业务兜底；quorum=1 在 3 节点下容忍 1 挂，但容忍不了网络分区下的双主写入——需要评估（我们是单机房）。
**追问3：主从延迟影响读吗？** 默认读写都走 master（Lettuce 不自动读写分离）；需要读副本时要显式配置并接受延迟。
**追问4：AOF everysec 丢 1s 能接受吗？** 分数据：计数/位点可重建；锁丢了有兜底；缓存可重建。不可丢的数据不在 Redis（DB 是权威）。
**追问5：缓存实例挂了会怎样？** 命中率掉、回源压 DB（有 singleflight 与降级）；业务实例不受影响——实例隔离就是为了这个。

## 发散追问地图（横向）
- Redis 部署形态：主从/Sentinel/Cluster/Proxy 的容量、多 key、运维对比；集群 slot 迁移。
- 持久化：RDB/AOF/混合（4.0+ RDB-AOF）、everysec vs always 的延迟代价。
- 客户端：Lettuce vs Jedis、连接池参数、Sentinel 拓扑刷新、集群拓扑刷新。
- 内存治理：maxmemory-policy 选择、碎片整理（activedefrag）、大 key 治理、过期策略。
- 可观测：Redis exporter 指标、慢日志、热点 key、复制延迟。
- 安全：requirepass/ACL、rename-command、网络白名单、云托管（Tair/Redis Enterprise）替代。

## 面试官评分点
**高级开发级**：能讲清双实例隔离与两种淘汰策略；知道 Sentinel 的语义与切主窗口。
**架构师加分**：为什么 Sentinel 而非 Cluster（多 key/容量/演进路径）、故障域隔离、丢数据界、演练与验证。
**危险信号**：不知道淘汰策略差异；Cluster 多 key 坑没概念；宣称 Redis 数据不丢。

## 本项目真实证据
- `RedisConfig:49,60-86,97-98`：默认连接 Business Redis、Sentinel/单机自动切换、Lettuce 池、String+Jackson 序列化；`RedisMultiSourceConfig:15-36`：Cache Redis 独立（standalone，`cache.port` 默认 16380，"专用于可丢失缓存"）。
- `config/redis/sentinel.conf`：`port 26379`、`monitor mymaster 21.130.247.89 6379 1`、`down-after 5000`、`failover-timeout 30000`、`parallel-syncs 1`、auth-pass。
- `deploy/machine-b-middleware.yml:12-60`：redis-business 512mb **noeviction**、redis-cache 256mb **allkeys-lru**、AOF everysec。
- 演练：RV19（6379→6380，2.3s，458 键无损）；test-4 iptables 6379 阻断（401 fail-closed/hang 超时无写入/自愈）。

## 版本与来源
Redis Sentinel/Cluster 官方文档；Lettuce/Redisson 配置文档；本项目配置与 RV19/test-4 记录。

## 真实性说明
Sentinel 参数、双实例策略、池参数均为仓库配置事实；端口在不同环境不一致（以 Nacos 为准，演练环境为 6379/6380）；"Cluster 未采用"是设计决策。

## 本轮补充（2026-09-20 故障注入：语义矩阵与两处修复）
- 拓扑事实：6380=主（可写）/ 6379=只读从 / 6381 未运行；**容器命名主从颠倒**；单 sentinel（quorum=1；配置仍写 6379、运行态 6380=曾发生 failover）。
- 修复1（网关）：Redis 故障原全站 `401 Token 已被注销`（误导+误登出）→ 改 **503「认证服务暂不可用」**（保持 fail-closed、客户端可重试）。
- 修复2（读路径）：自定义 Lettuce 工厂无 commandTimeout（默认 60s）→ CacheHelper 的 DB 回退永不触发；补 **1s 超时**（Nacos 公共配置 + 两个自定义工厂）。
  主库暂停实测：product **200（DB 回退）**（首跳 11s=多层超时叠加、后续 0.01s）、counter 0.26s、cart 0.64s、notification 0.58s。
- 风险：认证链路对 Redis 是 **SPOF**；建议黑名单本地缓存 + pub/sub 变更广播或 HA；sentinel 应 3 节点且命名/配置统一。
