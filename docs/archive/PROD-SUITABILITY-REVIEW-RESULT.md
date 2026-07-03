# my-xhs 项目 P8 级别技术选型评审报告

> 评审日期：2026-06-01 | 基于 `PROD-SUITABILITY-REVIEW-PROMPT.md` 逐项审查
> 核心理念：P8 项目就该用生产级方案。问题是——选对了没有？

---

## 技术选型合理性总览

| 技术 | 选型方向 | 是否选对？ | 关键发现 |
|------|---------|:---:|------|
| ShardingSphere | JDBC 模式 ✅ | ⚠️ | 4 数据源指向同一 MySQL → 伪分库；3 实例即超 max_connections |
| ES 8.12.2 | 搜索引擎 ✅ | ⚠️ | License 风险（SSPL）；IK vs jieba 未评估 |
| Redis 7 | 缓存+存储 ✅ | ⚠️ | 单点无高可用；RDB 未显式关可能仍开启；256MB allkeys-lru 危险 |
| RocketMQ 5.1.4 | 消息队列 ✅ | ⚠️ | 用 5.x 镜像走 4.x 协议；未启用 Proxy；延迟粒度固定 |
| Canal 1.1.7 | CDC ❌ | 🔴 | **已 EOL**，社区停止维护 |
| XXL-Job 2.4.2 | 调度 ⚠️ | ⚠️ | 作者转 PowerJob；16 个 Handler 几乎没用分片 |
| Sentinel 1.8.8 | 限流熔断 ✅ | ⚠️ | 只有 flow 规则，无 degrade 熔断规则落地 |
| Nacos 2.3.0 | 注册中心 ✅ | ⚠️ | AP/CP 模式未显式选择；Config 全服务未启用 |
| SkyWalking 9.7.0 | 链路追踪 ✅ | ⚠️ | Agent 未挂载，OAP 徒耗 ES 存储 |
| CosId 2.6.8 | 分布式 ID ❌ | 🔴 | **声明了但从未集成**，实际用自研方案 |
| Prometheus+Grafana | 监控 ✅ | ✅ | 选择正确 |

---

## 第一章：存储方案深度评审

### 1.1 ShardingSphere：方向对了，实现错了

#### 选型合理性

ShardingSphere-JDBC 5.5.1 作为分库分表方案 —— **选择本身是正确的**。JDBC 模式零网络跳转，性能最优。对比 Proxy 模式的选择理由也成立：Proxy 增加一跳网络延迟，JDBC 模式更适合性能敏感的交易场景。

#### 但实现层面有 4 个严重问题

**问题一：生产上是什么？当前是伪分库**

```yaml
# sharding-config.yaml 的真实配置
dataSources:
  ds0: jdbc:mysql://21.91.124.110:13308/my_xhs_order_0
  ds1: jdbc:mysql://21.91.124.110:13308/my_xhs_order_1  # ← 同一主机！
  ds2: jdbc:mysql://21.91.124.110:13308/my_xhs_order_2  # ← 同一主机！
  ds3: jdbc:mysql://21.91.124.110:13308/my_xhs_order_3  # ← 同一主机！
```

4 个"数据源"实际是同一台 MySQL 的 4 个 database。Yaml 注释也承认："开发环境：4 个库都在同一 MySQL 实例上"。这不是真正的水平扩展，是同机垂直拆分。

**问题二：JDBC 模式的连接池膨胀**

Order 服务有 6 个数据源（4 个分片 + Payment + Mapping），单服务实例连接数 50。整个项目所有 MySQL 实例的连接池汇总：

| MySQL 实例 | 连接服务数 | 每服务池大小 | 单实例总连接 | max_connections | 2 实例比例 | 3 实例比例 |
|-----------|:---:|:---:|:---:|:---:|:---:|:---:|
| mysql-user (13306) | 4 | 20 | 80 | 300 | 53% | **80%** |
| **mysql-content (13307)** | **6** | **20** | **120** | **300** | **80%** | **⚠️ 120%** |
| mysql-order (13308) | order(4×10)+payment(2×20)+mapping(5) | — | **90** | 500 | 36% | 54% |
| mysql-inventory (13309) | 1 | 20 | 20 | 200 | 20% | 30% |

**结论**：ml-content 在仅 3 个服务实例时就超过 max_connections。这是 JDBC 模式的固有缺陷——每个实例直连全部数据源，连接数线性膨胀。P8 级别应该评估 ShardingSphere-Proxy 收敛连接。

