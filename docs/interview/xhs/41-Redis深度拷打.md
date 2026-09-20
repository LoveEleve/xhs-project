# 第41题 | 组件深度拷打：Redis

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：数据结构选型、RDB/AOF、maxmemory 策略、Sentinel/Cluster、Lua 与 slot、bigkey/hotkey、切主演练

## 问题
Redis 怎么用的？持久化怎么配？为什么用 Sentinel 不用 Cluster？大 key/热 key 怎么治理？切主会丢什么？

## 面试可讲版（五段式）

**① 数据结构选型（项目矩阵）**
| 场景 | 结构 | 原因 |
|---|---|---|
| 购物车 | Hash（数量）+ Set（选中）+ ZSet（排序） | 三种访问模式；hash tag 同 slot |
| 点赞/收藏 | Set / ZSet | SADD 幂等 / 时间序 |
| 关注/Feed 收件箱 | ZSet（score=时间戳） | 时间序范围查询 |
| 计数 | String（INCR）+ Set（去重） | 原子增减 + 权威 Set |
| 限流 | ZSet（滑动窗口）+ Lua | 精确窗口 |
| 锁 | String（SET NX PX，Redisson） | 互斥 + TTL |
| 幂等/防重放 | String（SETNX + TTL） | 一次性标记 |
| 号段/队列 | List/Hash | 号段缓存（实际在 DB+内存） |
- 全项目 Key 统一 `myxhs:` 前缀 + 分域命名；**23 个 Lua 脚本**覆盖多 key 原子操作（库存分桶/购物车/券/社交）。

**② 持久化与内存**
- **持久化**：`appendonly yes + everysec`（AOF 秒级刷盘，最多丢 1 秒）+ `save 900 1 / 300 10`（RDB 快照兜底）；备份体系：**每 6 小时 BGSAVE**；
- **内存**：master `maxmemory 512mb + noeviction`（业务数据**不能 LRU 淘汰**——宁可写失败也不无声丢数据）；缓存型实例用 `allkeys-lru`（本项目业务/缓存分实例的设计取向）；
- **监控**：`used_memory/maxmemory`、`evicted_keys`、`aof_last_write_status`、连接数——配 Redis 内存/连接告警。

**③ Sentinel vs Cluster**
- **Sentinel（本项目选型）**：主从 + 哨兵（26379），`down-after-milliseconds=5000 / failover-timeout=30000`；客户端（Redisson/Lettuce）自动发现新主；
- **为什么不用 Cluster**：数据量/分片需求未到（单实例 10MB 级使用量）；Cluster 的 slot/多 key 约束（CROSSSLOT）会全面约束 Lua 写法；Sentinel 运维简单、语义直观——**规模驱动选型**；
- **Lua 与 slot**：多 key 脚本必须同 slot（hash tag `{userId}`/`{skuId}`/`{templateId}`）——即使现在单机，也按 Cluster-ready 规范写（迁移成本前置）。

**④ 可用性与切主**
- **切主演练（RV19）**：实测 **2.3s 完成、458 个状态键无损、会话无错乱**；Redisson `retryAttempts=5/1000ms` 覆盖重连窗口；
- **切主会丢什么**：异步复制的**最后写入可能丢**（RPO>0）——所以核心正确性不能押在缓存上（锁/幂等都有 DB 兜底）；
- **扩展（本次多活专项）**：zone 感知 `ReadFrom.REPLICA_PREFERRED`（读本地副本/写主库/副本故障自动回主）；**双主仿真**（DUMP/RESTORE + LWW + 对账，单侧宕机不中断）。

**⑤ 大 key / 热 key（拷打重点）**
- **大 key**：治理手段——拆分（Hash 分桶/大集合 scatter）、压缩（ziplist 编码阈值）、异步删除（UNLINK 替代 DEL）、监控（`redis-cli --bigkeys` / RDB 分析）；本项目库存分桶（2→8 桶自适应）就是热点+大 key 的合并解法；
- **热 key**：本地缓存（**本项目决策不用**）→ 副本分散读（`REPLICA_PREFERRED`）→ 限流/排队 → key 打散（加后缀分片）；
- **热点探测**：滑动窗口统计（本项目库存的"热点 SKU 检测自动扩容"）；JD-hotkey 是业界方案（未引入）。