**问题三：分片算法没有考虑扩容**

```yaml
algorithm-expression: ds${user_id % 4}
algorithm-expression: t_order_${(user_id.intdiv(4)) % 4}
```

纯取模算法。从 4 库扩到 8 库 = 所有数据重新 hash。没有一致性哈希预留，没有虚拟槽（slot）设计。P8 级别应评估：一致性 Hash + 虚拟节点 或 基因法（orderNo 内嵌 userId 的后 4 位）。

**问题四：基因法优于 order_no_mapping**

```
当前方案：order_no → order_no_mapping 表 → user_id → 分片路由（额外一次查询）
基因法：  order_no = 时间戳 + 机器码 + userId 后4位 + 自增序号
          → 直接解析 order_no → 分片路由（零额外查询）
```

order_no_mapping 表自身也会成为瓶颈（按 order_no 查是唯一索引，还好；但如果做范围查询就会扫全表）。基因法更优雅，且不引入额外存储。

### 1.2 Elasticsearch：选型正确，细节待优化

#### 为什么 ES 是对的

对于社交+电商系统，搜索是核心功能。MySQL 的 FULLTEXT + ngram 分词器能做到基本的搜索，但：
- ES 8.x 的 Completion Suggester（FST 数据结构）比 LIKE 前缀匹配快 10 倍+
- Search After 深分页性能碾压 MySQL 的 LIMIT OFFSET
- 聚合分析（热搜排行）在 ES 中是 O(1) 操作，MySQL 中需要全表扫描

**所以 ES 是正确的选择。**

#### 但需要评估的点

| 关注点 | 现状 | 建议 |
|--------|------|------|
| **License** | ES 8.12.2 使用 SSPL + Elastic License 2.0 | 商业项目中需法务审核。如果担心 → OpenSearch 2.x |
| **IK 分词器** | 代码中用了 ik_smart/ik_max_word，依赖 ES 集群预先安装 | jieba 分词器词库更大，建议做对比评估 |
| **ES 写多读少的场景** | 大量笔记实时写入 → ES 默认 refresh_interval=1s | 可调整为 5s~30s（牺牲实时性换吞吐） |
| **ES 做主存储的风险** | ES 不是数据库，不应存唯一可信数据 | 主体数据存 MySQL，ES 仅做搜索索引 |
| **双索引写入缺失** | `IndexRebuildJob` 仅写 note_index 和 product_index，suggest_index 数据来源不明确 | 补充 suggest_index 的数据写入 |

### 1.3 Redis：使用方式多样且正确，但高可用缺失

#### 使用方式评审

| 用法 | 合理性 | 评估 |
|------|:---:|------|
| 缓存（Cache Aside） | ✅ | 正确 |
| 分布式锁（Redisson RLock + Watchdog） | ✅ | Redisson 正确选择 |
| 限流计数器（ZSet 滑动窗口） | ✅ | Lua 原子操作正确 |
| 购物车主存储（Hash+Set+ZSet） | ✅ | 但持久化配置要关注 |
| 计数服务（String + Buffer → DB） | ✅ | CounterBuffer 双缓冲正确 |
| Feed 收件箱（ZSet） | ⚠️ | 写扩散问题见下面的分析 |
| 幂等键（SET NX EX） | ✅ | 正确 |
| Token 黑名单 | ✅ | 正确 |

#### 致命发现：Redis 持久化配置有重大风险

```yaml
# docker-compose.yml 的 Redis 启动命令
command: redis-server --port 16379 --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
```

| 配置 | 值 | 风险 |
|------|-----|------|
| **RDB** | 未显式配置 `save` | Redis 7 默认 `save 3600 1 300 100 60 10000` — RDB 可能**仍在运行**，大 Key 做 RDB 时会 fork 子进程导致延迟 |
| **AOF** | `appendonly yes` | ✅ 已启用，但 `appendfsync` 走默认 `everysec`（可接受） |
| **maxmemory** | 256MB | 🔴 购物车 + Feed 收件箱 + 粉丝列表 + 缓存全挤在 256MB |
| **eviction** | `allkeys-lru` | 🔴 LRU 淘汰会删除**任何 Key**，包括购物车数据！ |
| **高可用** | 单机 | 🔴 无 Sentinel/Cluster，宕机全站不可用 |

**最严重的问题**：`allkeys-lru` 淘汰策略下，当内存达到 256MB，Redis 会淘汰所有 Key（包括购物车数据、计数数据、幂等键）。volatile-lru（仅淘汰带 TTL 的 Key）才是正确的选择。

#### Feed 写扩散量化分析

```
100 万粉丝大 V 发布一条笔记：
→ FeedPushConsumer.checkBigV() → true（粉丝 > 10 万）
→ 遍历粉丝 ZSet（myxhs:follow:follower:{authorId}）→ 100 万成员
→ 对每个粉丝执行：ZADD myxhs:feed:inbox:{fanId} noteId timestamp
→ 100 万次 ZADD → 约 30~60 秒（单线程 Redis，Pipeline 批处理）

如果 10 个大 V 同时发布：
→ Pipeline 固然能批量推送，但 1000 万次 ZADD → Redis CPU 100%
→ 期间其他 Redis 操作（缓存读取、分布式锁）全部阻塞
→ 整个系统进入不可用状态
```

**P8 级别的对策**：
1. **延迟推**：大 V 的笔记先发消息到 `FEED_TOPIC`，由 Consumer 异步推进收件箱
2. **推拉混合**：活跃粉丝（近 7 天登录的）推 → 非活跃粉丝在下一次打开时拉
3. **分片队列**：大 V 的粉丝列表分片，多个 Consumer 并行推送
4. **Redis 集群**：Feed 收件箱 Key 按 userId hash 到不同 Redis 节点，分散压力

---

## 第二章：消息与事件方案评审

### 2.1 RocketMQ：选对了，但用浅了

#### 选型比选

| 方案 | 事务消息 | 延时消息 | 顺序消息 | 运维复杂度 | P8 适配度 |
|------|:---:|:---:|:---:|:---:|:---:|
| **RocketMQ（当前）** | ✅ 原生 | ✅ 18 级 | ✅ 原生 | 中 | ★★★★ |
| Kafka | ⚠️ 需要 idempotent producer | ❌ 需外部实现 | ✅ 按 partition | 中 | ★★★ |
| Pulsar | ✅ 原生 | ✅ 原生 | ✅ 原生 | 高 | ★★★★ |

my-xhs 的下单链路依赖事务消息——这是 RocketMQ 的核心优势。Kafka 的事务消息行为不同（幂等 Producer + 事务），无法做到"半消息先发、本地事务提交后可见"。所以**选择 RocketMQ 是正确的**。

#### 但使用层面的问题

**问题一：用 5.1.4 镜像但走 4.x 协议**

RocketMQ 5.x 的最大变化是引入 Proxy 模式（gRPC 协议，计算存储分离）。但 my-xhs 的 Broker 以 4.x Remoting 协议启动（mqbroker -n namesrv）。Proxy 模式的优势（轻量级 SDK、多语言支持、网络隔离）全部没有用上。

**问题二：延时消息粒度固定**

```java
// 仅使用 delayLevel=16 = 30 分钟
rocketMQTemplate.syncSend(ORDER_CLOSE_TOPIC, ..., 3000, 16);
```

RocketMQ 默认只有 18 个预定义延迟级别。如果需要 45 分钟的关单（常见的电商场景），要么改 broker 的 `messageDelayLevel`，要么换用定时任务兜底。当前是 XXL-Job 每分钟扫描 + 延时消息 30 分钟双重保险——这个设计合理但不优雅。

**问题三：消息轨迹全局关闭**

Canal 的 `rocketmq.enable.message.trace = false`。业务 Producer 也都没有开启 trace。排查消息丢失问题时只能靠应用日志，无法从 MQ 层面看到消息的完整生命周期。

### 2.2 Canal：EOL 是致命问题

#### Canal 的现状

Canal 1.1.7 是当前版本。2019 年最后发布，社区已停止维护（issues 堆积、PR 无人 review）。GitHub 上用户反馈：MySQL 8.0 的新数据类型（如 JSON_TABLE、窗口函数相关的 binlog 变更）可能解析失败。

#### 替代方案评估

| 方案 | 社区 | MySQL 8.0 支持 | 学习成本 | 与 RocketMQ 集成 |
|------|:---:|:---:|:---:|------|
| **Canal 1.1.7（当前）** | EOL ❌ | 部分 | 低 | ✅ 原生 RocketMQ |
| Debezium | 🔥 活跃 | ✅ | 中 | ⚠️ 需 Kafka → RocketMQ 桥接 |
| Flink CDC | 🔥 活跃 | ✅ | 中高 | ✅ Flink SQL → RocketMQ Sink |