**⑥ 拷打追问**
1. **"AOF everysec 丢 1 秒能接受吗？"** 业务数据（计数/购物车）可容忍秒级；订单等强数据在 MySQL（Redis 只是加速/权威缓存且有对账）；关键操作不等 AOF。
2. **"noeviction 满了会怎样？"** 写命令报错（OOM command not allowed）——业务上表现为写失败（有异常处理与告警），不会静默丢；这是有意选择。
3. **"Sentinel 脑裂怎么办？"** 少数派旧主继续接受写→双写风险；缓解：客户端写失败重试新主 + 业务幂等/版本号；严格场景需 min-replicas-to-write（本项目未配，是边界）。
4. **"Lua 脚本太长会阻塞吗？"** 会——Redis 单线程执行脚本，脚本要短（本项目脚本都是毫秒级、参数化）；长逻辑拆多步或放服务端算。
5. **"为什么不用 Redisson 的分布式锁替代 Lua？"** 锁解决"互斥"（效率），Lua 解决"多 key 原子"（正确性）；库存/券这类必须 Lua（锁+多步仍可能中间失败）；锁见 01 题。
6. **"Redis 挂了会怎样？"** 分场景：锁→加锁请求 fail-closed（拒绝/跳过），切主丢锁只影响效率；限流→降级放行（保护性）；缓存→直查 DB（可能压垮，限流配合）；计数→缓冲重试。测试过 Redis 阻断下的降级。

**⑦ 话术**
> "Redis 我们是 Sentinel 主从：主 6379 从 6380 哨兵 26379，AOF everysec 加 RDB 兜底，maxmemory 512M 用 noeviction——业务数据宁可写失败也不静默淘汰。不用 Cluster 是规模和语义驱动：数据量没到、Cluster 的 slot 会全面约束 Lua，但 Lua 我们仍按 hash tag 同 slot 规范写。切主演练过，2.3 秒完成、458 个状态键无损；异步复制会丢最后写入，所以正确性不押在缓存上。大 key 热 key 的解法是分桶加副本分流，本地缓存我们评估后不用。"

## 发散追问地图（横向）
- 持久化：RDB fork 与写时复制、AOF rewrite、混合持久化。
- 高可用：Sentinel 选举/脑裂、Cluster 分片/迁移、代理（Codis/Twemproxy）。
- 性能：pipeline、单线程模型、慢查询、IO 多线程。
- 治理：bigkey/hotkey 工具、内存碎片（activedefrag）、key 过期策略。
- 扩展：Redis 双主/CRDT、Redis Stack（JSON/搜索）。

## 面试官评分点
**高级开发级**：能讲清结构选型/持久化/淘汰策略与项目对应。
**架构师加分**：Sentinel vs Cluster 的规模与语义取舍；hash tag 的迁移成本前置；切主丢写与"正确性不押缓存"的关系；大 key/热 key 的组合解法。
**危险信号**：业务数据用 LRU 淘汰；多 key Lua 不管 slot；以为切主不丢数据；大 key 只会说"拆"。

## 本项目真实证据
- 容器配置（AOF everysec/noeviction/512M/save 900 300）；Sentinel down-after=5000/failover-timeout=30000；`RedisKeyConstants`（myxhs: 前缀分域）；23 个 Lua；
- RV19 切主演练（2.3s/458 键无损，xhs/14 题引用）；`redis-zone-drill-20260918.md`（ReadFrom 读副本/回主/回切）、`redis-server-multi-active-20260918.md`（双主 LWW）；
- 库存分桶自动扩容（03 题）；`RedisHighMemoryUsage`/`RedisHighConnections` 告警规则。

## 版本与来源
Redis 官方文档（持久化/淘汰/Sentinel/Cluster）；RV19 演练记录；本项目配置与多活报告。

## 真实性说明
配置参数/演练数字/脚本数量均为仓库与运行态事实；"未配 min-replicas-to-write"为主动边界。

## 本轮补充（2026-09-20 故障语义实测）
- **故障语义矩阵**：网关 401「Token 已被注销」→503「认证服务暂不可用」（fail-closed 可重试）；主库暂停 product 200（DB 回退，首跳 11.2s/后续 0.01s）；从库暂停无感；限流 fail-open。
- 自定义 Lettuce 工厂补 **1s commandTimeout**（原 60s 默认使 DB 回退等不到）。
- 切主 2.3s 会话无错乱（458 状态键）；更新→读 24ms 一致；点赞集合 TTL 治理（84 键回填 + Lua EXPIRE）。