**P8 级别建议**：
- 如果只用了 RocketMQ，且不想引入 Kafka → **Flink CDC**（Flink SQL 直接消费 binlog → 写入 RocketMQ）。Flink CDC 支持全量+增量一体化。
- 如果未来评估引入 Kafka → **Debezium** + Kafka Connect（成熟的 CDC 工具，但需要在 Kafka 和 RocketMQ 之间做桥接）
- 如果不能替换 → 至少升级到 Canal 1.1.8（如果有）并监控社区状态

### 2.3 XXL-Job：存在更优选择

#### 为什么 XXL-Job 的选择值得商榷

| 候选方案 | XXL-Job | PowerJob | Spring @Scheduled |
|------|:---:|:---:|:---:|
| **作者** | 许雪里（已转 PowerJob） | 许雪里（当前维护） | Spring 官方 |
| **社区** | 活跃度下降 | 活跃 | — |
| **分片支持** | ✅ | ✅ | ❌ |
| **MapReduce** | ❌ | ✅ | ❌ |
| **容器部署** | 原生支持 | 原生支持 | 无调度中心 |

**核心问题：16 个 Handler 中几乎没用分片**

审查所有 16 个 XXL-Job Handler，除了极个别任务（如推荐离线计算 Item-CF），绝大多数任务本质就是**定时轮询**——完全可以用 Spring @Scheduled 替代。分片能力被浪费了。

**P8 级别建议**：
- 如果要保留调度中心（分布式环境下确实需要），迁移到 **PowerJob**。同一作者、API 相似、且支持 MapReduce
- 如果不需要分片，简化为 Spring @Scheduled + Redisson 分布式锁（更加轻量）

---

## 第三章：服务治理方案评审

### 3.1 Sentinel 1.8.8：选择正确，实现不完整

#### 对比分析

| 候选 | Spring Cloud 官方推荐 | Dashboard | 规则热更新 | Java Agent | 社区 |
|------|:---:|:---:|:---:|:---:|:---:|
| **Sentinel（当前）** | ⚠️ 适配不完整 | ✅ 成熟 | ✅ Nacos 数据源 | ❌ | 中等 |
| Resilience4j | ✅ 官方推荐 | ⚠️ 需自建 | ⚠️ 需自定义 | ❌ | 活跃 |
| Hystrix | ❌ | ✅ | ❌ | ❌ | EOL |

**选择 Sentinel 是正确的**：对于阿里巴巴技术栈（Nacos + RocketMQ + Sentinel），三者原生集成更好。且 Sentinel 的 Dashboard 是真正的 P8 级能力——实时流量曲线、拖拽调整规则、秒级生效。

#### 但实现层面的缺失

1. **只有 Flow 规则，无 Degrade 规则**：所有 yml 中仅定义了 `sentinel.datasource.flow.nacos`，没有 `sentinel.datasource.degrade.nacos`。意味着熔断降级**未配置**——Sentinel 能力只用了一半。

2. **Sentinel Dashboard 当前未部署**：docker-compose 中无 sentinel-dashboard 容器。端口 18082 在配置中写了但没服务监听。规则管理全靠 Nacos 配置文件手动编辑——失去了 Dashboard 的核心价值。

3. **Spring Cloud CircuitBreaker 抽象层**：my-xhs 直接使用了 Sentinel 原生 API（@SentinelResource 注解）。如果想切换到 Resilience4j，需要大量改动。更好的方案是通过 Spring Cloud CircuitBreaker 抽象层包装（支持 `ReactiveCircuitBreakerFactory`），实现方案可插拔。

### 3.2 Nacos 2.3.0：选择正确，使用不足

#### 为什么 Nacos 是对的

| 候选 | 注册+配置一体化 | 元数据支持 | CAP 可切换 | Raft 共识 | 学习成本 |
|------|:---:|:---:|:---:|:---:|:---:|
| **Nacos（当前）** | ✅ | ✅ | ✅ | ✅ | 低 |
| Consul | ✅ | ✅ | ❌ | ✅（Raft） | 中 |
| Eureka | ❌ | ✅ | ❌ | ❌ | 低 |

Nacos 作为"注册+配置"一体方案是正确的——既能做服务发现（AP Distro 协议），又能做配置中心（CP Raft 协议）。

#### 使用层面的问题

1. **AP/CP 模式未显式选择**：Nacos 2.x 默认 AP（Distro 协议）。注册中心选 AP 是对的（服务发现的短暂不一致比服务不可用危害更小）。但代码中没有任何关于模式的配置说明——意味着接手的人不知道当前是 AP 还是 CP。

2. **Config 全服务未启用**：已在前面的 P8 评审中详细分析。15 个服务 `spring.cloud.nacos.config.enabled: false`。Nacos 的一半能力被闲置。

3. **无元数据配置**：所有服务的 `spring.cloud.nacos.discovery.metadata` 都是空的。`version` 字段缺失导致无法做灰度路由。

### 3.3 SkyWalking 9.7.0：选择正确，落地未完成

SkyWalking 作为 Java 微服务的 APM 工具是正确的——通过 Java Agent 自动插桩，零代码改动即可采集链路数据。但当前 Agent 未挂载，OAP Server 徒耗 ES 存储（没有任何 Trace 数据写入）。

**附加问题**：OpenTelemetry 是否应该评估？OTel 是 CNCF 标准，Java Agent 支持更广（不仅限于 SkyWalking 后端）。如果未来想切换链后端（如 Jaeger，费用更低的方案），OTel 可以零代码切换。但对 Java 微服务，SkyWalking 的 Auto-Instrumentation 仍然是最省事的方案。

---

## 第四章：分布式 ID 方案评审

### 4.1 CosId vs 自研——方向错了

#### 实际状态

**pom.xml** 声明了 `<cosid.version>2.6.8</cosid.version>`，但：
- `dependencyManagement` 中**没有 CosId 的 `<dependency>` 条目**
- 全项目 Java 文件中 **0 处引用 CosId**
- 实际使用的是**自研 `SegmentIdGenerator`** + **MyBatis-Plus IdWorker**

#### 评审

自研 SegmentIdGenerator 的双 Buffer 号段模式设计是正确的（号段预加载 + 原子切换）。但 P8 级别的项目不应该重复造轮子：

| 能力 | 自研 SegmentIdGenerator | CosId SegmentChainId |
|------|:---:|:---:|
| 双 Buffer 切换 | ✅ 实现正确 | ✅ |
| 号段动态调整 | ❌ 号段大小固定 | ✅ 根据消耗速率动态调整 |
| 时钟回拨处理 | ❌ **未处理** | ✅ 预留窗口 + 自动补偿 |
| 号段预热 | ❌ 按需加载 | ✅ 后台线程预热 |
| 社区维护 | ❌ 单点故障（代码在人不在就没维护） | ✅ 社区维护 |

**结论**：
1. 要么正式集成 CosId（删除自研代码）
2. 要么给自研方案补充：时钟回拨处理、号段动态伸缩、号段预热
3. **当前状态（声明了但没用）是最差的选择**——给面试官的观感是"知道 CosId 但懒得集成"

---

## 第五章：方案冲突与冗余

### 5.1 限流方案的三层重叠

```
Gateway RateLimitFilter → Sentinel LeapArray 滑动窗口
@RateLimit AOP → Redis Lua ZSet 滑动窗口
```

**冲突本质**：一个请求可能被两套限流各判断一次。Gateway 阈值 1000 QPS，AOP 阈值 100 QPS → 实际以低者为准。但运维需要维护两套规则、两个 Dashboard、两种故障模式。

**P8 建议**：明确职责边界：
- Gateway 层：总入口限流，按服务，防止整体过载（粗粒度）
- AOP 层：精细限流，按方法 + 用户，防止热点方法过载（细粒度）
- 阶梯原则：Gateway 阈值 > AOP 阈值（保证流量先到 AOP 再来判断）

### 5.2 Redis Key 命名不一致

`RedisKeyConstants` 定义了 40+ 个规范 Key。但 4 个地方存在硬编码 Key：
- `FeedPushConsumer.checkBigV()`：`"myxhs:user:bigv:" + authorId`
- `CartReconcileJob`：`"myxhs:cart:items:"`, `"myxhs:cart:checked:"`
- `IndexRebuildJob`：`"myxhs:search:index:rebuild:status"`
- `InventoryCacheEvictConsumer`：`"inventory:bucket:"`, `"inventory:total:"`

### 5.3 RokcetMQ 5.x 版本与协议的错配

Broker 镜像 5.1.4 但以 4.x Remoting 协议启动。意味着 `rocketmq-spring-boot-starter:2.3.0` 是以 Remoting 协议连接的。5.x 的 Proxy 能力（gRPC、轻量 SDK、多语言）完全没用到。实际上等于买 5.x 用 4.x 的体验。

---

## 第六章：如果重新做技术选型

### P8 工程师的重新选型清单

| 维度 | 当前选择 | 重新选型 | 原因 |
|------|---------|---------|------|
| 分库分表 | ShardingSphere-JDBC | **ShardingSphere-Proxy** | 连接收敛 + 运维友好。JDBC 模式的连接膨胀在生产 10+ 实例时不可承受 |
| 分片算法 | 纯 MOD | **一致性 Hash + 虚拟节点** | 为扩容预留 |
| 非分片键查询 | order_no_mapping 表 | **基因法**（orderNo 内嵌 userId） | 零额外查询 |
| CDC | Canal 1.1.7 | **Flink CDC** | Canal EOL + Flink CDC 支持全量+增量一体化 |
| 调度 | XXL-Job | **保留但评估 PowerJob** | 分片能力真正需要时，PowerJob 的 MapReduce 更实用 |
| ES | ES 8.12.2 | **ES 8.x 或 OpenSearch** | 根据 License 风险评估 |
| Redis 高可用 | 单点 | **Redis Sentinel（至少 3 节点）** | P8 项目不可单点 |
| Redis 淘汰策略 | allkeys-lru | **volatile-lru** | 避免淘汰非 TTL 的业务数据 |
| 分布式 ID | 自研 SegementIdGenerator | **CosId SegmentChainId** | 正式集成，删除自研 |
| 消息轨迹 | 关闭 | **开启** | 排查问题必需 |
| Sentinel 规则 | 仅有 Flow | **Flow + Degrade 完整配置** | 补齐熔断降级 |

### 连接收敛的具体收益（ShardingSphere-Proxy）

```
当前 JDBC 模式（3 服务实例）：
  mysql-content: 6 services × 3 instances × 20 connections = 360（超 max_connections=300）

Proxy 模式：
  mysql-content: 1 Proxy × 20 connections = 20（所有服务实例共享）
  360 → 20，减少 94%
```

---

## 总结评分

| 维度 | 得分 | 评价 |
|------|:---:|------|
| 技术选型方向 | **82/100** | 大方向基本都对了——RocketMQ/Sentinel/Nacos/ShardingSphere/ES——选型眼光是 P8 的 |
| 方案实现深度 | **45/100** | 选对了但没用好。分库是伪分库、Canal 是 EOL、CosId 没集成、Sentinel 只用了一半 |
| 方案间协调性 | **60/100** | 限流重复、Key 不统一、版本协议错配——有意识但缺执行力 |
| 前瞻性 | **65/100** | 流量染色+路由预留是对的，但扩容方案缺一致性 Hash、GC 规划未做 |
| 运维复杂度 | **35/100** | 14 个中间件 + 14 个连接池组 + JDBC 直连膨胀——3 个服务实例就超 max_connections |
| **综合** | **57/100** | **选型眼光 P8 级，落地深度 P5 级** |

---

## 如果即刻整改，Top 10 建议

| # | 建议 | 影响 | 工时 |
|---|------|------|:---:|
| 1 | **评估 Flink CDC 替代 Canal** | Canal→MQ→Consumer 链 | 8h |
| 2 | **Redis allkeys-lru → volatile-lru + Sentinel 部署** | 全站稳定性 | 4h |
| 3 | **正式集成 CosId，删除自研 SegmentIdGenerator** | ID 生成层 | 4h |
| 4 | **Sentinel Degrade 规则补充（至少 5 条核心规则）** | 熔断降级 | 2h |
| 5 | **ShardingSphere 改为 Proxy 模式或做连接收敛** | DB 连接池 | 4h |
| 6 | **分片算法评估一致性 Hash** | 未来扩容 | 4h |
| 7 | **开启 RocketMQ 消息轨迹** | 问题排查 | 1h |
| 8 | **RedisKeyConstants 硬编码统一** | 维护性 | 2h |
| 9 | **限流方案的阶梯阈值设计**（Gateway > AOP） | 运维清晰度 | 2h |
| 10 | **Nacos 元数据 version 字段配置** | 灰度发布 | 1h |
